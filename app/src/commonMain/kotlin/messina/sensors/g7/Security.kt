package messina.sensors.g7

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ionspin.kotlin.bignum.integer.Sign
import messina.cryptography.Ecdsa
import messina.cryptography.aesEcbEncrypt
import messina.cryptography.jpake.EcJpake
import messina.cryptography.jpake.P256
import messina.cryptography.jpake.Schnorr
import messina.cryptography.secureRandomBytes
import messina.cryptography.sha256
import messina.sensors.Sensor

/**
 * Drives the Dexcom G7 EC-JPAKE handshake and the post-handshake challenges,
 * holding all the ephemeral state for one connection. All cryptography is
 * delegated to the general `messina.cryptography` module; this class only adds
 * the G7-specific framing, party tags, and the fixed round-3 nonce.
 */
class Security(sensor: Sensor.G7) {

    // The 4-byte pairing PIN, interpreted as the J-PAKE password.
    private val password = P256.scalarFromBytes(sensor.pin)

    // Two ephemeral key pairs (x1/X1, x2/X2) for the two first-round shares.
    private val first = KeyPair.generate()
    private val second = KeyPair.generate()

    // The sensor's first-round public keys, captured as their proofs arrive.
    private lateinit var sensorFirstPublic: P256.EcPoint
    private lateinit var sensorSecondPublic: P256.EcPoint

    /**
     * The session key (first 16 bytes are the AES key): seeded from the sensor's stored key
     * so the J-PAKE rounds can be skipped on reconnect, and set when a fresh exchange completes.
     */
    var sharedKey: ByteArray? = sensor.sharedKey
        private set

    /** Whether a shared key is already available, letting the J-PAKE rounds be skipped. */
    fun jpakeCompleted(): Boolean = sharedKey != null

    // ---- First round: our two key shares, each with a Schnorr proof ----

    fun round1(): ByteArray = certBytes(proveFirstRound(first))
    fun round2(): ByteArray = certBytes(proveFirstRound(second))

    private fun proveFirstRound(key: KeyPair): Schnorr.SchnorrProof =
        Schnorr.prove(P256.generator, key.privateScalar, G7.CLIENT_PARTY, randomScalar())

    /** Parse and verify a sensor first-round cert; returns false if the proof is invalid. */
    fun receiveSensorRound1(cert: ByteArray): Boolean {
        val proof = parseCert(cert)
        sensorFirstPublic = proof.pubKey
        return Schnorr.verify(P256.generator, proof, G7.SENSOR_PARTY)
    }

    fun receiveSensorRound2(cert: ByteArray): Boolean {
        val proof = parseCert(cert)
        sensorSecondPublic = proof.pubKey
        return Schnorr.verify(P256.generator, proof, G7.SENSOR_PARTY)
    }

    // ---- Second round (G7 "round 3"): the combined-base proof ----

    fun round3(): ByteArray {
        val nonce = BigInteger.parseString(G7.ROUND3_NONCE_HEX, 16)
        val proof = EcJpake.secondRoundProof(
            ourFirstPublic = first.publicPoint,
            peerFirstPublic = sensorFirstPublic,
            peerSecondPublic = sensorSecondPublic,
            ourSecondPrivate = second.privateScalar,
            password = password,
            partyId = G7.CLIENT_PARTY,
            nonce = nonce,
        )
        return certBytes(proof)
    }

    /**
     * Parse the sensor's second-round cert and derive the shared key. Returns false
     * if the sensor's proof is invalid. The G7's own proofs are non-standard, so a
     * caller may choose to derive the key regardless (see [deriveSharedKey]).
     */
    fun receiveSensorRound3(cert: ByteArray): Boolean {
        val proof = parseCert(cert)
        deriveSharedKey(proof.pubKey)
        // TODO: confirm whether real sensors emit verifiable proofs; Juggluco does
        //  not enforce this. See the EC-JPAKE memory note.
        val base = first.publicPoint + second.publicPoint + sensorFirstPublic
        return Schnorr.verify(base, proof, G7.SENSOR_PARTY)
    }

    fun deriveSharedKey(sensorSecondRoundPublic: P256.EcPoint) {
        sharedKey = EcJpake.deriveSharedSecret(
            peerSecondRoundPublic = sensorSecondRoundPublic,
            peerSecondPublic = sensorSecondPublic,
            ourSecondPrivate = second.privateScalar,
            password = password,
        )
    }

    // ---- Post-handshake challenges ----

    /**
     * The AES authentication response (encrypt8AES): the 8-byte [challenge] is
     * doubled to a 16-byte block, AES-encrypted under the session key, and the
     * first 8 bytes are returned.
     */
    fun aesResponse(challenge: ByteArray): ByteArray {
        val key = sharedKey!!.copyOfRange(0, 16)
        val block = challenge.copyOfRange(0, 8) + challenge.copyOfRange(0, 8)
        return aesEcbEncrypt(key, block).copyOfRange(0, 8)
    }

    /**
     * The certificate challenge (getchallenge): sign the SHA-256 of 16 bytes of
     * [input] (skipping its 2-byte header) with the embedded app key, returning
     * the raw 64-byte r‖s signature.
     */
    fun certificateChallenge(input: ByteArray): ByteArray {
        val appKey = BigInteger.parseString(G7.APP_PRIVATE_KEY_HEX, 16)
        val digest = sha256(input.copyOfRange(2, 18))
        return Ecdsa.sign(appKey, digest)
    }

    private fun certBytes(proof: Schnorr.SchnorrProof): ByteArray =
        proof.pubKey.encodeRaw() + proof.v.encodeRaw() + P256.scalarToBytes(proof.r)

    private fun parseCert(cert: ByteArray): Schnorr.SchnorrProof {
        require(cert.size == G7.CERT_SIZE) { "Expected a ${G7.CERT_SIZE}-byte cert" }
        return Schnorr.SchnorrProof(
            pubKey = P256.decodeRaw(cert.copyOfRange(0, 64)),
            v = P256.decodeRaw(cert.copyOfRange(64, 128)),
            r = P256.scalarFromBytes(cert.copyOfRange(128, 160)),
        )
    }

    private class KeyPair(val privateScalar: BigInteger, val publicPoint: P256.EcPoint) {
        companion object {
            fun generate(): KeyPair {
                val d = randomScalar()
                return KeyPair(d, P256.generator * d)
            }
        }
    }

    companion object {
        private fun randomScalar(): BigInteger {
            while (true) {
                val k = BigInteger.fromByteArray(secureRandomBytes(32), Sign.POSITIVE).mod(P256.N)
                if (k != BigInteger.ZERO) return k
            }
        }
    }
}
