package messina.cryptography

import com.ionspin.kotlin.bignum.integer.BigInteger
import messina.cryptography.jpake.P256
import messina.cryptography.jpake.P256.EcPoint

/**
 * ECDSA over P-256, producing and checking signatures in the raw `r || s` form
 * (two 32-byte big-endian scalars).
 *
 * Keys are bare scalars/points: the private key is the secret scalar `d`, the
 * public key is the point `G·d`. Callers that hold keys in other encodings
 * convert at the boundary.
 */
object Ecdsa {

    private val N = P256.N

    /** Sign [digest] with [privateKey], using a fresh random nonce. Returns `r || s` (64 bytes). */
    fun sign(privateKey: BigInteger, digest: ByteArray): ByteArray {
        while (true) {
            val k = randomScalar()
            val sig = signWithNonce(privateKey, digest, k)
            if (sig != null) return sig
        }
    }

    /** Verify a raw `r || s` [signature] of [digest] under [publicKey] (= `G·d`). */
    fun verify(publicKey: EcPoint, digest: ByteArray, signature: ByteArray): Boolean {
        require(signature.size == 64) { "Expected a 64-byte r||s signature" }
        val r = P256.scalarFromBytes(signature.copyOfRange(0, 32))
        val s = P256.scalarFromBytes(signature.copyOfRange(32, 64))
        if (r <= BigInteger.ZERO || r >= N || s <= BigInteger.ZERO || s >= N) return false

        val z = leftmostBits(digest)
        val sInv = s.modInverse(N)
        val u1 = (z * sInv).mod(N)
        val u2 = (r * sInv).mod(N)
        val point = P256.generator * u1 + publicKey * u2
        if (point.isInfinity) return false
        return point.x.mod(N) == r
    }

    /**
     * Sign with a caller-supplied nonce [k] (in `[1, n)`). Returns null if this
     * nonce yields a degenerate signature, so the random path can retry. Exposed
     * for deterministic testing against known-answer vectors.
     */
    internal fun signWithNonce(privateKey: BigInteger, digest: ByteArray, k: BigInteger): ByteArray? {
        val r = (P256.generator * k).x.mod(N)
        if (r.isZero()) return null
        val z = leftmostBits(digest)
        val s = (k.modInverse(N) * (z + r * privateKey)).mod(N)
        if (s.isZero()) return null
        return P256.scalarToBytes(r) + P256.scalarToBytes(s)
    }

    /** The leftmost `bitlen(n)` bits of [digest] as an integer (FIPS 186 bits2int). */
    private fun leftmostBits(digest: ByteArray): BigInteger {
        val nBits = 256
        var z = P256.scalarFromBytes(digest)
        val excess = digest.size * 8 - nBits
        if (excess > 0) z = z.shr(excess)
        return z
    }

    private fun randomScalar(): BigInteger {
        while (true) {
            val k = P256.scalarFromBytes(secureRandomBytes(32)).mod(N)
            if (!k.isZero()) return k
        }
    }
}
