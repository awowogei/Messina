package messina.ui.sensor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import messina.sensors.EventLog
import messina.sensors.SensorId
import messina.ui.BackButton
import messina.utils.Time
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@Composable
fun EventLogScreen(
    sensorId: SensorId,
    onBack: () -> Unit,
    onNavigateToEvent: (Int) -> Unit,
) {
    val entries = remember(sensorId) { EventLog.getSensorEvents(sensorId) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackButton("Sensor", onClick = onBack)
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(entries) { index, entry ->
                val name = remember(entry.data) { eventName(entry.data) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToEvent(index) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        Time.fromInstant(entry.receivedAt).toFullTimestamp(),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

internal fun eventName(payload: String): String =
    runCatching {
        val type = Json.parseToJsonElement(payload).jsonObject["type"] as? JsonPrimitive
        if (type?.isString == true) type.content else null
    }.getOrNull() ?: "Unknown"
