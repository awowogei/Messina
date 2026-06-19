package messina.ui

import androidx.compose.foundation.Image
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import messina.app.generated.resources.Res
import messina.app.generated.resources.chevron_left
import messina.app.generated.resources.chevron_up_down
import org.jetbrains.compose.resources.painterResource

@Composable
fun BackButton(title: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(start = 0.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Image(
            painter = painterResource(Res.drawable.chevron_left),
            contentDescription = "Back",
            modifier = Modifier.size(18.dp),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
        )
        Text(title, fontSize = 16.sp)
    }
}

@Composable
fun ChoiceRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val contentColor =
        if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 16.sp,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(value, fontSize = 16.sp, color = contentColor)
            Image(
                painter = painterResource(Res.drawable.chevron_up_down),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(contentColor)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WheelPicker(
    items: List<String>,
    initialIndex: Int,
    onSelectionChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val itemHeight = 44.dp
    val visibleCount = 5
    val halfCount = visibleCount / 2
    val paddedItems = remember(items) { List(halfCount) { "" } + items + List(halfCount) { "" } }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val itemHeightPx = with(LocalDensity.current) { itemHeight.toPx() }

    val centeredIndex by remember {
        derivedStateOf {
            val adj = if (listState.firstVisibleItemScrollOffset > itemHeightPx / 2f) 1 else 0
            (listState.firstVisibleItemIndex + adj).coerceIn(0, items.lastIndex)
        }
    }

    LaunchedEffect(centeredIndex) {
        onSelectionChanged(centeredIndex)
    }

    Box(modifier = modifier.height(itemHeight * visibleCount)) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(itemHeight)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(paddedItems) { paddedIndex, item ->
                val isCentered = (paddedIndex - halfCount) == centeredIndex
                Box(
                    modifier = Modifier.fillMaxWidth().height(itemHeight),
                    contentAlignment = Alignment.Center
                ) {
                    if (item.isNotEmpty()) {
                        Text(
                            text = item,
                            fontSize = if (isCentered) 22.sp else 17.sp,
                            fontWeight = if (isCentered) FontWeight.Medium else FontWeight.Normal,
                            color = if (isCentered) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(
                                alpha = 0.25f
                            )
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .height(itemHeight * halfCount)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface,
                            Color.Transparent
                        )
                    )
                )
        )
        Box(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .height(itemHeight * halfCount)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
        )
    }
}

@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val trackWidth = 51.dp
    val trackHeight = 31.dp
    val thumbSize = 27.dp
    val thumbPadding = 2.dp
    val trackColor by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        animationSpec = tween(200),
        label = "trackColor"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbSize - thumbPadding else thumbPadding,
        animationSpec = tween(200),
        label = "thumbOffset"
    )

    Box(
        modifier = modifier
            .layout { measurable, constraints ->
                val scale = 1.4f
                val placeable = measurable.measure(constraints)
                val w = (placeable.width * scale).toInt()
                val h = (placeable.height * scale).toInt()
                layout(w, h) {
                    placeable.placeWithLayer(
                        x = (w - placeable.width) / 2,
                        y = (h - placeable.height) / 2
                    ) {
                        scaleX = scale
                        scaleY = scale
                    }
                }
            }
            .size(width = trackWidth, height = trackHeight)
            .clip(RoundedCornerShape(50))
            .background(trackColor)
            .then(
                if (onCheckedChange != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onCheckedChange(!checked) } else Modifier
            )
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset, y = thumbPadding)
                .size(thumbSize)
                .shadow(
                    2.dp,
                    CircleShape,
                    spotColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.2f)
                )
                .background(Color.White, CircleShape)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChangeFinished: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        thumb = {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .shadow(3.dp, CircleShape)
                    .background(Color.White, CircleShape)
            )
        },
        track = { sliderState ->
            val fraction = (sliderState.value - sliderState.valueRange.start) /
                    (sliderState.valueRange.endInclusive - sliderState.valueRange.start)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.outlineVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        },
        modifier = modifier
    )
}

@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        decorationBox = { innerTextField ->
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    text = placeholder,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            innerTextField()
        }
    )
}

// onClick: tapping the label navigates (with full-row ripple), switch toggles independently.
// No onClick: label is non-interactive, only the switch itself is tappable.
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.indication(
                    interactionSource,
                    LocalIndication.current
                ) else Modifier
            )
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onClick != null) Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) { onClick() } else Modifier)
                .padding(vertical = 18.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
