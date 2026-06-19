package messina.sensors

import messina.Glucose
import messina.logging.error
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.time.Instant

object SensorEvents {
    /// A new sensor has been added
    val sensorAdded = _Events<Sensor>()

    /// A sensor has been removed from the active sensors
    val sensorRemoved = _Events<Sensor>()

    /// A connection to a sensor has been established
    val sensorConnected = _Events<SensorId>()

    /// A sensor has been disconnected
    val sensorDisconnected = _Events<SensorId>()

    /// A raw glucose reading from a sensor
    val glucoseReading = _Events<GlucoseReading>()

    /// New history read from a sensor
    val history = _Events<GlucoseHistory>()

    class _Events<T> {
        private val _events = MutableSharedFlow<T>(extraBufferCapacity = 256)
        private val events: SharedFlow<T> = _events.asSharedFlow()

        fun send(event: T) {
            if (!_events.tryEmit(event)) {
                error { "Failed to send sensor event: $event" }
            }
        }

        suspend fun collect(collector: FlowCollector<T>) {
            events.collect(collector)
        }
    }

}

data class GlucoseHistory(
    val sensorId: SensorId,
    val from: Instant,
    val to: Instant,
)

data class GlucoseReading(
    val sensorId: SensorId,
    val time: Instant,
    val glucose: Glucose,
)

