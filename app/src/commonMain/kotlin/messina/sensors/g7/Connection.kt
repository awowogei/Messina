package messina.sensors.g7

import com.juul.kable.Characteristic
import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import messina.Database
import messina.cryptography.secureRandomBytes
import messina.sensors.GlucoseReading
import messina.sensors.HistoryEvent
import messina.sensors.Sensor
import messina.sensors.SensorEvents
import messina.sensors.bondDevice
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

suspend fun g7Connection(sensor: Sensor.G7) = sensorBluetoothConnection(
    sensorId = sensor.id,
    address = sensor.macAddress,
    advertisedName = "DXCM" + sensor.serialNumber.decodeToString(),
) { peripheral ->
    Connection(this, peripheral, sensor).run()
}

/**
 * Dexcom G7 connection: runs the EC-JPAKE handshake via [Security], then streams
 * glucose. Ported from Juggluco's DexGattCallback and cross-checked against xDrip's
 * `libkeks` plugin (jamorham.keks.Plugin), which is the authoritative reference for
 * the round sequencing, bonding, and stored-key reuse.
 *
 * Unlike Libre 3, the G7 streams handshake payloads as raw 20-byte chunks (no offset
 * header) on the key-exchange characteristic and pulls each round with a one-byte
 * `round` command on the auth characteristic.
 */
private class Connection(
    private val scope: CoroutineScope,
    private val peripheral: Peripheral,
    private val sensor: Sensor.G7,
) {
    private val security = Security(sensor)

    private val keyExchange = Channel<ByteArray>(Channel.UNLIMITED)
    private val auth = Channel<ByteArray>(Channel.UNLIMITED)

    // Non-null while a backfill is in progress; collects the streamed historical records.
    private var backfillReadings: MutableList<GlucoseReading>? = null
    private val backfillDone = Channel<Unit>(Channel.CONFLATED)

    suspend fun run() {
        with(scope) {
            subscribe(peripheral, G7.charKeyExchange) { keyExchange.send(it) }
            subscribe(peripheral, G7.charAuth) { auth.send(it) }
        }

        handshake()

        with(scope) {
            subscribe(peripheral, G7.charData, ::onData)
            subscribe(peripheral, G7.charBackfill, ::onBackfill)
        }
        writeData(G7.Command.GET_DATA)
    }

    private suspend fun handshake() {
        if (!security.jpakeCompleted()) {
            jpakeRounds()
            sensor.sharedKey = security.sharedKey
            info { "G7: shared key derived" }
        }
        authenticate()
    }

    /**
     * The three J-PAKE rounds. The sensor sends its cert for each round first; we
     * reply with ours on the key-exchange characteristic, then ask for the next
     * round with a `round` command. (libkeks: aNext RoundStart/Round1/Round2/Round3.)
     */
    private suspend fun jpakeRounds() {
        info { "G7: starting EC-JPAKE" }

        writeCommand(G7.Command.round(0))
        check(security.receiveSensorRound1(receiveCert())) { "sensor round-1 proof invalid" }
        sendChunked(G7.charKeyExchange, security.round1())

        writeCommand(G7.Command.round(1))
        check(security.receiveSensorRound2(receiveCert())) { "sensor round-2 proof invalid" }
        sendChunked(G7.charKeyExchange, security.round2())

        writeCommand(G7.Command.round(2))
        // The sensor's round-3 proof is non-standard, so we derive the key without gating on it.
        security.receiveSensorRound3(receiveCert())
        sendChunked(G7.charKeyExchange, security.round3())
    }

    private suspend fun authenticate() {
        // RequestAuth: send our 8 random bytes wrapped in 0x02 markers, verify the
        // sensor encrypted them under the session key, and reply with our encryption
        // of its challenge. (libkeks: AuthRequestTxMessage2 / verifyChallenge / AuthChallengeTxMessage.)
        val random8 = secureRandomBytes(8)
        writeAuth(byteArrayOf(0x02) + random8 + byteArrayOf(0x02))

        val response = auth.receive()
        check(security.aesResponse(random8).contentEquals(response.copyOfRange(1, 9))) {
            "sensor AES auth mismatch (wrong PIN?)"
        }
        writeAuth(byteArrayOf(0x04) + security.aesResponse(response.copyOfRange(9, 17)))

        handleAuthStatus(auth.receive())
    }

    /** [0x05, authenticated, bonded] — decides bonding vs proceeding. (libkeks: ChallengeReply.) */
    private suspend fun handleAuthStatus(status: ByteArray) {
        require(status.size >= 3 && status[0].toInt() == 0x05) { "unexpected auth status ${status.toHexString()}" }
        val authenticated = status[1].toInt() == 1
        val bonded = status[2].toInt()

        when {
            // Key no longer valid on the sensor; drop it and reconnect to re-pair.
            bonded == 3 -> { sensor.sharedKey = null; error("G7: sensor requested key refresh") }
            !authenticated -> { sensor.sharedKey = null; error("G7: not authenticated (wrong PIN?)") }
            // Already bonded (reconnect): proceed straight to data.
            bonded == 1 -> info { "G7: authenticated and bonded" }
            else -> certExchange()
        }
    }

    /**
     * The primary G7 first-pairing path (Juggluco authenticate → askcertificate →
     * sendkeychallenge): present our two app certificates, prove possession of the leaf
     * key by signing the sensor's challenge, then bond. The 4-byte serial-suffix password
     * routes here — there is no pairing-code branch in Juggluco's G7.
     */
    private suspend fun certExchange() {
        info { "G7: authenticated, exchanging certificates" }
        sendCertificate(0)
        sendCertificate(1)
        signKeyChallenge()
        bond()
    }

    /**
     * Announce our certificate's size on the auth char, drain the sensor's certificate
     * off the key-exchange char (its content is unused, exactly as Juggluco discards it),
     * then stream ours back. (Juggluco: askcertificate + getcert.)
     */
    private suspend fun sendCertificate(index: Int) {
        val ourCert = G7.APP_CERTIFICATES[index]
        writeAuth(G7.Command.certInfo(index, ourCert.size))
        receivePacket(keyExchange, sensorCertSize(auth.receive()))
        sendChunked(G7.charKeyExchange, ourCert)
    }

    /**
     * Sign the sensor's challenge with the embedded app key to prove we hold the leaf
     * certificate's private key. (Juggluco: sendkeychallenge + dexChallenger.)
     */
    private suspend fun signKeyChallenge() {
        writeAuth(byteArrayOf(0x0c) + secureRandomBytes(16))
        val signature = security.certificateChallenge(auth.receive())
        sendChunked(G7.charKeyExchange, signature)
        writeAuth(G7.Command.SIGN_CHALLENGE_OUT)
    }

    /** The sensor's reply to certInfo: [0x0b, state, which, size_le16, …]. */
    private fun sensorCertSize(reply: ByteArray): Int {
        require(reply.size >= 5 && reply[0].toInt() == 0x0b) { "unexpected cert-info reply ${reply.toHexString()}" }
        return (reply[3].toInt() and 0xFF) or ((reply[4].toInt() and 0xFF) shl 8)
    }

    /**
     * Signal readiness, then bond reactively. As Juggluco's authenticate() does, we wait
     * for the sensor's post-challenge ack, send TIME_EXTENDED, and only call createBond
     * once the sensor sends back one of the bond-request sequences — waiting for the bond
     * to actually complete before returning. (Juggluco: SendKeyChallengeOut + bondBytes
     * check + bonded() callback.)
     */
    private suspend fun bond() {
        auth.receive() // the sensor's ack of our signed challenge
        writeAuth(TIME_EXTENDED)

        withTimeout(30.seconds) {
            while (true) {
                val message = auth.receive()
                if (BOND_REQUESTS.any { it.contentEquals(message) }) {
                    info { "G7: sensor requested bond, creating bond" }
                    check(bondDevice(sensor.macAddress)) { "G7: bonding failed" }
                    info { "G7: bonded" }
                    return@withTimeout
                }
                info { "G7: awaiting bond request, ignoring ${message.toHexString()}" }
            }
        }
    }

    private suspend fun onData(value: ByteArray) {
        if (value.isEmpty()) return
        when (value[0].toInt() and 0xFF) {
            0x4E -> onGlucose(value)
            // Signals the end of a backfill stream; only meaningful while one is active.
            0x59 -> if (backfillReadings != null) backfillDone.trySend(Unit)
            else -> info { "G7 data: ${value.toHexString()}" }
        }
    }

    private fun onGlucose(value: ByteArray) {
        val reading = G7.Packet.reading(value)
        info { "G7 reading: $reading" }
        if (reading.isValid()) {
            sensor.addReading(
                sensor.activationTime + reading.time,
                reading.glucose(),
            )
        }
        if (reading.time - sensor.lastReceived > 5.minutes) {
            // Snapshot before the update below so the launched coroutine sees the gap start.
            val from = sensor.lastReceived
            scope.launch {
                try {
                    backfill(from, reading.time)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error { "G7 backfill failed: $e" }
                }
            }
        }
        sensor.lastReceived = reading.time
    }

    /** A backfilled historical record on the backfill characteristic (9-byte dexbackfill). */
    private fun onBackfill(value: ByteArray) {
        val readings = backfillReadings ?: return
        if (value.size < 9) return
        val record = G7.Packet.backfill(value)
        if (record.isValid()) {
            readings.add(GlucoseReading(sensor.activationTime + record.time, record.glucose()))
        }
    }

    /**
     * Request the readings missed since [from], collect the records streamed on the backfill
     * characteristic until the sensor signals completion, then persist them as history.
     */
    private suspend fun backfill(from: Duration, to: Duration) {
        if (backfillReadings != null) return
        val readings = mutableListOf<GlucoseReading>().also { backfillReadings = it }
        info { "G7: requesting backfill ${from.inWholeMinutes}..${to.inWholeMinutes} min" }

        writeData(G7.Command.backfill(from, to))
        withTimeout(30.seconds) { backfillDone.receive() }
        backfillReadings = null

        if (readings.isEmpty()) {
            info { "G7: backfill complete, no readings" }
            return
        }
        val sorted = readings.sortedBy { it.time }
        withContext(Dispatchers.IO) {
            Database.transact(
                "INSERT OR IGNORE INTO glucose_history (sensor_id, time, glucose) VALUES (?, ?, ?)",
                sorted.map { arrayOf(sensor.id.value, it.time.epochSeconds, it.glucose.toMgDl()) },
            )
        }
        SensorEvents.history.send(HistoryEvent(sensor.id, sorted.first().time, sorted.last().time))
        info { "G7: backfill complete, ${sorted.size} readings" }
    }

    /** Reassemble a 160-byte cert streamed as raw 20-byte chunks (no header). */
    private suspend fun receiveCert(): ByteArray = receivePacket(keyExchange, G7.CERT_SIZE)

    private suspend fun receivePacket(chunks: ReceiveChannel<ByteArray>, size: Int): ByteArray {
        val data = ByteArray(size)
        var received = 0
        while (received < size) {
            val chunk = chunks.receive()
            if (chunk.isEmpty()) continue
            chunk.copyInto(data, received)
            received += chunk.size
        }
        return data
    }

    /** Write [data] to [characteristic] as raw chunks of at most [G7.CHUNK_SIZE] bytes. */
    private suspend fun sendChunked(characteristic: Characteristic, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + G7.CHUNK_SIZE, data.size)
            peripheral.write(characteristic, data.copyOfRange(offset, end), WriteType.WithoutResponse)
            offset = end
            delay(40) // the sensor drops chunks sent back-to-back
        }
    }

    private suspend fun writeCommand(command: ByteArray) =
        peripheral.write(G7.charAuth, command, WriteType.WithResponse)

    private suspend fun writeAuth(data: ByteArray) =
        peripheral.write(G7.charAuth, data, WriteType.WithResponse)

    private suspend fun writeData(data: ByteArray) =
        peripheral.write(G7.charData, data, WriteType.WithoutResponse)

    companion object {
        // Sent to the sensor to signal we are ready to proceed / extend the session.
        private val TIME_EXTENDED = byteArrayOf(0x06, 0x19)

        // Sequences the sensor sends to ask us to bond; receiving one triggers createBond.
        private val BOND_REQUESTS = listOf(
            byteArrayOf(0x06, 0x19),
            byteArrayOf(0xFF.toByte(), 0x06, 0x01),
            byteArrayOf(0x06, 0x00),
        )
    }
}
