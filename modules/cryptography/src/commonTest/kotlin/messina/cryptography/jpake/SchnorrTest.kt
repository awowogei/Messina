package messina.cryptography.jpake

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchnorrTest {

    // The party identifier and point framing are caller concerns (here standing in
    // for the G7 layer), not part of the general crypto module.
    private val peerPartyId = byteArrayOf(0x37, 0x56, 0x27, 0x67, 0x56, 0x27)
    private val clientPartyId = byteArrayOf(0x63, 0x6c, 0x69, 0x65, 0x6e, 0x74) // "client"

    // Public point (A) and commitment (V) from two real proofs captured in
    // Juggluco's ecJPake.cpp testmulti(), framed as rawX‖rawY (64 bytes each).
    private val packby1 = hex(
        "7ccc36e133643a357a1ffba9a2a266246ed504697f4ba03e6b2f4e7b62b4bb88" +
            "b47e39052e0c11f525f344d6b3b0924f3d33cc25775b8a55cdc6117a518cff26" +
            "2cc2267b156f5bfc4bbbb0f93bf1f9ce09e17d621398c2b36e0acd772e713a77" +
            "b14e175ae07b943411918fcfed480066a47c06f4c25b01cb20b148c036819f4a" +
            "fed6f7aaf7dfcfbcf0965ae8e11900022e9298b6a546b14769cbfee1c77b9170",
    )
    private val packby2 = hex(
        "0b7d5bc678f018f2d0d86ef4b982813e7f501c0d142975efda08e539dbf8e04d" +
            "0ab6fd611dbcfe1bafd46a2fb806640c75872a2186b747a6afb8bea721e381bf" +
            "823e7be9be45757c219f6a9f0f5d2d9de01cd05d3d72c911d0bae22c48ef0571" +
            "7ad3fc962bc47915f983285c4b78174be1d63151725dec834c4cf0769b44f836" +
            "7dffb961d2a174bf3f8148707e5dae974adffb3f41c3e378a8c44d8666168ef3",
    )

    /**
     * Known-answer for the Fiat–Shamir challenge: the reference C++ (ecJPake.cpp,
     * run against libcrypto) hashes base‖V‖pubKey‖partyId to these exact digests.
     * Reproducing them byte-for-byte pins the transcript encoding.
     */
    @Test
    fun challengeMatchesReferenceVectors() {
        assertEquals(
            "ea27326c81dfd3cadfe383c149aca75a1c61ea2decbd35e172a138fc2c83b378",
            challengeHex(packby1),
        )
        assertEquals(
            "fc7993bd95dd6bbf32359e6f1518cd0121e93af458355513bed2580f208f8578",
            challengeHex(packby2),
        )
    }

    @Test
    fun proveProducesVerifiableProof() {
        val secret = BigInteger.parseString(
            "54fd40eafbe36079e92056a79b7b69c672fb35452179a3f3a30c00402c4a71c3", 16,
        )
        val nonce = BigInteger.parseString(
            "fbc271b637e2491e45a4179ed33665c506a1e0a1d350f5af0f96370695fdc323", 16,
        )
        val proof = Schnorr.prove(P256.generator, secret, clientPartyId, nonce)
        assertTrue(Schnorr.verify(P256.generator, proof, clientPartyId))
    }

    @Test
    fun verifyRejectsWrongPartyId() {
        val secret = BigInteger.fromInt(12345)
        val proof = Schnorr.prove(P256.generator, secret, clientPartyId, BigInteger.fromInt(67890))
        assertFalse(Schnorr.verify(P256.generator, proof, peerPartyId))
    }

    @Test
    fun verifyRejectsTamperedResponse() {
        val secret = BigInteger.fromInt(12345)
        val proof = Schnorr.prove(P256.generator, secret, clientPartyId, BigInteger.fromInt(67890))
        val tampered = Schnorr.SchnorrProof(proof.pubKey, proof.v, (proof.r + BigInteger.ONE).mod(P256.N))
        assertFalse(Schnorr.verify(P256.generator, tampered, clientPartyId))
    }

    private fun challengeHex(cert: ByteArray): String {
        val pubKey = P256.decodeRaw(cert.copyOfRange(0, 64))
        val v = P256.decodeRaw(cert.copyOfRange(64, 128))
        return P256.scalarToBytes(Schnorr.challenge(P256.generator, v, pubKey, peerPartyId)).toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
}
