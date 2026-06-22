package messina.sensors.libre3

import com.juul.kable.Characteristic
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import messina.Database
import messina.sensors.EventLog
import messina.sensors.GlucoseHistory
import messina.sensors.GlucoseReading
import messina.sensors.Sensor
import messina.sensors.SensorEvents
import messina.sensors.Sensors
import messina.sensors.sensorBluetoothConnection
import messina.sensors.subscribe
import messina.logging.error
import messina.logging.info
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

// @formatter:off
private object Services {
    object security {
        val SERVICE        = Uuid.parse("0898203a-ef89-11e9-81b4-2a2ae2dbcce4")
        val COMMANDS       = Uuid.parse("08982198-ef89-11e9-81b4-2a2ae2dbcce4")
        val CHALLENGE = Uuid.parse("089822ce-ef89-11e9-81b4-2a2ae2dbcce4")
        val CRYPTO    = Uuid.parse("089823fa-ef89-11e9-81b4-2a2ae2dbcce4")
    }

    object data {
        val SERVICE        = Uuid.parse("089810cc-ef89-11e9-81b4-2a2ae2dbcce4")
        val SENSOR_CONTROL = Uuid.parse("08981338-ef89-11e9-81b4-2a2ae2dbcce4")
        val SENSOR_STATUS  = Uuid.parse("08981482-ef89-11e9-81b4-2a2ae2dbcce4")
        val EVENT_LOG      = Uuid.parse("08981bee-ef89-11e9-81b4-2a2ae2dbcce4")
        val READINGS   = Uuid.parse("0898177a-ef89-11e9-81b4-2a2ae2dbcce4")
        val LONG_HISTORY  = Uuid.parse("0898195a-ef89-11e9-81b4-2a2ae2dbcce4")
        val SHORT_HISTORY  = Uuid.parse("08981ab8-ef89-11e9-81b4-2a2ae2dbcce4")
        val FACTORY_DATA   = Uuid.parse("08981d24-ef89-11e9-81b4-2a2ae2dbcce4")
        // TODO: What is this for?
        val BLE_LOGIN      = Uuid.parse("08981100-ef89-11e9-81b4-2a2ae2dbcce4")
    }
}

private val charSensorControl = characteristicOf(Services.data.SERVICE, Services.data.SENSOR_CONTROL)
private val charSensorStatus  = characteristicOf(Services.data.SERVICE, Services.data.SENSOR_STATUS)
private val charEventLog      = characteristicOf(Services.data.SERVICE, Services.data.EVENT_LOG)
private val charGlucose       = characteristicOf(Services.data.SERVICE, Services.data.READINGS)
private val charHistoric      = characteristicOf(Services.data.SERVICE, Services.data.LONG_HISTORY)
private val charClinical      = characteristicOf(Services.data.SERVICE, Services.data.SHORT_HISTORY)
private val charFactory       = characteristicOf(Services.data.SERVICE, Services.data.FACTORY_DATA)
private val charCommands      = characteristicOf(Services.security.SERVICE, Services.security.COMMANDS)
private val charChallenge     = characteristicOf(Services.security.SERVICE, Services.security.CHALLENGE)
private val charCert          = characteristicOf(Services.security.SERVICE, Services.security.CRYPTO)
// @formatter:on

private val characteristicNames: Map<Uuid, String> = buildMap {
    put(Services.security.COMMANDS, "SecurityCommands")
    put(Services.security.CHALLENGE, "Challenge")
    put(Services.security.CRYPTO, "Crypto")
    put(Services.data.SENSOR_CONTROL, "SensorControl")
    put(Services.data.SENSOR_STATUS, "SensorStatus")
    put(Services.data.EVENT_LOG, "EventLog")
    put(Services.data.READINGS, "Readings")
    put(Services.data.LONG_HISTORY, "LongHistory")
    put(Services.data.SHORT_HISTORY, "ShortHistory")
    put(Services.data.FACTORY_DATA, "FactoryData")
    put(Services.data.BLE_LOGIN, "BleLogin")
}

private fun Characteristic.name() =
    characteristicNames[characteristicUuid] ?: "Unnamed($characteristicUuid)"

suspend fun libre3Connection(sensor: Sensor.Libre3) = sensorBluetoothConnection(
    sensorId = sensor.id,
    address = sensor.macAddress,
    advertisedName = "ABBOTT" + sensor.serialNumber.decodeToString(),
    onDisconnect = { status ->
        // The sensor terminating the connection usually means the sharedKey is incorrect and needs
        // to be recomputed
        if (status == State.Disconnected.Status.PeripheralDisconnected) {
            sensor.sharedKey = null
        }
    },
) { peripheral ->
    Connection(this, peripheral, sensor).run()
}

private class Connection(
    private val scope: CoroutineScope,
    private val peripheral: Peripheral,
    private val sensor: Sensor.Libre3,
) {
    private val security = Security(sensor)

    // The commands characteristic carries both 1-byte signals and 2-byte announcements of
    // chunked transfers on the crypto and challenge characteristics
    private val commands = Channel<ByteArray>(Channel.UNLIMITED)
    private val cryptoData = Channel<ByteArray>(Channel.UNLIMITED)
    private val challengeData = Channel<ByteArray>(Channel.UNLIMITED)

    // Control commands are acked with SensorControl packets
    private val controlAck = Channel<Libre3.Packet.Control>(Channel.UNLIMITED)

    private var sensorStatus: Libre3.Packet.Status? = null

    suspend fun run() {
        with(scope) {
            subscribe(peripheral, charCommands) { commands.send(it) }
            subscribe(peripheral, charCert) { cryptoData.send(it) }
            subscribe(peripheral, charChallenge) { challengeData.send(it) }
        }

        if (security.ecdhCompleted()) {
            info { "skipping ecdh" }
        } else {
            info { "Init ecdh" }
            ecdh()
        }
        authenticate()

        with(scope) {
            subscribe(peripheral, charSensorControl, ::onSensorControl)
            subscribe(peripheral, charEventLog) { info { "event log: ${it.toHexString()}" } }
            subscribe(peripheral, charHistoric, ::onHistoricData)
            subscribe(peripheral, charClinical, ::onClinicalData)
            subscribe(peripheral, charFactory) { info { "factory data: ${it.toHexString()}" } }
            subscribe(peripheral, charGlucose, ::onGlucoseData)
            subscribe(peripheral, charSensorStatus, ::onSensorStatus)
        }
    }

    private suspend fun ecdh() {
        writeCommand(Security.EcdhCommand.INIT_ECDH)

        info { "Notifying sensor of incoming app cert" }
        writeCommand(Security.EcdhCommand.PREPARE_FOR_APP_CERT)

        info { "Sending app cert to sensor" }
        writePacket(charCert, Crypto.APP_CERTIFICATES[1])
        writeCommand(Security.EcdhCommand.APP_CERT_SENT)

        // "certificate can be requested"
        awaitSignal(4)
        info { "Requesting sensor certificate" }
        writeCommand(Security.EcdhCommand.SEND_CERTIFICATE)
        val certificate = receivePacket(cryptoData, expectedSize = 140)
        security.sensorStaticKey = certificate.copyOfRange(11, 11 + 65)
        info { "Sensor certificate received: ${security.sensorStaticKey.toHexString()}" }
        writeCommand(Security.EcdhCommand.SENSOR_CERT_RECEIVED)

        info { "Sending app ephemeral key to sensor" }
        writePacket(charCert, security.ephemeral.publicKey)
        writeCommand(Security.EcdhCommand.APP_KEY_SENT)

        val sensorKey = receivePacket(cryptoData, expectedSize = 65)
        info { "Sensor ephemeral key received: ${sensorKey.toHexString()}" }
        security.computeSharedKey(sensorKey)
        info { "shared key: ${security.sharedKey!!.toHexString()}" }

        // Save shared key to database
        sensor.sharedKey = security.sharedKey
    }

    private suspend fun authenticate() {
        writeCommand(Security.EcdhCommand.INIT_CHALLENGE)

        val challenge = receivePacket(challengeData, expectedSize = 23)
        info { "Sending encryption challenge" }
        val response = security.makeChallenge(challenge)
        info { "Challenge encrypted: ${response.toHexString()}" }
        writePacket(charChallenge, response)
        info { "Finished writing challenge to sensor" }
        writeCommand(Security.EcdhCommand.CHALLENGE_SENT)

        val sensorChallenge = receivePacket(challengeData, expectedSize = 67)
        info { "Received sensor challenge" }
        security.solveChallenge(sensorChallenge)
    }

    private suspend fun writeCommand(command: Security.EcdhCommand) {
        peripheral.write(
            charCommands,
            byteArrayOf(command.id.toByte()),
            WriteType.WithResponse
        )
    }

    private suspend fun writeControl(command: ByteArray) {
        val data = security.encrypt(command, Libre3.PacketType.CONTROL_SEND)
        info { "${charSensorControl.name()}: wrote ${data.size} bytes" }
        peripheral.write(charSensorControl, data, WriteType.WithResponse)
        // Suspend here until the sensor acks on completion.
        controlAck.receive()
    }

    private suspend fun writePacket(characteristic: Characteristic, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            // The sensor can only receive 20 bytes at a time, a little-endian offset in the first two
            // bytes followed by up to 18 bytes of payload
            val len = minOf(data.size - offset, 18)
            val packet = ByteArray(20)
            packet[0] = (offset and 0xFF).toByte()
            packet[1] = (offset shr 8).toByte()
            data.copyInto(
                packet,
                destinationOffset = 2,
                startIndex = offset,
                endIndex = offset + len
            )
            offset += len

            peripheral.write(characteristic, packet, WriteType.WithResponse)
            info { "${characteristic.name()}: wrote $len bytes, $offset of ${data.size} written" }
        }
    }

    private suspend fun receivePacket(
        chunks: ReceiveChannel<ByteArray>,
        expectedSize: Int
    ): ByteArray {
        val announcement = commands.receive()

        val size = announcement[1].toInt() and 0xFF
        check(size == expectedSize) { "Expected a packet of $expectedSize bytes, sensor announced $size" }

        val data = ByteArray(size)
        var received = 0
        var sequence = 0
        while (received < size) {
            val chunk = chunks.receive()
            info { "Received ${chunk.size} bytes" }
            if (chunk.isEmpty()) continue

            val count = chunk[0].toInt() and 0xFF
            check(count == sequence) {
                "Invalid packet received, expected sequence number $sequence, got $count"
            }
            sequence++

            chunk.copyInto(data, destinationOffset = received, startIndex = 1)
            received += chunk.size - 1
        }
        return data
    }

    // TODO: Signals enum
    private suspend fun awaitSignal(signal: Int) {
        val value = commands.receive()
        check(value.size == 1 && (value[0].toInt() and 0xFF) == signal) {
            "Expected signal $signal, got ${value.toHexString()}"
        }
    }

    // Glucose packets are split, so they need to accumulate before handling them.
    private val glucoseBuffer = Buffer()
    private suspend fun onGlucoseData(value: ByteArray) {
        glucoseBuffer.write(value)
        if (glucoseBuffer.size < 35) {
            return
        }

        val reading = security.decrypt(
            glucoseBuffer.readByteArray(35),
            Libre3.PacketType.READING
        ) as Libre3.Packet.Reading

        info { "$reading" }
        EventLog.push(sensor.id, reading.serialize())

        // TODO: For unknown reason it fails to write to sensor control unless it has
        //  received the initial sensor status first. Might be just a timing issue,
        //  nothing to do with the sensor status, as it comes in some seconds after
        //  initialization.
        if (this.sensorStatus != null) {
            if (reading.time - sensor.lastReceived > 5.minutes) {
                // Snapshot before reassignment below so the coroutine doesn't consume the new value
                val from = sensor.lastReceived
                scope.launch {
                    try {
                        backfill(from)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error { "backfill failed: $e" }
                    }
                }
            }
            sensor.lastReceived = reading.time
        }

        if (reading.isValid()) {
            SensorEvents.glucoseReading.send(
                GlucoseReading(
                    sensor.id,
                    sensor.activationTime + reading.time,
                    sensor.calibrateReading(reading.glucose())
                )
            )
        }
    }

    private fun onSensorStatus(value: ByteArray) {
        val status = security.decrypt(
            value,
            Libre3.PacketType.STATUS
        ) as Libre3.Packet.Status

        this.sensorStatus = status

        info { "sensor status:\n $status" }
        EventLog.push(sensor.id, status.serialize())

        // TODO: I don't actually know how many of these can be triggered. I know EXPIRED is hit
        //  at least.
        when (status.state()) {
            Libre3.State.EXPIRED,
            Libre3.State.TERMINATED_NORMAL,
            Libre3.State.ERROR,
            Libre3.State.ERROR_TERMINATED -> {
                info { "sensor ${status.state()}, removing" }
                Sensors.remove(sensor.id)
            }

            else -> {}
        }
    }

    private suspend fun onSensorControl(value: ByteArray) {
        val control = security.decrypt(
            value,
            Libre3.PacketType.CONTROL_RECEIVE
        ) as Libre3.Packet.Control

        info { "sensor control: ${control.bytes.toHexString()}" }
        EventLog.push(sensor.id, control.serialize())

        controlAck.send(control)
    }

    private fun onHistoricData(value: ByteArray) {
        val longHistory = security.decrypt(
            value,
            Libre3.PacketType.LONG_HISTORY
        ) as Libre3.Packet.LongHistory

        info { "longHistory: $longHistory" }
        EventLog.push(sensor.id, longHistory.serialize())

        // Historic readings are not saved separately, but are simply ignored if we
        // already have a reading for that instant
        val backFill = this.backFill
        if (backFill == null) {
            info { "historic packet arrived with no active backfill" }
            return
        }
        for (reading in longHistory.readings) {
            backFill.add(
                GlucoseReading(
                    sensor.id,
                    sensor.activationTime + reading.time,
                    sensor.calibrateReading(reading.glucose)
                )
            )
        }
    }

    private fun onClinicalData(value: ByteArray) {
        val shortHistory = security.decrypt(
            value,
            Libre3.PacketType.SHORT_HISTORY
        ) as Libre3.Packet.ShortHistory
        info { "shortHistory: $shortHistory" }

        if (!shortHistory.isValid()) {
            return
        }

        val backFill = this.backFill
        if (backFill == null) {
            info { "clinical packet arrived with no active backfill" }
            return
        }
        backFill.add(
            GlucoseReading(
                sensor.id,
                sensor.activationTime + shortHistory.time,
                sensor.calibrateReading(shortHistory.glucose)
            )
        )
        EventLog.push(sensor.id, shortHistory.serialize())
    }

    private var backFill: BackFill? = null

    // Requests the readings missed since the last received one: first the long (5-minute)
    // history, then the short (1-minute) history which replaces overlapping long readings
    private suspend fun backfill(from: Duration) {
        if (this.backFill != null) return
        val backFill = BackFill().also { this.backFill = it }

        info { "Requesting backfill from minute: ${from.inWholeMinutes}" }

        writeControl(Libre3.Control.longHistory(from))
        writeControl(Libre3.Control.shortHistory(from))

        this.backFill = null
        val readings = backFill.readings
        if (readings.isEmpty()) return

        val from2 = readings.first().time
        val to = readings.last().time
        withContext(Dispatchers.IO) {
            Database.transact(
                "INSERT OR IGNORE INTO glucose_history (sensor_id, time, glucose) VALUES (?, ?, ?)",
                readings.map {
                    arrayOf(
                        it.sensorId.value,
                        it.time.epochSeconds,
                        it.glucose.toMgDl()
                    )
                }
            )
        }
        SensorEvents.history.send(GlucoseHistory(sensor.id, from2, to))
        info { "backfill complete" }
    }
}

private class BackFill {
    val readings: MutableList<GlucoseReading> = mutableListOf()

    fun add(reading: GlucoseReading) {
        // The 5-minute history and the 1-minute history might overlap and since the 5-minute
        // history isn't the measured value but an average we replace it when it happens.
        while (readings.isNotEmpty() && readings.last().time > reading.time) {
            info { "removed: ${readings.removeLast()}" }
        }
        readings.add(reading)
    }
}
