package messina.cryptography.jpake

import com.ionspin.kotlin.bignum.integer.BigInteger
import messina.cryptography.jpake.P256.EcPoint
import messina.cryptography.sha256

/**
 * Elliptic-curve J-PAKE (RFC 8236) over P-256: a password-authenticated key
 * exchange. Two parties, each holding the same low-entropy [password], exchange
 * ephemeral public keys (each carrying a [Schnorr] proof) and derive a strong
 * shared secret that only matches if the passwords agree.
 *
 * This object covers the protocol's two non-trivial steps. The first-round
 * messages are ordinary Schnorr proofs over the generator and can be produced
 * directly with `Schnorr.prove(P256.generator, ephemeralPrivate, partyId, nonce)`;
 * the [verify][Schnorr.verify] of a peer's first-round message likewise uses the
 * generator as base.
 *
 * Everything that is deployment-specific — party identifiers, nonce sourcing, the
 * password encoding, and any on-the-wire framing — is supplied by the caller.
 */
object EcJpake {

    /**
     * Produce this party's second-round message: a Schnorr proof over the combined
     * base `ourFirstPublic + peerFirstPublic + peerSecondPublic`, proving knowledge
     * of the effective secret `ourSecondPrivate · password (mod n)`. The proof's
     * public point is that base scaled by the effective secret.
     *
     * @param nonce ephemeral scalar in `[1, n)`; explicit so callers can inject a
     *   specific value (e.g. a fixed deployment exponent or a test vector).
     */
    fun secondRoundProof(
        ourFirstPublic: EcPoint,
        peerFirstPublic: EcPoint,
        peerSecondPublic: EcPoint,
        ourSecondPrivate: BigInteger,
        password: BigInteger,
        partyId: ByteArray,
        nonce: BigInteger,
    ): Schnorr.SchnorrProof {
        val base = ourFirstPublic + peerFirstPublic + peerSecondPublic
        val secret = (ourSecondPrivate * password).mod(P256.N)
        return Schnorr.prove(base, secret, partyId, nonce)
    }

    /**
     * Derive the 32-byte shared secret from the peer's second-round public point.
     * The caller takes whatever prefix it needs as a symmetric key.
     *
     * @param peerSecondRoundPublic the public point from the peer's second-round message
     * @param peerSecondPublic the peer's second first-round public key
     */
    fun deriveSharedSecret(
        peerSecondRoundPublic: EcPoint,
        peerSecondPublic: EcPoint,
        ourSecondPrivate: BigInteger,
        password: BigInteger,
    ): ByteArray {
        val effectiveSecret = (ourSecondPrivate * password).mod(P256.N)
        val k = (peerSecondRoundPublic - peerSecondPublic * effectiveSecret) * ourSecondPrivate
        return sha256(P256.scalarToBytes(k.x))
    }
}
