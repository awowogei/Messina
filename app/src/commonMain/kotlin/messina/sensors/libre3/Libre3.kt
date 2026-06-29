package messina.sensors.libre3

import messina.Glucose
import messina.sensors.Sensor
import messina.sensors.Sensors
import messina.sensors.Tag
import messina.share.LibreView
import messina.logging.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.readShortLe
import kotlinx.io.readUByte
import kotlinx.io.readUIntLe
import kotlinx.io.readUShortLe
import kotlinx.io.writeIntLe
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit
import kotlin.time.Instant


object Libre3 {
    suspend fun initialize(tag: Tag) {
        // TODO: Throws uninteligeble IoException
        val response = tag.transceive(nfcInitializationCommand())
        val initResponse = NfcInitializationResponse(response)

        val id = LibreView.accountId()

        val activationCommand = nfcActivationCommand(initResponse.state, id)
        val response2 = tag.transceive(activationCommand)

        if (response2.size == 19) {
            // TODO: Maybe it's possible to have constructors that can fail so the above
            //       comparison can be removed for clarity
            val activationResponse = NfcActivationResponse(response2)
            Sensors.add(
                Sensor.Libre3(
                    initResponse.serialNumber,
                    activationResponse.address,
                    activationResponse.blePin,
                    activationTime = activationResponse.activationTime,
                )
            )
        } else {
            error("Sensor ${initResponse.state.name.lowercase().replace('_', ' ')}")
        }
    }

    // Control commands that can be sent to the sensor
    object Control {
        // Get historic readings in 5-minute intervals
        fun longHistory(from: Duration): ByteArray {
            val buffer = Buffer()
            buffer.writeByte(0x01)
            buffer.writeByte(0x00)
            buffer.writeByte(0x01)
            buffer.writeIntLe(from.toInt(DurationUnit.MINUTES))
            return buffer.readByteArray()
        }

        // Get historic readings in 1-minute intervals
        fun shortHistory(from: Duration): ByteArray {
            val buffer = Buffer()
            buffer.writeByte(0x01)
            buffer.writeByte(0x01)
            buffer.writeByte(0x01)
            buffer.writeIntLe(from.toInt(DurationUnit.MINUTES))
            return buffer.readByteArray()
        }

        // Get all sensor events that happened since the given event index
        fun eventLog(lastEvent: Int): ByteArray {
            val buffer = Buffer()
            buffer.writeByte(0x00)
            buffer.writeIntLe(lastEvent)
            buffer.writeByte(0x00)
            buffer.writeByte(0x00)
            return buffer.readByteArray()
        }
    }

    enum class State {
        MANUFACTURING,
        STORAGE,
        INSERTION_DETECTED,
        INSERTION_FAILED,
        PAIRED,
        EXPIRED,
        TERMINATED_NORMAL,
        ERROR,
        ERROR_TERMINATED,
    }

    enum class PacketType(val header: ByteArray) {
        CONTROL_SEND(byteArrayOf(0x00, 0x00, 0x00)),
        CONTROL_RECEIVE(byteArrayOf(0x00, 0x00, 0x0F)),
        STATUS(byteArrayOf(0x00, 0x00, 0xF0.toByte())),
        READING(byteArrayOf(0x00, 0x0F, 0x00)),
        LONG_HISTORY(byteArrayOf(0x00, 0xF0.toByte(), 0x00)),
        SHORT_HISTORY(byteArrayOf(0x0F, 0x00, 0x00)),
        EVENT_LOG(byteArrayOf(0xF0.toByte(), 0x00, 0x00)),
        FACTORY_DATA(byteArrayOf(0x44, 0x00, 0x00))
    }

    @Serializable
    sealed class Packet {
        fun serialize(): String = Json.encodeToString(this)

        @Serializable
        @SerialName("Reading")
        data class Reading(
            // Time the reading was taken relative to the start of the sensor
            val time: Duration,
            // A clamped reading of the blood glucose, clamped to some range it's certified for probably
            private val clampedReadingMgDl: Int,
            // How fast the reading is changing from minute to minute
            private val rateOfChange: Int,
            // Don't know, signal attenuation?
            val esaDuration: Int,
            // Don't know
            private val projectedGlucose: Int,
            // Time of the historic reading relative to the start of the sensor
            val historicalLifeCount: Int,
            val clampedHistoricMgDl: Int,
            // Least significant 3 bits are trend, arrow 7 directions
            private val trend: Int,
            // Unclamped readings
            private val currentMgDl: Int,
            private val historicMgDl: Int,
            // Temperature measurement
            val temperature: Int,
            // Don't know
            val fastdata: ByteArray,
        ) : Packet() {
            fun isValid(): Boolean {
                return this.clampedReadingMgDl != 32768
            }

            fun glucose(): Glucose {
                return Glucose.fromMgDl(clampedReadingMgDl.toDouble())
            }
        }

        @Serializable
        @SerialName("LongHistory")
        data class LongHistory(
            val start: Duration,
            val readings: List<Reading>,
        ) : Packet() {
            @Serializable
            data class Reading(val time: Duration, val glucose: Glucose)

            val end: Duration get() = start + ((readings.size - 1) * 5).minutes
        }

        @Serializable
        @SerialName("ShortHistory")
        data class ShortHistory(
            val time: Duration,
            val fastData: ByteArray,
            val glucose: Glucose,
            val glucoseAverage: Glucose
        ) : Packet() {
            fun isValid(): Boolean {
                return this.glucose.toMgDl() != 32768.0
            }
        }

        @Serializable
        @SerialName("Control")
        data class Control(
            val bytes: ByteArray,
        ) : Packet()

        @Serializable
        @SerialName("Status")
        data class Status(
            private val time: Duration,
            private val errorData: Int, //?
            private val eventData: Int, //?
            private val index: Int,
            private val sensorState: Int,
            private val currentLifeCount: Int,
            private val stackDisconnectReason: Int,
            private val appDisconnectReason: Int,
        ) : Packet() {
            fun totalEvents(): Int = this.index + 1
            fun state(): State = State.entries[sensorState]
        }

        companion object {
            fun deserialize(type: PacketType, data: ByteArray): Packet {
                val buffer = Buffer()
                buffer.write(data)
                return when (type) {
                    PacketType.READING -> Reading(
                        time = buffer.readUShortLe().toInt().minutes,
                        clampedReadingMgDl = buffer.readUShortLe().toInt(),
                        rateOfChange = buffer.readShortLe().toInt(),
                        esaDuration = buffer.readUShortLe().toInt(),
                        projectedGlucose = buffer.readUShortLe().toInt(),
                        historicalLifeCount = buffer.readUShortLe().toInt(),
                        clampedHistoricMgDl = buffer.readUShortLe().toInt(),
                        trend = buffer.readUByte().toInt(),
                        currentMgDl = buffer.readUShortLe().toInt(),
                        historicMgDl = buffer.readUShortLe().toInt(),
                        temperature = buffer.readUShortLe().toInt(),
                        fastdata = buffer.readByteArray(8),
                    )

                    PacketType.STATUS -> Status(
                        time = buffer.readShortLe().toInt().minutes,
                        errorData = buffer.readShortLe().toInt(),
                        eventData = buffer.readShortLe().toInt() + 4000,
                        index = buffer.readUByte().toInt(),
                        sensorState = buffer.readUByte().toInt(),
                        currentLifeCount = buffer.readShortLe().toInt(),
                        stackDisconnectReason = buffer.readUByte().toInt(),
                        appDisconnectReason = buffer.readUByte().toInt(),
                    )

                    PacketType.LONG_HISTORY -> {
                        val start = buffer.readUShortLe().toInt().minutes
                        val count = (data.size - 2) / 2
                        val readings = ArrayList<LongHistory.Reading>(count)
                        for (i in 0 until count) {
                            val v = buffer.readUShortLe().toDouble()
                            if (v in 39.0..501.0) {
                                readings.add(
                                    LongHistory.Reading(
                                        start + (i * 5).minutes,
                                        Glucose.fromMgDl(v)
                                    )
                                )
                            }
                        }
                        LongHistory(start, readings)
                    }

                    PacketType.SHORT_HISTORY -> {
                        val time = buffer.readUShortLe().toInt().minutes
                        val fastData = buffer.readByteArray(8)
                        val reading = Glucose.fromMgDl(buffer.readUShortLe().toDouble())
                        val average = Glucose.fromMgDl(buffer.readUShortLe().toDouble())
                        ShortHistory(time, fastData, reading, average)
                    }

                    PacketType.CONTROL_RECEIVE -> Control(
                        bytes = buffer.readByteArray()
                    )

                    else -> throw IllegalArgumentException()
                }
            }
        }
    }
}

private fun nfcInitializationCommand(): ByteArray {
    return byteArrayOf(0x02.toByte(), 0xA1.toByte(), 0x7A.toByte())
}

private fun nfcActivationCommand(sensorState: Libre3.State, accountId: UInt): ByteArray {
    val header = byteArrayOf(
        0x02.toByte(),
        // Activate ELSE switch receiver
        if (sensorState == Libre3.State.INSERTION_DETECTED) 0xA8.toByte() else 0xA0.toByte(),
        0x7A.toByte(),
    )

    val time = Clock.System.now().epochSeconds - 1

    // Pack time-1 as u32 little-endian, then accountId as u32 little-endian
    val packet = ByteArray(8)
    for (i in 0 until 4) {
        packet[i] = (time shr (i * 8)).toByte()
    }
    for (i in 0 until 4) {
        packet[4 + i] = (accountId shr (i * 8)).toByte()
    }

    val crc = crc16CcittFalse(packet)

    return header + packet + crc
}

private fun crc16CcittFalse(data: ByteArray): ByteArray {
    var crc = 0xFFFF
    for (b in data) {
        // Reflect the input byte before processing
        var v = b.toInt() and 0xFF
        var reflected = 0
        repeat(8) {
            reflected = (reflected shl 1) or (v and 1)
            v = v shr 1
        }

        crc = crc xor (reflected shl 8)
        repeat(8) {
            crc = if (crc and 0x8000 != 0) {
                ((crc shl 1) xor 0x1021) and 0xFFFF
            } else {
                (crc shl 1) and 0xFFFF
            }
        }
    }
    return byteArrayOf(crc.toByte(), (crc shr 8).toByte())
}

private class NfcInitializationResponse(response: ByteArray) {
    // TODO: Probably just make these int
    val securityVersion: UShort
    val localization: UShort
    val generation: UShort
    val wearDuration: UShort
    val firmwareVersion: UInt
    val productType: Byte
    val warmup: Byte
    val state: Libre3.State
    val serialNumber: ByteArray
    val crc16: ByteArray

    init {
        // Got this originally from Juggluco, but it doesn't produce the correct values?
        // It leaves a byte at the end, and most of the fields seem wrong.
        //val iter = response.drop(1).dropWhile { it == 0xa5.toByte() }.iterator()

        val buffer = Buffer()
        buffer.write(response)
        buffer.skip(3) // Drop the initial 00 a5 00
        this.securityVersion = buffer.readUShortLe()
        this.localization = buffer.readUShortLe()
        this.generation = buffer.readUShortLe()
        this.wearDuration = buffer.readUShortLe()
        this.firmwareVersion = buffer.readUIntLe()
        this.productType = buffer.readByte()
        this.warmup = buffer.readByte()
        this.state = Libre3.State.entries[buffer.readUByte().toInt()]
        this.serialNumber = buffer.readByteArray(9)
        this.crc16 = buffer.readByteArray(2)

        info { "$this" }
    }

    override fun toString(): String {
        return """
        Sensor info:
          securityVersion : $securityVersion
          localization    : $localization
          generation      : $generation
          wearDuration    : $wearDuration
          firmwareVersion : $firmwareVersion
          productType     : $productType
          warmup          : $warmup
          state           : $state
          serialNumber    : ${serialNumber.toHexString()}
          crc16           : ${crc16.joinToString(" ") { it.toHexString() }}
    """.trimIndent()
    }
}

private class NfcActivationResponse(response: ByteArray) {
    val address: ByteArray
    val blePin: ByteArray
    val activationTime: Instant
    val crc16: ByteArray

    init {
        val buffer = Buffer().also { it.write(response) }
        buffer.skip(3)
        this.address = buffer.readByteArray(6).reversedArray()
        this.blePin = buffer.readByteArray(4)
        this.activationTime = Instant.fromEpochSeconds(buffer.readUIntLe().toLong())
        this.crc16 = buffer.readByteArray(2)
    }

    override fun toString(): String {
        return """
    Bluetooth information:
      address         : ${address.toHexString()}
      bluetooth pin   : $blePin
      activation time : $activationTime
      crc16           : ${crc16.joinToString(" ") { it.toHexString() }}
""".trimIndent()
    }
}

