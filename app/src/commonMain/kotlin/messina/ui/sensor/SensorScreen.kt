package messina.ui.sensor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import messina.settings.GlucoseUnit
import messina.settings.Settings
import messina.Glucose
import messina.sensors.Sensor
import messina.sensors.SensorId
import messina.sensors.Sensors
import messina.ui.AppSlider
import messina.ui.BackButton
import messina.ui.SwitchRow
import messina.utils.Time
import messina.utils.format
import messina.app.generated.resources.Res
import messina.app.generated.resources.chevron_left
import org.jetbrains.compose.resources.painterResource
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration

@Composable
fun SensorScreen(
    onBack: () -> Unit,
    onNavigateToSensorDetail: (SensorId) -> Unit = {},
    onNavigateToEventLog: (SensorId) -> Unit = {},
) {
    val connected = Sensors.active
    val old = Sensors.inactive
    val singleConnected = connected.singleOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackButton("Overview", onClick = onBack)
            if (singleConnected != null) {
                TextButton(onClick = {
                    onBack()
                    Sensors.remove(singleConnected.id)
                }) {
                    Text("Disconnect", fontSize = 16.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (connected.isNotEmpty()) {
            if (singleConnected != null) {
                SensorSettingsBody(
                    sensor = singleConnected,
                    onNavigateToEventLog = { onNavigateToEventLog(singleConnected.id) },
                )
            } else {
                SectionHeader("Connected")
                SensorList(connected, onNavigateToSensorDetail)
            }
        }

        if (old.isNotEmpty()) {
            SectionHeader("Old sensors")
            SensorList(old, onNavigateToSensorDetail)
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SensorList(sensors: List<Sensor>, onClick: (SensorId) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        sensors.forEachIndexed { index, sensor ->
            if (index > 0) HorizontalDivider()
            SensorListRow(
                sensor = sensor,
                onClick = { onClick(sensor.id) }
            )
        }
    }
}

@Composable
fun SensorDetailsScreen(
    sensorId: SensorId,
    onBack: () -> Unit,
    onNavigateToEventLog: (SensorId) -> Unit = {},
) {
    val sensor = Sensors.get(sensorId)

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackButton("Sensors", onClick = onBack)
            if (sensor != null) {
                TextButton(onClick = {
                    onBack()
                    Sensors.remove(sensorId)
                }) {
                    Text("Disconnect", fontSize = 16.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (sensor != null) {
            SensorSettingsBody(
                sensor = sensor,
                onNavigateToEventLog = { onNavigateToEventLog(sensor.id) },
            )
        }
    }
}

@Composable
private fun SensorListRow(sensor: Sensor, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(sensor.name(), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
        Image(
            painter = painterResource(Res.drawable.chevron_left),
            contentDescription = null,
            modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = 180f },
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
        )
    }
}

@Composable
private fun SensorSettingsBody(sensor: Sensor, onNavigateToEventLog: () -> Unit) {
    val glucoseUnit = Settings.glucoseUnit
    val sliderRange = when (glucoseUnit) {
        GlucoseUnit.Mmol -> -5.0f..5.0f
        GlucoseUnit.MgDl -> -90.0f..90.0f
    }
    val steps = when (glucoseUnit) {
        GlucoseUnit.Mmol -> 99   // 101 values: -5.0..+5.0 in 0.1 steps
        GlucoseUnit.MgDl -> 35   // 37 values:  -90..+90  in 5.0 steps
    }
    var sliderValue by remember {
        mutableStateOf(sensor.calibrationOffset.value.toFloat())
    }
    val displayGlucose = when (glucoseUnit) {
        GlucoseUnit.Mmol -> Glucose.fromMmol(sliderValue.toDouble())
        GlucoseUnit.MgDl -> Glucose.fromMgDl(sliderValue.toDouble())
    }

    val expiresIn = sensor.expiresIn()

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Expires in",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            val expiryText = if (expiresIn.isInfinite()) {
                "Never"
            } else {
                val expiresAt = Time.fromInstant(Clock.System.now() + expiresIn)
                "${formatExpiry(expiresIn)} (${expiresAt.toDayMonth()}, ${expiresAt.toHHMM()})"
            }
            Text(
                expiryText,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        HorizontalDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = if (sensor.active) 12.dp else 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Calibration offset",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                formatOffset(displayGlucose),
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (sensor.active) {
            AppSlider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    sensor.calibrationOffset = when (glucoseUnit) {
                        GlucoseUnit.Mmol -> Glucose.fromMmol(sliderValue.toDouble())
                        GlucoseUnit.MgDl -> Glucose.fromMgDl(sliderValue.toDouble())
                    }
                },
                valueRange = sliderRange,
                steps = steps,
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            )
        }

        HorizontalDivider()
        SwitchRow(
            label = "Smoothing",
            checked = sensor.smoothingEnabled,
            onCheckedChange = { sensor.smoothingEnabled = it },
        )

        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNavigateToEventLog)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Event log", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Image(
                painter = painterResource(Res.drawable.chevron_left),
                contentDescription = null,
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = 180f },
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
            )
        }
    }
}

private fun formatExpiry(remaining: Duration): String {
    if (remaining <= Duration.ZERO) return "Expired"
    val days = remaining.inWholeDays
    val hours = remaining.inWholeHours % 24
    val minutes = remaining.inWholeMinutes % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

private fun formatOffset(glucose: Glucose): String {
    val v = glucose.value
    return when (Settings.glucoseUnit) {
        GlucoseUnit.Mmol -> if (v >= 0) "+%.1f".format(v) else "%.1f".format(v)
        GlucoseUnit.MgDl -> v.roundToInt().let { if (it >= 0) "+$it" else "$it" }
    }
}
