package messina.backup

import messina.Database
import messina.Glucose
import messina.sensors.GlucoseReading
import messina.sensors.SensorEvents
import messina.sensors.Sensors
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

object Backup {
    init {
        registerSensors()
        GlobalScope.launch {
            val interval = 60.minutes
            var lastSync = getLastSync()
            while (true) {
                val wait = lastSync + interval - Clock.System.now()
                if (wait > 0.seconds) delay(wait)
                upload(lastSync)
                lastSync = Clock.System.now()
                setLastSync(lastSync)
            }
        }
    }

    private suspend fun upload(since: Instant) {
        for (sensor in Sensors.active) {
            val readings = Database.execute(
                "SELECT time, glucose FROM glucose_history WHERE sensor_id = ? AND time > ? ORDER BY time ASC",
                arrayOf(sensor.id.value, since.epochSeconds),
            ) { rows ->
                buildList {
                    for (row in rows) add(
                        GlucoseReading(
                            sensor.id,
                            Instant.fromEpochSeconds(row.getLong(0)),
                            Glucose.fromMgDl(row.getDouble(1)),
                        )
                    )
                }
            }

            LibreView.upload(sensor, readings)
        }
    }

    private fun registerSensors() {
        GlobalScope.launch {
            SensorEvents.sensorAdded.collect { sensor ->
                LibreView.register(sensor)
            }
        }
    }

    private fun getLastSync(): Instant {
        var lastSync =
            Database.execute("SELECT value FROM storage WHERE key = 'last_sync'") { rows ->
                rows.next()?.getLong(0)?.let { Instant.fromEpochSeconds(it) }
            }
        if (lastSync == null) {
            lastSync = Clock.System.now()
            setLastSync(lastSync)
        }

        return lastSync
    }

    private fun setLastSync(time: Instant) {
        Database.execute(
            "INSERT OR REPLACE INTO storage (key, value) VALUES ('last_sync', ?)",
            arrayOf(time.epochSeconds)
        )
    }
}
