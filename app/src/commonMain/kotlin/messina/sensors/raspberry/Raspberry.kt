package messina.sensors.raspberry

import com.juul.kable.characteristicOf
import messina.Glucose
import messina.sensors.EventLog
import messina.sensors.GlucoseReading
import messina.sensors.Sensor
import messina.sensors.SensorEvents
import messina.sensors.sensorBluetoothConnection
import kotlinx.io.Buffer
import kotlinx.io.readIntLe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val charReading = characteristicOf(
    service = Uuid.parse("12345678-1234-5678-1234-56789abcdef0"),
    characteristic = Uuid.parse("12345678-1234-5678-1234-56789abcdef1"),
)

@Serializable
sealed class RaspberryPacket {
    fun serialize(): String = Json.encodeToString(this)

    @Serializable
    @SerialName("Reading")
    data class Reading(val mgdl: Double) : RaspberryPacket()
}

suspend fun raspberryConnection(sensor: Sensor.Raspberry) = sensorBluetoothConnection(
    sensorId = sensor.id,
    address = sensor.macAddress,
    advertisedName = null,
) { peripheral ->
    peripheral.observe(charReading).collect { value ->
        val buffer = Buffer().also { it.write(value) }
        val rawMmol = buffer.readIntLe().toDouble()
        val calibratedMmol = rawMmol + sensor.calibrationOffset.toMmol()
        EventLog.push(sensor.id, RaspberryPacket.Reading(rawMmol).serialize())
        SensorEvents.glucoseReading.send(
            GlucoseReading(
                sensor.id,
                Clock.System.now(),
                Glucose.fromMmol(calibratedMmol)
            )
        )
    }
}
