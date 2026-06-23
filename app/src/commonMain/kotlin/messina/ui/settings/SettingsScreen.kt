package messina.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import messina.settings.Alarm
import messina.settings.GlucoseUnit
import messina.settings.Settings
import messina.Glucose
import messina.ui.BackButton
import messina.ui.ChoiceRow
import messina.ui.SwitchRow
import messina.app.generated.resources.Res
import messina.app.generated.resources.chevron_left
import org.jetbrains.compose.resources.painterResource

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToAlarm: (Alarm) -> Unit = {},
    onNavigateToAdvanced: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            BackButton("Overview", onClick = onBack)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(
                    rememberScrollState(),
                    flingBehavior = ScrollableDefaults.flingBehavior()
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Glucose unit", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                SingleChoiceSegmentedButtonRow {
                    GlucoseUnit.entries.forEachIndexed { index, unit ->
                        SegmentedButton(
                            selected = Settings.glucoseUnit == unit,
                            onClick = { Settings.glucoseUnit = unit },
                            shape = SegmentedButtonDefaults.itemShape(
                                index,
                                GlucoseUnit.entries.size
                            ),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.primary,
                                activeContentColor = MaterialTheme.colorScheme.onPrimary,
                                activeBorderColor = MaterialTheme.colorScheme.primary,
                                inactiveBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                            label = { Text(if (unit == GlucoseUnit.MgDl) "mg/dL" else "mmol/L") }
                        )
                    }
                }
            }
            HorizontalDivider()
            SwitchRow(
                label = "Keep screen on",
                checked = Settings.keepScreenOn,
                onCheckedChange = { Settings.keepScreenOn = it },
                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
            )
            HorizontalDivider()
            ChoiceRow(
                label = "Color theme",
                value = Settings.theme.name,
                onClick = { Settings.theme = Settings.theme.next() },
                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
            )

            // Alarms section header
            Text(
                text = "Alarms",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp, start = 16.dp, bottom = 6.dp)
            )

            // Alarm rows
            Column(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            ) {
                Settings.alarms.forEachIndexed { index, alarm ->
                    if (index > 0) HorizontalDivider()
                    val label = when (alarm) {
                        is Alarm.Connection -> "Connection lost"
                        is Alarm.Glucose -> "${alarm.threshold}"
                    }
                    SwitchRow(
                        label = label,
                        checked = alarm.enabled,
                        onCheckedChange = { alarm.enabled = it },
                        onClick = { onNavigateToAlarm(alarm) }
                    )
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            Settings.addGlucoseAlarm(Glucose.fromMmol(7.0))
                            onNavigateToAlarm(Settings.alarms.last())
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "+",
                            fontSize = 22.sp,
                            color = MaterialTheme.colorScheme.onTertiary,
                            lineHeight = 22.sp
                        )
                    }
                    Text("New alarm", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(onClick = onNavigateToAdvanced)
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Advanced", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                Image(
                    painter = painterResource(Res.drawable.chevron_left),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = 180f },
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }

        } // end scrollable Column
    }
}
