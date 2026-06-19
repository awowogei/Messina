package messina.sensors.libre3

import messina.http.Http
import messina.sensors.Sensor
import messina.cryptography.EcdhKeyPair
import messina.cryptography.sha256
import messina.logging.info
import kotlinx.coroutines.runBlocking
import kotlin.random.Random

// @formatter:off
object Crypto {
    // Java bytes are signed so you have to call toByte() on some hexes so we just do it for all of them
    private fun bytesOf(vararg ints: Int): ByteArray = ints.map { it.toByte() }.toByteArray()

    val APP_CERTIFICATES: Array<ByteArray> = arrayOf(
        bytesOf(
            0x03, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e,
            0x0f, 0x10, 0x00, 0x01, 0x5f, 0x14, 0x9f, 0xe1, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x04, 0x27, 0x51, 0xfd, 0x1e, 0xf4, 0x2b, 0x14, 0x5a, 0x52, 0xc5, 0x93, 0xae, 0x6b, 0x5a,
            0x75, 0x58, 0x8a, 0x9f, 0x7e, 0xaf, 0x1c, 0x0f, 0x99, 0x85, 0xf9, 0x93, 0xd5, 0x8f, 0x14, 0x7b,
            0xb8, 0x41, 0x68, 0x42, 0x24, 0x49, 0x96, 0x37, 0x92, 0xdc, 0x43, 0xf3, 0x84, 0x47, 0xef, 0xeb,
            0xbb, 0xeb, 0x4a, 0x53, 0xb3, 0x25, 0x5c, 0x0b, 0xe0, 0xfe, 0x1f, 0x23, 0x58, 0x44, 0xa3, 0xd3,
            0x29, 0x9e, 0xba, 0x97, 0xb8, 0xe6, 0xc3, 0x17, 0x09, 0x39, 0xf2, 0x77, 0x8f, 0x64, 0x86, 0x6f,
            0x06, 0x6d, 0xeb, 0x91, 0x5d, 0xd6, 0x62, 0x9e, 0xee, 0x47, 0x30, 0xa1, 0xe1, 0x4c, 0xab, 0x75,
            0xc1, 0x8c, 0x4f, 0xec, 0x53, 0xf8, 0x85, 0x4c, 0x87, 0x64, 0x3a, 0x76, 0x4f, 0x40, 0x87, 0xae,
            0xc0, 0x39, 0x4c, 0x21, 0x0c, 0x18, 0x86, 0x5a, 0x8f, 0xf4, 0x5a, 0xdc, 0x37, 0x27, 0xf4, 0x8b,
            0x53, 0xa7
        ),
        bytesOf(
            0x03, 0x03, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e,
            0x0f, 0x10, 0x00, 0x01, 0x61, 0x89, 0x76, 0x55, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x04, 0x82, 0x42, 0xbe, 0x33, 0xf1, 0xa3, 0x30, 0x88, 0x01, 0x12, 0xfa, 0x62, 0xcc, 0x48,
            0x42, 0xa4, 0x3d, 0x12, 0x04, 0x92, 0x2a, 0xd2, 0x01, 0xd8, 0x77, 0x5b, 0xb2, 0x26, 0xf6, 0x11,
            0xf7, 0x5b, 0x0e, 0xf3, 0xd5, 0xbc, 0x6c, 0xc4, 0x31, 0x7c, 0xaa, 0x45, 0x75, 0x84, 0xab, 0x00,
            0x3f, 0x17, 0x12, 0x33, 0x60, 0x89, 0xd3, 0xa4, 0xf2, 0x98, 0x38, 0xed, 0x0d, 0xc6, 0x66, 0xde,
            0xae, 0xa2, 0xd6, 0x5a, 0x00, 0xdf, 0xff, 0x5d, 0x7b, 0xca, 0xe2, 0x16, 0x55, 0xe3, 0x02, 0xe3,
            0x45, 0x8e, 0x77, 0x4d, 0xaa, 0xaa, 0xca, 0x87, 0xaf, 0x75, 0xf1, 0xb8, 0x78, 0x84, 0xb1, 0x8d,
            0x4c, 0xe8, 0x75, 0xd0, 0xd1, 0x08, 0xc9, 0x03, 0xa8, 0x34, 0x47, 0x1a, 0x4f, 0xf6, 0x74, 0xb2,
            0xd3, 0x0b, 0xcb, 0xa0, 0x62, 0x37, 0x30, 0x14, 0xb7, 0x78, 0x6e, 0x44, 0x37, 0xb1, 0x77, 0xae,
            0xc3, 0xc8
        ),
    )

    val APP_PRIVATE_KEYS: Array<ByteArray> = arrayOf(
        bytesOf(
            0x43, 0xF2, 0xC5, 0x3D, 0x02, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x96, 0x95, 0x77, 0x4B, 0x9A, 0x04, 0x53, 0x51, 0xFB, 0x16, 0x0B, 0xEC, 0x5F, 0x49, 0xDB,
            0xDF, 0x57, 0x45, 0x48, 0x50, 0x67, 0x78, 0x6C, 0xDE, 0x13, 0x08, 0x83, 0xD8, 0x3D, 0xF6, 0x96,
            0x81, 0x4E, 0xA4, 0x1E, 0xA7, 0xD2, 0xF8, 0xD2, 0x30, 0x84, 0x76, 0xB4, 0x9A, 0x01, 0x2C, 0x4E,
            0xBB, 0x00, 0x00, 0x00, 0x01, 0x7D, 0x4D, 0x61, 0x51, 0x06, 0x81, 0xBF, 0x22, 0x31, 0x67, 0x6B,
            0x90, 0x3B, 0x17, 0xED, 0x53, 0x98, 0x0D, 0x98, 0xFE, 0x68, 0x2E, 0xE4, 0x4B, 0x00, 0x00, 0x00,
            0x20, 0x5B, 0x7B, 0x96, 0xAA, 0xE3, 0xFF, 0x22, 0x2D, 0x4D, 0x37, 0x1E, 0x7A, 0xA6, 0x2C, 0xFA,
            0xA0, 0x9B, 0xF8, 0x42, 0x1C, 0xC1, 0xDA, 0x7B, 0x7B, 0x0D, 0xF9, 0x34, 0x33, 0xCC, 0x49, 0xFB,
            0x0E, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x96, 0x9E, 0xDB, 0x28, 0xBF, 0x6F, 0xC0, 0xFF, 0x76, 0x0A, 0xF0, 0x95, 0x92, 0x1D, 0x9F,
            0x1E, 0x3B, 0x16, 0x77, 0xB5
        ),
        bytesOf(
            0x1D, 0x85, 0x8F, 0x06, 0x02, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x96, 0x95, 0x77, 0x4B, 0x9A, 0x04, 0x53, 0x51, 0xFB, 0x16, 0x0B, 0xEC, 0x5F, 0x49, 0xDB,
            0xDF, 0x0D, 0xC0, 0xCE, 0x52, 0xFB, 0x56, 0x5F, 0x84, 0xE6, 0x13, 0xB8, 0x19, 0xAE, 0xD3, 0xDF,
            0x91, 0x9C, 0xE3, 0x0A, 0x3D, 0xD4, 0xC0, 0x12, 0xEA, 0xEA, 0x70, 0xC8, 0xCC, 0xE2, 0x89, 0x58,
            0x40, 0x00, 0x00, 0x00, 0x01, 0x9B, 0xC7, 0x79, 0x12, 0x3D, 0x86, 0x60, 0xB3, 0x7E, 0x99, 0xB4,
            0xBF, 0x10, 0xC1, 0xC4, 0x2C, 0x11, 0x35, 0xB3, 0x02, 0x5B, 0xC9, 0xB2, 0xEF, 0x00, 0x00, 0x00,
            0x20, 0xE3, 0xA1, 0xFB, 0x17, 0x80, 0xA1, 0x63, 0x80, 0x2A, 0xA0, 0xFE, 0xB1, 0xF2, 0x00, 0xAC,
            0x26, 0x9A, 0x42, 0xB2, 0x29, 0x03, 0x8C, 0xA6, 0xE1, 0x4D, 0x40, 0xEF, 0xBC, 0x6B, 0x7B, 0x6A,
            0xE8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0xCE, 0xC6, 0x67, 0xE6, 0xC0, 0x9D, 0x20, 0xF5, 0xC0, 0x33, 0xD0, 0x61, 0xB5, 0xFC, 0xA1,
            0x8B, 0x39, 0x92, 0x06, 0x8B
        )
    )
}
// @formatter:on

class Security(sensor: Sensor.Libre3) {
    private val blePin = sensor.blePin
    var sharedStaticKey: ByteArray? = sensor.sharedStaticKey
    var sharedKey: ByteArray? = sensor.sharedKey

    val ephemeral = EcdhKeyPair.generate()
    lateinit var sensorStaticKey: ByteArray

    private var r1 = ByteArray(16)
    private var r2 = ByteArray(16)
    private var nonce1 = ByteArray(7)
    private lateinit var kEnc: ByteArray
    private lateinit var ivEnc: ByteArray

    enum class EcdhCommand(val id: Int) {
        // Reset the sensor
        INIT_ECDH(1),

        // Signals it to prepare for the application cert
        PREPARE_FOR_APP_CERT(2),

        // Signals the application cert is sent
        APP_CERT_SENT(3),

        // Signals that the second round challenge is sent
        CHALLENGE_SENT(8),

        // Signals that the sensor can send its cert
        SEND_CERTIFICATE(9),

        // Signals that the sensor cert is received
        SENSOR_CERT_RECEIVED(13),

        // Signals that the app ephemeral key is sent
        APP_KEY_SENT(14),

        // Signals that the sensor can start sending challenge data
        INIT_CHALLENGE(17)
    }

    fun ecdhCompleted(): Boolean {
        return this.sharedKey != null
    }

    fun computeSharedKey(sensorEphemeral: ByteArray) {
        if (this.sharedStaticKey == null) {
            // TODO: This will lock on server strain. Disconnect, poll server for result,
            //  then reconnect
            this.sharedStaticKey = runBlocking {
                Http.post(
                    "https://149.28.60.85.nip.io",
//                    "http://192.168.1.203:8080",
                    mapOf(
                        "private_key" to Crypto.APP_PRIVATE_KEYS[1].toHexString(),
                        "public_key" to sensorStaticKey.toHexString()
                    ),
                    timeout = 2000
                ).body!!.hexToByteArray()
            }
        }
        val sharedEphemeral = ephemeral.sharedKey(sensorEphemeral)
        this.sharedKey =
            sha256("00000001".hexToByteArray() + sharedEphemeral + this.sharedStaticKey!!)
    }

    fun makeChallenge(data: ByteArray): ByteArray {
        info { "challenge data: ${data.toHexString()} " }
        data.copyInto(this.r1, 0, 0, 16)
        data.copyInto(this.nonce1, 0, 16, 16 + 7)
        info { "r1: ${this.r1.toHexString()}" }
        info { "nonce1: ${this.nonce1.toHexString()}" }

        Random.nextBytes(this.r2)
        info { "r2: ${this.r2.toHexString()}" }
        info { "blepin: ${this.blePin.toHexString()}" }

        val uit = ByteArray(36)
        this.r1.copyInto(uit, 0)
        this.r2.copyInto(uit, 16)
        this.blePin.copyInto(uit, 32)
        info { "uit: ${uit.toHexString()}" }

        val kenc = this.sharedKey!!.copyOfRange(0, 16)
        val encrypted = AesCcm(7).encrypt(kenc, nonce1, uit)
        return encrypted
    }

    fun solveChallenge(challenge: ByteArray) {
        val data = challenge.copyOfRange(0, 60)
        val nonce = challenge.copyOfRange(60, 67)
        val kenc = this.sharedKey!!.copyOfRange(0, 16)
        val decrypted = AesCcm(7).decrypt(kenc, nonce, data)

        //val sensorR1 = decrypted.copyOfRange(16, 32)
        //if (!this.r1.contentEquals(sensorR1)) {
        //    error { "Sensor returned wrong r1" }
        //}

        //val sensorR2 = decrypted.copyOfRange(0, 16)
        //if (!this.r2.contentEquals(sensorR2)) {
        //    error { "Sensor returned wrong r2" }
        //}

        this.kEnc = decrypted.copyOfRange(32, 48)
        this.ivEnc = decrypted.copyOfRange(48, 56)
        println("k_enc: ${this.kEnc.toHexString()}")
    }

    private var sequenceNumber = 1
    fun encrypt(plaintext: ByteArray, type: Libre3.PacketType): ByteArray {
        val nonce = ByteArray(13)
        nonce[0] = (sequenceNumber and 0xFF).toByte()
        nonce[1] = ((sequenceNumber shr 8) and 0xFF).toByte()
        sequenceNumber++

        type.header.copyInto(nonce, destinationOffset = 2)
        this.ivEnc.copyInto(nonce, destinationOffset = 5)

        return AesCcm(13).encrypt(
            this.kEnc,
            nonce,
            plaintext
        ) + nonce.copyOfRange(0, 2)
    }

    fun decrypt(ciphertext: ByteArray, type: Libre3.PacketType): Libre3.Packet {
        // Sequence number(?) + packet identifier + ivEnc
        var nonce = ciphertext.copyOfRange(ciphertext.size - 2, ciphertext.size)
        nonce += type.header
        nonce += this.ivEnc

        val withoutSequence = ciphertext.copyOfRange(0, ciphertext.size - 2)

        val data = AesCcm(13).decrypt(
            this.kEnc,
            nonce,
            withoutSequence
        )

        info { "data: ${data.toHexString()}" }
        return Libre3.Packet.deserialize(type, data)
    }
}

private class AesCcm(
    private val nonceLen: Int
) {
    private val tagLen: Int = 4
    private val L = 15 - nonceLen
    private val flagsB0 = ((L - 1) or (((tagLen - 2) / 2) shl 3)).toByte()
    private val flagsA = (L - 1).toByte()

    init {
        require(nonceLen in 7..13) { "nonceLen must be 7–13, got $nonceLen" }
    }

    fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        validateParams(key, nonce)
        val tag = cbcMac(key, nonce, plaintext)
        val encTag = xorTag(key, nonce, tag)
        val ct = ctrXor(key, nonce, plaintext, startCounter = 1)
        return ct + encTag
    }

    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        validateParams(key, nonce)
        require(ciphertext.size >= tagLen) { "Ciphertext too short" }

        val ct = ciphertext.copyOfRange(0, ciphertext.size - tagLen)
        val encTag = ciphertext.copyOfRange(ciphertext.size - tagLen, ciphertext.size)
        val plaintext = ctrXor(key, nonce, ct, startCounter = 1)
        val expectedTag = cbcMac(key, nonce, plaintext)
        val actualTag = xorTag(key, nonce, encTag)

        require(constantTimeEquals(expectedTag, actualTag)) {
            "CCM authentication tag mismatch, ciphertext is invalid or corrupted"
        }
        return plaintext
    }

    private fun cbcMac(key: ByteArray, nonce: ByteArray, message: ByteArray): ByteArray {
        val b0 = ByteArray(16)
        b0[0] = flagsB0
        nonce.copyInto(b0, destinationOffset = 1)
        encodeLengthBigEndian(message.size.toLong(), b0, offset = 1 + nonceLen, fieldLen = L)

        var x = AES128.encryptBlock(key, b0)

        val padLen = (message.size + 15) / 16 * 16
        val padded = message.copyOf(padLen)
        for (i in 0 until padLen / 16) {
            val block = padded.sliceArray(i * 16 until (i + 1) * 16)
            x = AES128.encryptBlock(
                key,
                ByteArray(16) { j -> (x[j].toInt() xor block[j].toInt()).toByte() })
        }
        return x.copyOf(tagLen)
    }

    private fun ctrXor(
        key: ByteArray,
        nonce: ByteArray,
        data: ByteArray,
        startCounter: Int
    ): ByteArray {
        val result = ByteArray(data.size)
        var offset = 0
        var counter = startCounter
        while (offset < data.size) {
            val s = encryptCounter(key, nonce, counter++)
            val take = minOf(16, data.size - offset)
            for (i in 0 until take) result[offset + i] =
                (data[offset + i].toInt() xor s[i].toInt()).toByte()
            offset += take
        }
        return result
    }

    private fun xorTag(key: ByteArray, nonce: ByteArray, tag: ByteArray): ByteArray {
        val s0 = encryptCounter(key, nonce, counter = 0)
        return ByteArray(tagLen) { i -> (tag[i].toInt() xor s0[i].toInt()).toByte() }
    }

    private fun encryptCounter(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
        val a = ByteArray(16)
        a[0] = flagsA
        nonce.copyInto(a, destinationOffset = 1)
        encodeLengthBigEndian(counter.toLong(), a, offset = 1 + nonceLen, fieldLen = L)
        return AES128.encryptBlock(key, a)
    }

    private fun encodeLengthBigEndian(value: Long, dest: ByteArray, offset: Int, fieldLen: Int) {
        for (i in fieldLen - 1 downTo 0) {
            dest[offset + (fieldLen - 1 - i)] = ((value shr (i * 8)) and 0xFF).toByte()
        }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private fun validateParams(key: ByteArray, nonce: ByteArray) {
        require(key.size == 16) { "AES-128 key must be 16 bytes, got ${key.size}" }
        require(nonce.size == nonceLen) { "Nonce must be $nonceLen bytes, got ${nonce.size}" }
    }
}

private object AES128 {
    // @formatter:off
    private val SBOX = intArrayOf(
        0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
        0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
        0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
        0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
        0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
        0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
        0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
        0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
        0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
        0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
        0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
        0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
        0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
        0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
        0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
        0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
    )
    // @formatter:on

    private val RCON = intArrayOf(
        0x01000000, 0x02000000, 0x04000000, 0x08000000, 0x10000000,
        0x20000000, 0x40000000, 0x80000000.toInt(), 0x1b000000, 0x36000000,
    )

    fun encryptBlock(key: ByteArray, block: ByteArray): ByteArray {
        require(key.size == 16) { "AES-128 key must be 16 bytes" }
        require(block.size == 16) { "AES block must be 16 bytes" }

        val rk = keyExpansion(key)
        val s = IntArray(4) { col ->
            (block[col * 4].toInt() and 0xFF shl 24) or
                    (block[col * 4 + 1].toInt() and 0xFF shl 16) or
                    (block[col * 4 + 2].toInt() and 0xFF shl 8) or
                    (block[col * 4 + 3].toInt() and 0xFF)
        }
        addRoundKey(s, rk, round = 0)
        for (round in 1..9) {
            subBytes(s); shiftRows(s); mixColumns(s); addRoundKey(s, rk, round)
        }
        subBytes(s); shiftRows(s); addRoundKey(s, rk, round = 10)
        return ByteArray(16) { i -> (s[i / 4] ushr ((3 - i % 4) * 8) and 0xFF).toByte() }
    }

    private fun keyExpansion(key: ByteArray): IntArray {
        val w = IntArray(44)
        for (i in 0..3) {
            w[i] = (key[i * 4].toInt() and 0xFF shl 24) or
                    (key[i * 4 + 1].toInt() and 0xFF shl 16) or
                    (key[i * 4 + 2].toInt() and 0xFF shl 8) or
                    (key[i * 4 + 3].toInt() and 0xFF)
        }
        for (i in 4..43) {
            var temp = w[i - 1]
            if (i % 4 == 0) temp = subWord(rotWord(temp)) xor RCON[i / 4 - 1]
            w[i] = w[i - 4] xor temp
        }
        return w
    }

    private fun rotWord(w: Int): Int = (w shl 8) or (w ushr 24)

    private fun subWord(w: Int): Int =
        (SBOX[w ushr 24 and 0xFF] shl 24) or
                (SBOX[w ushr 16 and 0xFF] shl 16) or
                (SBOX[w ushr 8 and 0xFF] shl 8) or
                (SBOX[w and 0xFF])

    private fun subBytes(s: IntArray) {
        for (i in 0..3) s[i] = subWord(s[i])
    }

    private fun shiftRows(s: IntArray) {
        val b =
            Array(4) { row -> ByteArray(4) { col -> (s[col] ushr ((3 - row) * 8) and 0xFF).toByte() } }
        for (row in 1..3) b[row] = ByteArray(4) { col -> b[row][(col + row) % 4] }
        for (col in 0..3) {
            s[col] = (b[0][col].toInt() and 0xFF shl 24) or
                    (b[1][col].toInt() and 0xFF shl 16) or
                    (b[2][col].toInt() and 0xFF shl 8) or
                    (b[3][col].toInt() and 0xFF)
        }
    }

    private fun mixColumns(s: IntArray) {
        for (i in 0..3) {
            val b0 = s[i] ushr 24 and 0xFF;
            val b1 = s[i] ushr 16 and 0xFF
            val b2 = s[i] ushr 8 and 0xFF;
            val b3 = s[i] and 0xFF
            val x0 = xtime(b0);
            val x1 = xtime(b1)
            val x2 = xtime(b2);
            val x3 = xtime(b3)
            s[i] = ((x0 xor x1 xor b1 xor b2 xor b3) shl 24) or
                    ((b0 xor x1 xor x2 xor b2 xor b3) shl 16) or
                    ((b0 xor b1 xor x2 xor x3 xor b3) shl 8) or
                    (b0 xor b1 xor b2 xor x3 xor x0)
        }
    }

    private fun xtime(b: Int): Int = if (b and 0x80 == 0) b shl 1 else (b shl 1 xor 0x1b) and 0xFF

    private fun addRoundKey(s: IntArray, rk: IntArray, round: Int) {
        for (i in 0..3) s[i] = s[i] xor rk[round * 4 + i]
    }
}