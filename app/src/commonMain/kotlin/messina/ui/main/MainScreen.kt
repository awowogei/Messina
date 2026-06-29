package messina.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import org.jetbrains.compose.resources.painterResource
import messina.app.generated.resources.Res
import messina.app.generated.resources.bell_slash
import messina.app.generated.resources.cloud
import messina.app.generated.resources.settings_cog
import messina.app.generated.resources.sensor
import messina.app.generated.resources.statistics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import messina.sensors.GlucoseReading
import messina.sensors.Sensor
import messina.sensors.SensorId
import messina.sensors.Sensors
import messina.settings.AlarmController
import messina.share.LibreView
import messina.utils.Time
import messina.utils.format
import kotlinx.coroutines.delay
import messina.Glucose
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@Composable
private fun TimeSinceLabel(
    sensor: Sensor,
    fontSize: TextUnit,
) {
    var now by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Clock.System.now()
            delay(1.seconds)
        }
    }

    val since = sensor.lastReadingTime?.let {
        val seconds = (now - it).inWholeSeconds.coerceAtLeast(0)
        "%02d:%02d".format(seconds / 60, seconds % 60)
    }
    val connected = sensor.connected
    Text(
        text = if (connected) {
            since ?: ""
        } else {
            "Disconnected" + (since?.let { " ($it)" } ?: "")
        },
        fontSize = if (connected) fontSize else fontSize * 0.85f,
        color = if (connected) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.error
        },
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun GlucoseDisplay(
    sensor: Sensor,
    highlight: Highlight?,
    scale: Float,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val reading: GlucoseReading?
    val trend: Glucose?
    if (highlight != null) {
        reading = highlight.reading
        trend = highlight.trend
    } else {
        reading = sensor.latestReading()
        trend = sensor.latestTrend()
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        if (highlight != null) {
            Text(
                text = Time.fromInstant(highlight.reading.time).toHHMM(),
                fontSize = (14f * scale).sp,
                color = colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        } else {
            TimeSinceLabel(
                sensor = sensor,
                fontSize = (14f * scale).sp,
            )
        }
        Text(
            text = reading?.glucose?.format(decimals = 2) ?: "---",
            fontSize = (64f * scale).sp,
            color = color ?: colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Text(
            text = if (reading != null) {
                if (trend != null) "%+.2f/min".format(trend.value) else "--"
            } else "",
            fontSize = (16f * scale).sp,
            color = colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun GlucoseDisplays(
    highlightedGlucose: Map<SensorId, Highlight>?,
    cache: Map<SensorId, List<GlucoseReading>>,
    modifier: Modifier = Modifier,
) {
    val colors = run {
        // When there's only one sensor active, but two curves are being drawn, we assume it's the
        // user switching sensor, so same color.
        if (cache.size <= 2 && Sensors.active.size == 1) {
            cache.keys.associateWith { null }
        } else {
            cache.keys.withIndex().associate { (index, id) ->
                id to Sensors.COLORS[index % Sensors.COLORS.size]
            }
        }

    }

    val sensors = highlightedGlucose?.keys?.mapNotNull { Sensors.get(it) } ?: Sensors.active

    Box(
        modifier = modifier.height(230.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (sensors.size) {
            0 -> Text(
                text = "---",
                fontSize = 64.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            1 -> {
                val sensor = sensors.first()
                GlucoseDisplay(
                    sensor = sensor,
                    highlight = highlightedGlucose?.get(sensor.id),
                    scale = 1f,
                    color = colors[sensor.id],
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            2 -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                for (sensor in sensors) {
                    GlucoseDisplay(
                        sensor = sensor,
                        highlight = highlightedGlucose?.get(sensor.id),
                        scale = 0.9f,
                        color = colors[sensor.id],
                    )
                }
            }

            3 -> Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val first = sensors.first()
                GlucoseDisplay(
                    sensor = first,
                    highlight = highlightedGlucose?.get(first.id),
                    scale = 0.55f,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = colors[first.id],
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    for (sensor in sensors.subList(1, 3)) {
                        GlucoseDisplay(
                            sensor = sensor,
                            highlight = highlightedGlucose?.get(sensor.id),
                            scale = 0.55f,
                            color = colors[sensor.id],
                        )
                    }
                }
            }

            else -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                for (column in sensors.take(4).chunked(2)) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        for (sensor in column) {
                            GlucoseDisplay(
                                sensor = sensor,
                                highlight = highlightedGlucose?.get(sensor.id),
                                scale = 0.55f,
                                color = colors[sensor.id],
                            )
                        }
                    }
                }
            }
        }
    }
}

private val FabSize = 72.dp

@Composable
private fun NavItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun MenuGridItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Text(label, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToSettings: () -> Unit,
    navigateToSensor: () -> Unit = {},
    onNavigateToScan: () -> Unit = {},
    onNavigateToShare: () -> Unit = {},
    onNavigateToLibreView: () -> Unit = {},
    state: MainState = viewModel()
) {
    val colorScheme = MaterialTheme.colorScheme

    val alarmActive by AlarmController.active.collectAsState()

    var showAddMenu by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var highlightedGlucose by remember { mutableStateOf<Map<SensorId, Highlight>?>(null) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            GlucoseChart(
                data = state.cache,
                maxTime = state.chartEnd,
                modifier = Modifier
                    .fillMaxSize()
                    .background(colorScheme.surface)
                    .safeDrawingPadding(),
                onHighlight = { highlightedGlucose = it },
                landscapeMode = true,
                loadData = state::loadData,
            )
            // TODO: This is so ugly...
            if (alarmActive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 72.dp)
                        .size(FabSize)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(colorScheme.secondary)
                        .clickable { AlarmController.stop() },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(Res.drawable.bell_slash),
                        contentDescription = "Stop alarm",
                        modifier = Modifier.size(32.dp),
                        colorFilter = ColorFilter.tint(Color.White)
                    )
                }
            }
            return@BoxWithConstraints
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.weight(1f).fillMaxWidth())
                GlucoseDisplays(
                    highlightedGlucose = highlightedGlucose,
                    cache = state.cache,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 0.dp, bottom = 24.dp)
                        .background(colorScheme.surface)
//                        .padding(vertical = 28.dp)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colorScheme.surface)
                        .padding(vertical = 16.dp)
                ) {
                    GlucoseChart(
                        data = state.cache,
                        maxTime = state.chartEnd,
                        onHighlight = { highlightedGlucose = it },
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        loadData = state::loadData,
                    )
                }
                Spacer(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .background(colorScheme.surface)
                )
            }

            Box(modifier = Modifier.background(colorScheme.surface)) {
                NavigationBar(
                    containerColor = Color.Transparent,
                    modifier = Modifier.height(60.dp).drawBehind {
                        val path = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(Rect(0f, 0f, size.width, size.height))
                        }
                        drawPath(path, colorScheme.surface)
                    }
                ) {
                    NavItem(onClick = navigateToSensor, modifier = Modifier.weight(1f)) {
                        Image(
                            painter = painterResource(Res.drawable.sensor),
                            contentDescription = "Sensor",
                            modifier = Modifier.size(26.dp).offset(x = 10.dp),
                            colorFilter = ColorFilter.tint(colorScheme.onSurface)
                        )
                    }
                    NavItem(onClick = { }, modifier = Modifier.weight(1f)) {
                        Image(
                            painter = painterResource(Res.drawable.statistics),
                            contentDescription = "Statistics",
                            modifier = Modifier.size(26.dp),
                            colorFilter = ColorFilter.tint(colorScheme.onSurface)
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    NavItem(onClick = onNavigateToShare, modifier = Modifier.weight(1f)) {
                        Image(
                            painter = painterResource(Res.drawable.cloud),
                            contentDescription = "Cloud",
                            modifier = Modifier.size(32.dp).offset(y = 2.dp),
                            colorFilter = ColorFilter.tint(colorScheme.onSurface)
                        )
                    }
                    NavItem(onClick = onNavigateToSettings, modifier = Modifier.weight(1f)) {
                        Image(
                            painter = painterResource(Res.drawable.settings_cog),
                            contentDescription = "Settings",
                            modifier = Modifier.size(26.dp).offset(x = (-20).dp),
                            colorFilter = ColorFilter.tint(colorScheme.onSurface)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(FabSize)
                        .align(Alignment.TopCenter)
                        .offset(y = -(FabSize / 2))
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(colorScheme.secondary)
                        .clickable {
                            if (alarmActive) AlarmController.stop() else showAddMenu = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (alarmActive) {
                        Image(
                            painter = painterResource(Res.drawable.bell_slash),
                            contentDescription = "Stop alarm",
                            modifier = Modifier.size(32.dp),
                            colorFilter = ColorFilter.tint(Color.White)
                        )
                    } else {
                        Text(
                            "+",
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Light,
                            color = colorScheme.onSecondary
                        )
                    }
                }
            }
        }

        if (showAddMenu) {
            // TODO: Currently if you click a button right after you close the bottom sheet it
            //  will ignore the input. Something about it being implemented as a separate activity.
            ModalBottomSheet(
                onDismissRequest = { showAddMenu = false },
                sheetState = sheetState,
                containerColor = colorScheme.surface,
                dragHandle = {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(48.dp)
                                .height(4.dp)
                                .background(
                                    colorScheme.scrim.copy(alpha = 0.2f),
                                    RoundedCornerShape(2.dp)
                                )
                        )
                    }
                },
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        MenuGridItem(
                            label = "Add sensor",
                            onClick = {
                                showAddMenu = false
                                if (LibreView.loggedIn || !LibreView.requireAccount) {
                                    onNavigateToScan()
                                } else {
                                    LibreView.status =
                                        "You need to log in to LibreView in order to use Libre sensors"
                                    onNavigateToLibreView()
                                }
                            },
                        ) {
                            Image(
                                painter = painterResource(Res.drawable.sensor),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                colorFilter = ColorFilter.tint(colorScheme.onSurface)
                            )
                        }
                        MenuGridItem(
//                            label = "Log reading",
                            label = "Placeholder",
                            onClick = { showAddMenu = false },
                        ) {
                            Text("✎", fontSize = 32.sp, color = colorScheme.onSurface)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        MenuGridItem(
//                            label = "Add note",
                            label = "Placeholder",
                            onClick = { showAddMenu = false },
                        ) {
                            Text("✉", fontSize = 32.sp, color = colorScheme.onSurface)
                        }
                        MenuGridItem(
//                            label = "Log meal",
                            label = "Placeholder",
                            onClick = { showAddMenu = false },
                        ) {
                            Text("⊕", fontSize = 32.sp, color = colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}
