package messina.ui.sensor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import messina.sensors.EventLog
import messina.sensors.SensorId
import messina.ui.BackButton
import messina.utils.Time
import kotlin.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@Composable
fun EventDetailsScreen(sensorId: SensorId, index: Int, onBack: () -> Unit) {
    val entry = remember(sensorId, index) { EventLog.getSensorEvents(sensorId).getOrNull(index) }

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
            BackButton("Events", onClick = onBack)
            if (entry != null) {
                Text(
                    eventName(entry.data),
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }

        if (entry == null) return

        val fields = remember(entry.data) {
            runCatching {
                Json.parseToJsonElement(entry.data).jsonObject
                    .entries
                    .filter { it.key != "type" }
                    .map { it.key to formatValue(it.key, it.value) }
            }.getOrDefault(emptyList())
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Received",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    Time.fromInstant(entry.receivedAt).toFullTimestamp(),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider()

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(fields) { (name, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            name,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            value,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun formatValue(key: String, element: JsonElement): String {
    fun String.isDuration(): Boolean {
        return (this.startsWith("PT") || this.startsWith("-PT") || this.startsWith("P"))
    }

    fun formatDuration(value: String): String {
        return runCatching { Duration.parseIsoString(value).toString() }.getOrDefault(value)
    }

    return when (element) {
        is JsonNull -> "null"
        is JsonPrimitive -> if (element.isString) {
            if (key.equals("time", true) && element.content.isDuration()) {
                // Duration is stored in a silly iso format, so we have to convert it to something readable
                formatDuration(element.content)
            } else {
                element.content
            }
        } else element.content

        else -> element.toString()
    }
}

