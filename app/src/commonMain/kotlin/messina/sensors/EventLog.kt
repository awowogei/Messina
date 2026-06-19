package messina.sensors

import messina.Database
import kotlin.time.Clock
import kotlin.time.Instant

object EventLog {
    data class Event(val receivedAt: Instant, val data: String)

    fun push(sensorId: SensorId, payload: String) {
        Database.execute(
            "INSERT INTO event_log (sensor_id, time, payload) VALUES (?, ?, ?)",
            arrayOf(
                sensorId.value,
                Clock.System.now().epochSeconds,
                payload,
            )
        )
    }

    fun getSensorEvents(sensorId: SensorId): List<Event> {
        return Database.execute(
            "SELECT time, payload FROM event_log WHERE sensor_id = ? ORDER BY time DESC, rowid DESC",
            arrayOf(sensorId.value)
        ) { rows ->
            val out = mutableListOf<Event>()
            for (row in rows) {
                out.add(
                    Event(
                        receivedAt = Instant.fromEpochSeconds(row.getLong(0)),
                        data = row.getText(1),
                    )
                )
            }
            out
        }
    }
}
