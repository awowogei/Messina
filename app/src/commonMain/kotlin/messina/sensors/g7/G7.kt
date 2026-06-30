package messina.sensors.g7

import com.juul.kable.characteristicOf
import messina.Glucose
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.readIntLe
import kotlinx.io.readUShortLe
import kotlinx.io.writeIntLe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Dexcom G7 sensor definitions: BLE topology, the pairing/authentication phase
 * machine, the wire commands, glucose packet parsing, and the G7-specific
 * constants the (general) cryptography module expects to be supplied.
 *
 * The handshake itself is EC-JPAKE (see [Security]); everything here is the
 * deployment-specific framing around it.
 */
object G7 {

    // @formatter:off
    object Services {
        val SERVICE        = Uuid.parse("f8083532-849e-531c-c594-30f1f86a4ea5")
        // f8083534: glucose / backfill stream and the 0x4E/0x59 data commands
        val DATA           = Uuid.parse("f8083534-849e-531c-c594-30f1f86a4ea5")
        // f8083535: authentication (round requests, challenge/response, bonding)
        val AUTHENTICATION = Uuid.parse("f8083535-849e-531c-c594-30f1f86a4ea5")
        // f8083536: backfill control
        val BACKFILL       = Uuid.parse("f8083536-849e-531c-c594-30f1f86a4ea5")
        // f8083538: certificate / J-PAKE key-exchange payloads (raw 20-byte chunks)
        val KEY_EXCHANGE   = Uuid.parse("f8083538-849e-531c-c594-30f1f86a4ea5")
    }

    // Advertised under this 16-bit service when scanning.
    val SCAN_SERVICE = Uuid.parse("0000febc-0000-1000-8000-00805f9b34fb")
    // @formatter:on

    val charData        = characteristicOf(Services.SERVICE, Services.DATA)
    val charAuth        = characteristicOf(Services.SERVICE, Services.AUTHENTICATION)
    val charBackfill    = characteristicOf(Services.SERVICE, Services.BACKFILL)
    val charKeyExchange = characteristicOf(Services.SERVICE, Services.KEY_EXCHANGE)

    /** A J-PAKE cert on the wire: pubKey(64) ‖ commitment(64) ‖ proof(32). */
    const val CERT_SIZE = 160

    /** Largest GATT payload the sensor accepts per write. */
    const val CHUNK_SIZE = 20

    // ---- Wire commands ----

    object Command {
        /**
         * Tells the sensor which J-PAKE round to send next (0x0A followed by the
         * round number), written to the authentication characteristic.
         */
        fun round(round: Int): ByteArray = byteArrayOf(0x0A, round.toByte())

        /** Asks the sensor to start streaming current glucose (libkeks GETDATA). */
        val GET_DATA = byteArrayOf(0x4E, 0x0A, 0xA9.toByte())

        /** Announces the size of an app certificate we're about to stream (Juggluco lencode). */
        fun certInfo(which: Int, size: Int): ByteArray {
            val buffer = Buffer()
            buffer.writeByte(0x0b)
            buffer.writeByte(which.toByte())
            buffer.writeIntLe(size)
            return buffer.readByteArray()
        }

        /** Marker written after streaming our signed challenge response. */
        val SIGN_CHALLENGE_OUT = byteArrayOf(0x0d, 0x00, 0x02)

        /**
         * Requests a backfill of missed readings between two sensor-relative
         * second offsets: 0x59 followed by start and end as little-endian i32.
         */
        fun backfill(start: Duration, end: Duration): ByteArray {
            val buffer = Buffer()
            buffer.writeByte(0x59)
            buffer.writeIntLe(start.inWholeSeconds.toInt())
            buffer.writeIntLe(end.inWholeSeconds.toInt())
            return buffer.readByteArray()
        }
    }

    // ---- Glucose data ----

    @Serializable
    sealed class Packet {
        /**
         * A live glucose reading (the 19-byte 0x4E packet). Field layout follows
         * Juggluco's `glucoseinput` struct (little-endian, packed).
         */
        @Serializable
        @SerialName("Reading")
        data class Reading(
            // Seconds since sensor start for this measurement.
            val secsSinceStart: Int,
            // How many seconds ago the measurement was actually taken.
            val age: Int,
            private val mgdL: Int,
            private val predictedMgdL: Int,
            // Rate of change in mg/dL per minute (the raw trend / 10).
            val rateOfChange: Double,
            val state: Int,
        ) : Packet() {
            // Reading time relative to sensor start.
            val time: Duration get() = (secsSinceStart - age).seconds

            fun isValid(): Boolean = mgdL in 39..501

            fun glucose(): Glucose = Glucose.fromMgDl(mgdL.toDouble())
        }

        /**
         * A backfilled historical reading (the 9-byte record streamed on the backfill
         * characteristic). Field layout follows Juggluco's `dexbackfill` struct.
         */
        @Serializable
        @SerialName("Backfill")
        data class Backfill(
            val secsSinceStart: Int,
            private val mgdL: Int,
            val type: Int,
            val rateOfChange: Double,
        ) : Packet() {
            val time: Duration get() = secsSinceStart.seconds

            // Juggluco's `usable()`: only calibrated/ok states carry a real glucose value.
            fun isValid(): Boolean = type == 0x06 || type == 0x07 || type == 0x0E

            fun glucose(): Glucose = Glucose.fromMgDl(mgdL.toDouble())
        }

        companion object {
            fun reading(data: ByteArray): Reading {
                require(data.size >= 19) { "G7 reading must be at least 19 bytes, got ${data.size}" }
                val buffer = Buffer().also { it.write(data) }
                buffer.skip(1)                          // type (0x4E)
                buffer.skip(1)                          // status
                val secsSinceStart = buffer.readIntLe()
                buffer.skip(2)                          // sequence
                buffer.skip(2)                          // bogus
                val age = buffer.readUShortLe().toInt()
                val mgdL = buffer.readUShortLe().toInt() and 0x0FFF   // low 12 bits
                val state = buffer.readByte().toInt()
                val trend = buffer.readByte().toInt()                 // signed
                val predicted = buffer.readUShortLe().toInt() and 0x03FF // low 10 bits
                return Reading(
                    secsSinceStart = secsSinceStart,
                    age = age,
                    mgdL = mgdL,
                    predictedMgdL = predicted,
                    rateOfChange = trend / 10.0,
                    state = state,
                )
            }

            fun backfill(data: ByteArray): Backfill {
                require(data.size >= 9) { "G7 backfill record must be at least 9 bytes, got ${data.size}" }
                val buffer = Buffer().also { it.write(data) }
                val secsSinceStart = buffer.readIntLe()
                val mgdL = buffer.readUShortLe().toInt() and 0x0FFF // low 12 bits
                val type = buffer.readByte().toInt() and 0xFF
                buffer.skip(1)                          // extra
                val trend = buffer.readByte().toInt()   // signed
                return Backfill(
                    secsSinceStart = secsSinceStart,
                    mgdL = mgdL,
                    type = type,
                    rateOfChange = trend / 10.0,
                )
            }
        }
    }

    // ---- G7-specific cryptographic constants supplied to messina.cryptography ----

    /** Party identifier this client tags its own J-PAKE proofs with ("client"). */
    val CLIENT_PARTY = byteArrayOf(0x63, 0x6c, 0x69, 0x65, 0x6e, 0x74)

    /** Party identifier the sensor tags its proofs with; used when validating them. */
    val SENSOR_PARTY = byteArrayOf(0x37, 0x56, 0x27, 0x67, 0x56, 0x27)

    /** Fixed nonce the G7 expects for the round-3 (combined-base) proof. */
    const val ROUND3_NONCE_HEX = "fbc971b837e9491e45a4179ed33865c508a1e0a1d350f5af0f96370695fdc393"

    /** Embedded application signing key used for the certificate challenge (getchallenge). */
    const val APP_PRIVATE_KEY_HEX = "7cfbd596f6e74477b8c0e9f6f7a174275e101ef6bf7d18caf01181d127b579"

    /**
     * The app's DER certificate chain, streamed during the unbonded auth path:
     * [0] is the DEX00PG1 CA, [1] is the DEX03PG1 leaf whose key matches
     * [APP_PRIVATE_KEY_HEX].
     */
    val APP_CERTIFICATES: Array<ByteArray> = arrayOf(
        ("308201ea3082018fa00302010202142f3c52b6eb08701046d45d78ce81784c9dfe5240300a06082a8648ce3d0403" +
            "0230133111300f06035504030c084445583030504731301e170d3230313033303135353930345a170d33353130" +
            "32373135353930345a30133111300f06035504030c0844455830335047313059301306072a8648ce3d02010608" +
            "2a8648ce3d03010703420004fb1aca21d8aeec9a4eb51f85304953d977a1ad569799250ff863987f42a3cd9fa4ff" +
            "571eb568bc6c396277c3dcb51dedaee85513c80a5c4435538a19f5a96348a381c03081bd300f0603551d130101ff" +
            "040530030101ff301f0603551d230418301680149e0f1e36f3f276a701fe8e883a6e26a635bd6afc305a0603551d" +
            "1f04533051304fa034a0328630687474703a2f2f63726c2e64702e736161732e7072696d656b65792e636f6d2f63" +
            "726c2f44455830305047312e63726ca217a41530133111300f06035504030c084445583030504731301d0603551d" +
            "0e0416041488f61e81bc4b17f05c6b1be2991d60087ccedd79300e0603551d0f0101ff040403020186300a06082a" +
            "8648ce3d0403020349003046022100aa69cd897ec663af5f9e158187df6851ff0756f00c401624564f81a19f5a07" +
            "85022100daebb9fdb163b731eb0661f1c0a1932871a50e399ad1c6f519eabd4c9e7ba013").hexToByteArray(),
        ("308201cd30820174a003020102021419052fcc17530bfa56e49dcafcdacf853ce5ba73300a06082a8648ce3d0403" +
            "0230133111300f06035504030c084445583033504731301e170d3233303431343130323831345a170d32353034" +
            "31333130323831335a303a3138303606035504030c2f30312c303030302c303330304c514543437a4142417741" +
            "412c63696f69653356625132686c5a4d6a64556d357267413059301306072a8648ce3d020106082a8648ce3d03" +
            "0107034200045118c35e9e41e7e0654fee801c52a9c5dfc510ef09597d5cca8461e4af9c666714834f2bc903f16" +
            "fabfc45755b0183f1a09745cdffcb4e2f799e50bed9a6b58ca37f307d300c0603551d130101ff04023000301f06" +
            "03551d2304183016801488f61e81bc4b17f05c6b1be2991d60087ccedd79301d0603551d250416301406082b06" +
            "01050507030206082b06010505070301301d0603551d0e04160414d309e75c0725412d7a7922e3aacfb27f7ebd6" +
            "be0300e0603551d0f0101ff0404030205a0300a06082a8648ce3d0403020347003044022048d4868cf393d90441" +
            "01b6f07fd68d7f0642805f85da74e2fe9de8dd3507f02702201cd1bf7c6c7edd59435e324925fcf0ebb3cae2110d" +
            "79407c77aa3b93b7bc04cb").hexToByteArray(),
    )
}
