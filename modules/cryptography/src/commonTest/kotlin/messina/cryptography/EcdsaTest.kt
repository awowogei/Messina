package messina.cryptography

import com.ionspin.kotlin.bignum.integer.BigInteger
import messina.cryptography.jpake.P256
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EcdsaTest {

    // RFC 6979 Appendix A.2.5 — P-256 key.
    private val d = bn("C9AFA9D845BA75166B5C215767B1D6934E50C3DB36E89B127B8A622B120F6721")
    private val publicKey = P256.generator * d

    @Test
    fun signWithNonceMatchesRfc6979Vector() {
        // SHA-256 over "sample", deterministic nonce k, and the resulting (r, s).
        val digest = sha256("sample".encodeToByteArray())
        val k = bn("A6E3C57DD01ABE90086538398355DD4C3B17AA873382B0F24D6129493D8AAD60")
        val expectedR = "efd48b2aacb6a8fd1140dd9cd45e81d69d2c877b56aaf991c34d0ea84eaf3716"
        val expectedS = "f7cb1c942d657c41d436c7a1b6e29f65f3e900dbb9aff4064dc4ab2f843acda8"

        val sig = Ecdsa.signWithNonce(d, digest, k)
        assertNotNull(sig)
        assertTrue(sig.toHex() == expectedR + expectedS, "got ${sig.toHex()}")
    }

    @Test
    fun verifyAcceptsRfc6979Vector() {
        val digest = sha256("sample".encodeToByteArray())
        val sig = hex(
            "efd48b2aacb6a8fd1140dd9cd45e81d69d2c877b56aaf991c34d0ea84eaf3716" +
                "f7cb1c942d657c41d436c7a1b6e29f65f3e900dbb9aff4064dc4ab2f843acda8",
        )
        assertTrue(Ecdsa.verify(publicKey, digest, sig))
    }

    @Test
    fun signThenVerifyRoundTrips() {
        val digest = sha256("a different message".encodeToByteArray())
        val sig = Ecdsa.sign(d, digest)
        assertTrue(Ecdsa.verify(publicKey, digest, sig))
    }

    @Test
    fun verifyRejectsWrongDigest() {
        val sig = Ecdsa.sign(d, sha256("one".encodeToByteArray()))
        assertFalse(Ecdsa.verify(publicKey, sha256("two".encodeToByteArray()), sig))
    }

    @Test
    fun verifyRejectsWrongKey() {
        val digest = sha256("msg".encodeToByteArray())
        val sig = Ecdsa.sign(d, digest)
        val otherKey = P256.generator * bn("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        assertFalse(Ecdsa.verify(otherKey, digest, sig))
    }

    private fun bn(h: String) = BigInteger.parseString(h, 16)
    private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    private fun hex(s: String) =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
}
