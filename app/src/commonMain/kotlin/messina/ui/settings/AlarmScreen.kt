package messina.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import messina.settings.Alarm
import messina.settings.GlucoseUnit
import messina.settings.Settings
import messina.Glucose
import messina.ui.AppSlider
import messina.ui.BackButton
import messina.ui.ChoiceRow
import messina.ui.SwitchRow

private const val MmolMin = 2.9f
private const val MmolMax = 16.0f
private const val MmolSteps = ((MmolMax - MmolMin) * 10).toInt() - 1 // 0.1 mmol increments

private const val MgDlMin = 50f
private const val MgDlMax = 290f
private const val MgDlSteps = ((MgDlMax - MgDlMin) / 5).toInt() - 1 // 5 mg/dL increments

@Composable
fun AlarmScreen(alarm: Alarm, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
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
            BackButton("Settings", onClick = onBack)
            if (alarm is Alarm.Glucose) {
                TextButton(onClick = {
                    onBack()
                    Settings.removeAlarm(alarm)
                }) {
                    Text("Delete", fontSize = 16.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (alarm is Alarm.Glucose) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                ChoiceRow(
                    label = "Direction",
                    value = alarm.direction.name,
                    onClick = { alarm.direction = alarm.direction.next() }
                )
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val valueRange = when (Settings.glucoseUnit) {
                        GlucoseUnit.Mmol -> MmolMin..MmolMax
                        GlucoseUnit.MgDl -> MgDlMin..MgDlMax
                    }

                    var sliderValue by remember(alarm, Settings.glucoseUnit) {
                        val initial = alarm.threshold.value.toFloat()
                        mutableStateOf(initial.coerceIn(valueRange))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Glucose",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val displayGlucose = when (Settings.glucoseUnit) {
                            GlucoseUnit.Mmol -> Glucose.fromMmol(sliderValue.toDouble())
                            GlucoseUnit.MgDl -> Glucose.fromMgDl(sliderValue.toDouble())
                        }
                        Text(
                            "$displayGlucose",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    AppSlider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = {
                            alarm.threshold = when (Settings.glucoseUnit) {
                                GlucoseUnit.Mmol -> Glucose.fromMmol(sliderValue.toDouble())
                                GlucoseUnit.MgDl -> Glucose.fromMgDl(sliderValue.toDouble())
                            }
                        },
                        valueRange = valueRange,
                        steps = when (Settings.glucoseUnit) {
                            GlucoseUnit.Mmol -> MmolSteps
                            GlucoseUnit.MgDl -> MgDlSteps
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            SwitchRow(
                label = "Notification",
                checked = alarm.notification,
                onCheckedChange = { alarm.notification = it }
            )
            HorizontalDivider()
            SwitchRow(
                label = "Vibration",
                checked = alarm.vibration,
                onCheckedChange = { alarm.vibration = it }
            )
            HorizontalDivider()
            SwitchRow(
                label = "Sound",
                checked = alarm.sound,
                onCheckedChange = { alarm.sound = it }
            )
            HorizontalDivider()
            ChoiceRow(
                label = "Volume",
                value = alarm.volume.name,
                enabled = alarm.sound,
                onClick = { alarm.volume = alarm.volume.next() }
            )
            HorizontalDivider()
            SwitchRow(
                label = "Ignore Mute",
                checked = alarm.ignoreMute,
                onCheckedChange = { alarm.ignoreMute = it }
            )
        }
    }
}
