package messina.ui.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import messina.Glucose
import messina.sensors.GlucoseReading
import messina.sensors.SensorId
import messina.sensors.Sensors
import messina.settings.Alarm
import messina.settings.GlucoseUnit
import messina.settings.Settings
import messina.utils.Time
import messina.utils.format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

val CHART_LEFT_PAD_DP = 20.dp
val CHART_RIGHT_PAD_DP = 20.dp
val CHART_BOTTOM_PAD_DP = 20.dp

private const val PORTRAIT_LONG_PRESS_MS = 75L
private const val DOUBLE_TAP_HOLD_MS = 200L

// Readings further than this from the scrubbed time never highlight, so a disconnected sensor whose
// readings end in the past doesn't pin a stale highlight (and the cursor).
private val HIGHLIGHT_SNAP_TOLERANCE = 15.minutes

// Only the single closest reading is highlighted, plus any other curve whose nearest reading lands
// within this of that point in time. So overlapping live sensors all show, but elsewhere the cursor
// snaps to just one curve as a last resort.
private val HIGHLIGHT_TIE_TOLERANCE = 1.minutes

/** Mutable pan/zoom state used in landscape mode. Offset is milliseconds back from now. */
private class Viewport(initialSpan: Duration) {
    var span by mutableStateOf(initialSpan)
    val offset = Animatable(0f)
}


@Composable
fun GlucoseChart(
    modifier: Modifier = Modifier,
    data: Map<SensorId, List<GlucoseReading>>,
    maxTime: Instant,
    onHighlight: (Map<SensorId, GlucoseReading>?) -> Unit,
    landscapeMode: Boolean = false,
    loadData: (start: Instant, end: Instant) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewport = remember { Viewport(if (landscapeMode) 24.hours else 3.hours) }
    var highlightTime by remember { mutableStateOf<Instant?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall
    val colorScheme = MaterialTheme.colorScheme

    val effectiveOffset = viewport.offset.value.toLong().milliseconds
    val timeEnd = maxTime - effectiveOffset
    val timeStart = timeEnd - viewport.span
    val filteredData = data.mapValues { (id, readings) ->
        val smoothed = Sensors.get(id)?.smoothing?.apply(readings) ?: readings
        smoothed.filter { it.time in timeStart..timeEnd }
    }

    val computeHighlight by rememberUpdatedState { x: Float?, chartLeft: Float, chartWidth: Float ->
        highlightTime = x?.let {
            val fraction = ((it - chartLeft) / chartWidth).coerceIn(0f, 1f)
            timeStart + (fraction * viewport.span.inWholeMilliseconds).toLong().milliseconds
        }
        onHighlight(highlightTime?.let { readingsNear(filteredData, it) })
    }

    LaunchedEffect(effectiveOffset, viewport.span) {
        loadData(timeStart, timeEnd)
    }

    Spacer(
        modifier = modifier
            .padding(8.dp)
            // Key on landscape vs portrait so the handler restarts on orientation change.
            .pointerInput(landscapeMode) {
                val chartLeft = CHART_LEFT_PAD_DP.toPx()
                val chartWidth = size.width - chartLeft - CHART_RIGHT_PAD_DP.toPx()
                val setHighlight: (Float?) -> Unit =
                    { x -> computeHighlight(x, chartLeft, chartWidth) }
                if (landscapeMode) handleLandscapeGestures(viewport, scope, setHighlight)
                else handlePortraitGestures(setHighlight)
            }
            .drawWithCache {
                onDrawBehind {
                    drawChart(
                        filteredData,
                        highlightTime,
                        textMeasurer,
                        axisStyle,
                        colorScheme,
                        timeEnd,
                        viewport.span,
                        landscapeMode,
                    )
                }
            }
    )
}

/** Long-press to scrub a highlight cursor along the curve. */
private suspend fun PointerInputScope.handlePortraitGestures(
    setHighlight: (Float?) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val isLongPress = withTimeoutOrNull(PORTRAIT_LONG_PRESS_MS) {
            waitForUpOrCancellation(); false
        } ?: true
        if (!isLongPress) return@awaitEachGesture

        down.consume()
        setHighlight(down.position.x)
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.find { it.id == down.id } ?: break
            if (!change.pressed) break
            if (change.positionChanged()) {
                change.consume()
                setHighlight(change.position.x)
            }
        }
        setHighlight(null)
    }
}

/** Single-finger pan, two-finger pinch-to-zoom, double-tap-and-hold to scrub, edge double-tap to jump days. */
private suspend fun PointerInputScope.handleLandscapeGestures(
    viewport: Viewport,
    scope: CoroutineScope,
    setHighlight: (Float?) -> Unit,
) {
    val chartLeft = CHART_LEFT_PAD_DP.toPx()
    val chartWidth = size.width - chartLeft - CHART_RIGHT_PAD_DP.toPx()
    val doubleTapTimeoutMs = viewConfiguration.doubleTapTimeoutMillis

    fun pan(deltaPx: Float) {
        val msPerPx = viewport.span.inWholeMilliseconds.toFloat() / chartWidth
        val newOffset = (viewport.offset.value + deltaPx * msPerPx).coerceAtLeast(0f)
        scope.launch { viewport.offset.snapTo(newOffset) }
    }

    fun zoom(scaleFactor: Float, centroidFraction: Float) {
        val newSpan = (viewport.span / scaleFactor.toDouble()).coerceIn(3.hours, 24.hours)
        // When zooming in, anchor at the centroid; when zooming out, anchor at center.
        val anchor = if (newSpan < viewport.span) 1.0 - centroidFraction else 0.5
        val newOffsetMs =
            (viewport.offset.value.toLong().milliseconds + (viewport.span - newSpan) * anchor)
                .coerceAtLeast(Duration.ZERO).inWholeMilliseconds.toFloat()
        scope.launch { viewport.offset.snapTo(newOffsetMs) }
        viewport.span = newSpan
    }

    fun jumpDay(goBack: Boolean) {
        val targetMs = if (viewport.span > 15.hours) {
            // Snap so the visible window starts at a local midnight.
            val now = Clock.System.now()
            val visibleStart = now - viewport.offset.value.toLong().milliseconds - viewport.span
            val newDay = if (goBack) {
                val dayStart = Time.fromInstant(visibleStart).day()
                if (dayStart == visibleStart) {
                    Time.fromInstant(visibleStart).day() - 1.days
                } else {
                    dayStart
                }
            } else {
                Time.fromInstant(visibleStart + 2.minutes).day() + 1.days
            }
            (now - (newDay + viewport.span - 2.minutes))
                .coerceAtLeast(Duration.ZERO).inWholeMilliseconds.toFloat()
        } else {
            val day = 24.hours.inWholeMilliseconds.toFloat()
            (viewport.offset.value + if (goBack) day else -day).coerceAtLeast(0f)
        }
        scope.launch { viewport.offset.animateTo(targetMs, tween(200)) }
    }

    var lastTapUpMs = -1L
    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false)
        val downMs = Clock.System.now().toEpochMilliseconds()
        val isDoubleTap = lastTapUpMs > 0 && (downMs - lastTapUpMs) < doubleTapTimeoutMs

        if (isDoubleTap) {
            firstDown.consume()
            lastTapUpMs = -1L
            val edgeThreshold = size.width * 0.2f
            val isEdgeTap = firstDown.position.x < edgeThreshold ||
                    firstDown.position.x > size.width - edgeThreshold
            // Quick release on an edge = day jump. Held = highlight scrub.
            val quickRelease = withTimeoutOrNull(DOUBLE_TAP_HOLD_MS) { waitForUpOrCancellation() }
            when {
                quickRelease != null && isEdgeTap -> jumpDay(firstDown.position.x < size.width / 2f)
                quickRelease == null -> {
                    setHighlight(firstDown.position.x)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.find { it.id == firstDown.id } ?: break
                        if (!change.pressed) break
                        if (change.positionChanged()) {
                            change.consume()
                            setHighlight(change.position.x)
                        }
                    }
                    setHighlight(null)
                }
            }
            return@awaitEachGesture
        }

        // Pan / pinch-zoom loop.
        val prevPositions = mutableMapOf(firstDown.id to firstDown.position)
        var prevPinchSpan = -1f
        var hasMoved = false
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                if (!hasMoved) lastTapUpMs = Clock.System.now().toEpochMilliseconds()
                break
            }
            if (pressed.size >= 2) {
                val (p1, p2) = pressed[0].position to pressed[1].position
                val pinchSpan = (p2 - p1).getDistance()
                val centroidFraction =
                    (((p1.x + p2.x) / 2f - chartLeft) / chartWidth).coerceIn(0f, 1f)
                if (prevPinchSpan > 0f && pinchSpan > 0f) zoom(
                    pinchSpan / prevPinchSpan,
                    centroidFraction
                )
                prevPinchSpan = pinchSpan
                pressed.forEach { it.consume() }
                hasMoved = true
            } else {
                // Reset pinch baseline so the next two-finger event doesn't jump.
                prevPinchSpan = -1f
                val change = pressed[0]
                val prev = prevPositions[change.id]
                if (prev != null && change.positionChanged()) {
                    pan(change.position.x - prev.x)
                    change.consume()
                    hasMoved = true
                }
            }
            pressed.forEach { prevPositions[it.id] = it.position }
        }
    }
}

@OptIn(ExperimentalTextApi::class)
fun DrawScope.drawChart(
    data: Map<SensorId, List<GlucoseReading>>,
    highlightTime: Instant?,
    textMeasurer: TextMeasurer,
    axisStyle: TextStyle,
    colorScheme: ColorScheme,
    timeEnd: Instant,
    timeSpan: Duration = 3.hours,
    landscapeMode: Boolean = false,
) {
    val colors = run {
        // When there's only one sensor active, but two curves are being drawn, we assume it's the
        // user switching sensor, so same color.
        if (data.size == 2 && Sensors.active.size == 1) {
            data.keys.associateWith { Sensors.COLORS[0] }
        } else {
            data.keys.withIndex().associate { (index, id) ->
                id to Sensors.COLORS[index % Sensors.COLORS.size]
            }
        }
    }

    val timeStart = timeEnd - timeSpan
    val allValues = data.values.flatten().map { it.glucose.value }

    val isMmol = Settings.glucoseUnit == GlucoseUnit.Mmol
    val yMin = if (isMmol) 2.0 else 36.0
    val clampMin = if (isMmol) 2.2 else 39.6
    val yStep = if (isMmol) 2.0 else 50.0
    val yFirstGrid = ceil(yMin / yStep) * yStep
    val yMax = if (isMmol) maxOf(10.0, (allValues.maxOrNull() ?: 0.0) + 1.0)
    else maxOf(180.0, (allValues.maxOrNull() ?: 0.0) + 20.0)

    val chartOffset = Offset(CHART_LEFT_PAD_DP.toPx(), 0f)
    val chartSize = Size(
        size.width - CHART_LEFT_PAD_DP.toPx() - CHART_RIGHT_PAD_DP.toPx(),
        size.height - CHART_BOTTOM_PAD_DP.toPx()
    )
    val chartBottomY = chartOffset.y + chartSize.height
    val totalMs = (timeEnd - timeStart).inWholeMilliseconds.toFloat()

    fun xOf(time: Instant): Float =
        chartOffset.x + ((time - timeStart).inWholeMilliseconds.toFloat() / totalMs) * chartSize.width

    fun yOf(value: Double): Float =
        chartOffset.y + chartSize.height - ((value - yMin) / (yMax - yMin)).toFloat() * chartSize.height

    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx()))
    val stroke1 = 1.dp.toPx()

    fun drawDashedHorizontalLine(value: Double, color: Color) {
        if (value !in yMin..yMax) return
        val y = yOf(value)
        drawLine(
            color, Offset(chartOffset.x, y), Offset(chartOffset.x + chartSize.width, y),
            stroke1, pathEffect = dashEffect
        )
    }

    // Grid + Y labels
    val axisColor = colorScheme.primary.copy(alpha = 0.3f)
    val gridColor = axisColor.copy(alpha = axisColor.alpha * 0.6f)
    var v = yFirstGrid
    while (v <= yMax) {
        drawDashedHorizontalLine(v, gridColor)
        val label = textMeasurer.measure("%.0f".format(v), style = axisStyle)
        drawText(
            label, color = colorScheme.onSurfaceVariant, topLeft = Offset(
                x = chartOffset.x - label.size.width - 4.dp.toPx(),
                y = yOf(v) - label.size.height / 2f
            )
        )
        v += yStep
    }

    // Threshold lines
    val thresholdColor = colorScheme.error.copy(alpha = 0.5f)
    Settings.alarms.filterIsInstance<Alarm.Glucose>().forEach {
        drawDashedHorizontalLine(it.threshold.value, thresholdColor)
    }

    // Midnight lines + day labels
    if (landscapeMode) {
        var day = Time.fromInstant(timeStart + 1.days)
        while (day.day() <= timeEnd) {
            val x = xOf(day.day())
            drawLine(
                colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                Offset(x, chartOffset.y), Offset(x, chartBottomY), stroke1
            )
            val label = textMeasurer.measure(day.toDayMonth(), style = axisStyle)
            drawText(
                label, color = colorScheme.onSurfaceVariant,
                topLeft = Offset(x + 4.dp.toPx(), chartOffset.y)
            )
            day = Time.fromInstant(day.day() + 1.days)
        }
    }

    // X axis: baseline + hourly ticks + labels
    val tickHeight = 4.dp.toPx()
    drawLine(
        axisColor, Offset(chartOffset.x, chartBottomY),
        Offset(chartOffset.x + chartSize.width, chartBottomY), stroke1
    )
    var t = Instant.fromEpochSeconds(((timeStart.epochSeconds / 3600) + 1) * 3600)
    while (t <= timeEnd) {
        val x = xOf(t)
        drawLine(axisColor, Offset(x, chartBottomY), Offset(x, chartBottomY + tickHeight), stroke1)
        val label = textMeasurer.measure(Time.fromInstant(t).toHHMM(), style = axisStyle)
        drawText(
            label, color = colorScheme.onSurfaceVariant, topLeft = Offset(
                x = x - label.size.width / 2f,
                y = chartBottomY + tickHeight + 2.dp.toPx()
            )
        )
        t += 1.hours
    }

    // Glucose curves
    for ((id, history) in data) {
        if (history.size < 2) continue

        val color = colors[id]!!
        val fillBrush = Brush.verticalGradient(
            listOf(color.copy(alpha = 0.35f), Color.Transparent),
            startY = chartOffset.y,
            endY = chartBottomY,
        )

        for (segment in splitAtGaps(history)) {
            val pts =
                segment.map { Offset(xOf(it.time), yOf(maxOf(clampMin, it.glucose.value))) }
            val line = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                pts.drop(1).forEach { lineTo(it.x, it.y) }
            }

            // If there's only a single curve, draw a fill beneath it
            if (data.values.size == 1) {
                val fill = Path().apply {
                    moveTo(pts.first().x, chartBottomY)
                    pts.forEach { lineTo(it.x, it.y) }
                    lineTo(pts.last().x, chartBottomY)
                    close()
                }
                drawPath(fill, brush = fillBrush, style = Fill)
            }
            drawPath(line, color = color, style = Stroke(2.dp.toPx()))
        }
    }

    // Highlight cursor + value bubble
    val target = highlightTime ?: return
    val highlights = readingsNear(data, target) ?: return

    val cursorX = xOf(target)
    drawLine(
        colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        Offset(cursorX, chartOffset.y), Offset(cursorX, chartBottomY),
        strokeWidth = 2.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())),
    )

    for ((id, reading) in highlights) {
        val displayValue = maxOf(clampMin, reading.glucose.value)
        val hx = xOf(reading.time)
        val hy = yOf(displayValue)
        drawCircle(colors[id]!!, radius = 4.dp.toPx(), center = Offset(hx, hy))

        if (landscapeMode) {
            val style = axisStyle.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold)
            val displayGlucose =
                if (isMmol) Glucose.fromMmol(displayValue) else Glucose.fromMgDl(displayValue)
            val label = textMeasurer.measure(displayGlucose.format(), style = style)
            val hPad = 6.dp.toPx()
            val vPad = 4.dp.toPx()
            val labelX = (hx - label.size.width / 2f).coerceIn(
                chartOffset.x, chartOffset.x + chartSize.width - label.size.width
            )
            val labelY =
                (hy - label.size.height - vPad * 2 - 6.dp.toPx()).coerceAtLeast(chartOffset.y)
            drawRoundRect(
                color = colorScheme.onSurface.copy(alpha = 0.55f),
                topLeft = Offset(labelX - hPad, labelY - vPad),
                size = Size(label.size.width + hPad * 2, label.size.height + vPad * 2),
                cornerRadius = CornerRadius(6.dp.toPx()),
            )
            drawText(label, color = colorScheme.surface, topLeft = Offset(labelX, labelY))
        }
    }
}

// Highlights the single reading closest to the scrubbed time, plus any other curve whose nearest
// reading falls within HIGHLIGHT_TIE_TOLERANCE of that reading's time. Readings past
// HIGHLIGHT_SNAP_TOLERANCE are ignored, so a sensor whose data ends in the past never pins a stale
// highlight.
private fun readingsNear(
    data: Map<SensorId, List<GlucoseReading>>,
    target: Instant,
): Map<SensorId, GlucoseReading>? {
    val nearest = data.mapNotNull { (id, readings) ->
        val reading = readings.minByOrNull { abs((it.time - target).inWholeMilliseconds) }
            ?: return@mapNotNull null
        val distance = (reading.time - target).absoluteValue
        if (distance > HIGHLIGHT_SNAP_TOLERANCE) null else id to reading
    }
    val closest =
        nearest.minByOrNull { (_, reading) -> abs((reading.time - target).inWholeMilliseconds) }
            ?: return null
    return nearest.filter { (_, reading) ->
        (reading.time - closest.second.time).absoluteValue <= HIGHLIGHT_TIE_TOLERANCE
    }.toMap()
}

// Splits readings wherever there's more than a 1-minute gap, dropping single-point segments.
private fun splitAtGaps(readings: List<GlucoseReading>): List<List<GlucoseReading>> {
    if (readings.isEmpty()) return emptyList()
    val segments = mutableListOf<MutableList<GlucoseReading>>()
    var current = mutableListOf(readings[0])
    for (i in 1 until readings.size) {
        if (readings[i].time - readings[i - 1].time > 15.minutes) {
            segments += current
            current = mutableListOf()
        }
        current += readings[i]
    }
    segments += current
    return segments.filter { it.size >= 2 }
}
