package messina.cryptography.jpake

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class P256Test {

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun generatorIsOnCurve() {
        val g = P256.generator
        // y^2 == x^3 + a*x + b (mod p)
        val lhs = (g.y * g.y).mod(P256.P)
        val rhs = (g.x * g.x * g.x + P256.A * g.x + P256.B).mod(P256.P)
        assertEquals(lhs, rhs)
    }

    @Test
    fun scalarMulByOneIsIdentity() {
        val g = P256.generator
        assertEquals(g, g * BigInteger.ONE)
    }

    @Test
    fun scalarMulByOrderIsInfinity() {
        assertTrue((P256.generator * P256.N).isInfinity)
    }

    @Test
    fun doublingMatchesAddition() {
        val g = P256.generator
        assertEquals(g.double(), g + g)
    }

    @Test
    fun nistScalarMulKnownAnswer() {
        // NIST P-256 test vector: k=2 * G
        // From SEC2 / standard references.
        val k = BigInteger.fromInt(2)
        val r = P256.generator * k
        assertEquals(
            "7cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978",
            r.x.toByteArray32Hex(),
        )
        assertEquals(
            "07775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1",
            r.y.toByteArray32Hex(),
        )
    }

    @Test
    fun nistScalarMul3GKnownAnswer() {
        // 3*G exercises addition of two distinct non-infinity points (G + 2G).
        val r = P256.generator * BigInteger.fromInt(3)
        assertEquals(
            "5ecbe4d1a6330a44c8f7ef951d4bf165e6c6b721efada985fb41661bc6e7fd6c",
            r.x.toByteArray32Hex(),
        )
        assertEquals(
            "8734640c4998ff7e374b06ce1a64a2ecd82ab036384fb83d9a79b127a27d5032",
            r.y.toByteArray32Hex(),
        )
    }

    @Test
    fun encodeDecodeRawRoundTrips() {
        val p = P256.generator * BigInteger.fromInt(7)
        val decoded = P256.decodeRaw(p.encodeRaw())
        assertEquals(p, decoded)
    }

    @Test
    fun encodeDecodeUncompressedRoundTrips() {
        val p = P256.generator * BigInteger.fromInt(7)
        val decoded = P256.decodeUncompressed(p.encodeUncompressed())
        assertEquals(p, decoded)
        assertEquals(0x04.toByte(), p.encodeUncompressed()[0])
    }

    private fun BigInteger.toByteArray32Hex(): String {
        val raw = toByteArray()
        val fixed = when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32).also { raw.copyInto(it, 32 - raw.size) }
        }
        return fixed.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
