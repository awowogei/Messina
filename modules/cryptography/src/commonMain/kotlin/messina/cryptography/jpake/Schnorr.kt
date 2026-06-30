package messina.cryptography.jpake

import com.ionspin.kotlin.bignum.integer.BigInteger
import messina.cryptography.jpake.P256.EcPoint
import messina.cryptography.sha256

/**
 * Schnorr non-interactive zero-knowledge proof of knowledge of a discrete log,
 * as used by EC-JPAKE (RFC 8235).
 *
 * A prover holding a secret scalar `x` publishes `pubKey = base·x` together with
 * a [SchnorrProof] that convinces a verifier it knows `x` without revealing it.
 * The proof is bound to a caller-supplied party identifier so the two sides of an
 * exchange produce distinct, non-replayable transcripts. This type is protocol-
 * agnostic: callers choose `base`, the party id, and the nonce.
 */
object Schnorr {

    /**
     * @property pubKey the public point `base·x` the proof is about
     * @property v the ephemeral commitment `base·nonce`
     * @property r the response scalar `nonce − x·c (mod n)`
     */
    class SchnorrProof(
        val pubKey: EcPoint,
        val v: EcPoint,
        val r: BigInteger,
    )

    /**
     * Prove knowledge of [secret] (with respect to [base]) bound to [partyId],
     * using the ephemeral [nonce]. The nonce must be a fresh random scalar in
     * `[1, n)` for real use; it is an explicit parameter so callers can inject a
     * specific value (e.g. deterministic test vectors).
     */
    fun prove(base: EcPoint, secret: BigInteger, partyId: ByteArray, nonce: BigInteger): SchnorrProof {
        val pubKey = base * secret
        val v = base * nonce
        val c = challenge(base, v, pubKey, partyId)
        val r = (nonce - secret * c).mod(P256.N)
        return SchnorrProof(pubKey, v, r)
    }

    /** Verify [proof] against [base] and [partyId]: checks `base·r + pubKey·c == v`. */
    fun verify(base: EcPoint, proof: SchnorrProof, partyId: ByteArray): Boolean {
        val c = challenge(base, proof.v, proof.pubKey, partyId)
        val reconstructed = base * proof.r + proof.pubKey * c
        return reconstructed == proof.v
    }

    /**
     * The Fiat–Shamir challenge `c = SHA-256(transcript) mod n`, where the
     * transcript is `len‖base ‖ len‖v ‖ len‖pubKey ‖ len‖partyId`: each point in
     * its 65-byte uncompressed form, every field preceded by a 4-byte big-endian
     * length. This layout is the one EC-JPAKE peers agree on.
     */
    internal fun challenge(base: EcPoint, v: EcPoint, pubKey: EcPoint, partyId: ByteArray): BigInteger {
        val pointLen = 65
        val buffer = ByteArray(4 * 4 + 3 * pointLen + partyId.size)
        var offset = 0
        for (point in listOf(base, v, pubKey)) {
            offset = putLength(buffer, offset, pointLen)
            point.encodeUncompressed().copyInto(buffer, offset)
            offset += pointLen
        }
        offset = putLength(buffer, offset, partyId.size)
        partyId.copyInto(buffer, offset)
        return P256.scalarFromBytes(sha256(buffer)).mod(P256.N)
    }

    private fun putLength(buffer: ByteArray, offset: Int, value: Int): Int {
        buffer[offset] = (value ushr 24).toByte()
        buffer[offset + 1] = (value ushr 16).toByte()
        buffer[offset + 2] = (value ushr 8).toByte()
        buffer[offset + 3] = value.toByte()
        return offset + 4
    }
}
