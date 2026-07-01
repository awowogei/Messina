package messina.cryptography.jpake

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals

class EcJpakeTest {

    private val clientPartyId = byteArrayOf(0x63, 0x6c, 0x69, 0x65, 0x6e, 0x74) // "client"

    // Inputs from Juggluco's ecJPake.cpp testmulti(), reproduced exactly.
    private val ourSecondPrivate = bn("95ae54cd1f1542b9aa55df0b246ec9b9acd41668da8ed3c13424907948a9d18f")
    private val password = bn("31313535") // "1155"
    private val round2Nonce = bn("fbc971b837e9491e45a4179ed33865c508a1e0a1d350f5af0f96370695fdc393")

    private val ourFirstPublic =
        P256.generator * bn("54fd40eafbe36079e92056a79b7b69c672fb35452179a3f3a30c00402c4a71c3")
    private val peerFirstPublic = P256.decodeRaw(packby1.copyOfRange(0, 64))
    private val peerSecondPublic = P256.decodeRaw(packby2.copyOfRange(0, 64))

    /** The full 160-byte second-round cert (A‖gv‖proof) must match the reference. */
    @Test
    fun secondRoundProofMatchesReference() {
        val proof = EcJpake.secondRoundProof(
            ourFirstPublic, peerFirstPublic, peerSecondPublic,
            ourSecondPrivate, password, clientPartyId, round2Nonce,
        )
        val expected = hex(
            // A (rawX‖rawY)
            "a4dcfae2e05f847314eb35ec93fe0343371ce40badedf3428f4ffaa733edc376" +
                "2d3e0d1c037246c4b6881849bef46d274a3ad9a73ea262a3944a5db066019ea1" +
                // gv (rawX‖rawY)
                "05ee9db54bff6826524622c1ece5aa2451079220ca11b1f9a6b2d81fe398eb22" +
                "9c36c16c54829e066b47c6c9d4ec7f928bb14530f18b792f2e79e3bb3197788d" +
                // proof
                "5d7b73db10dc19151e937aff97426d43837fe3b1a95e4080648ee1c49fd00559",
        )
        val actual = proof.pubKey.encodeRaw() + proof.v.encodeRaw() + P256.scalarToBytes(proof.r)
        assertContentEquals(expected, actual)
    }

    /** SHA-256(K.x) must match the reference's mkSharedKey output. */
    @Test
    fun deriveSharedSecretMatchesReference() {
        // Loopback self-test, as in testmulti: the "peer" second-round point is the
        // one we just produced.
        val ourSecondRoundPublic = EcJpake.secondRoundProof(
            ourFirstPublic, peerFirstPublic, peerSecondPublic,
            ourSecondPrivate, password, clientPartyId, round2Nonce,
        ).pubKey

        val shared = EcJpake.deriveSharedSecret(
            peerSecondRoundPublic = ourSecondRoundPublic,
            peerSecondPublic = peerSecondPublic,
            ourSecondPrivate = ourSecondPrivate,
            password = password,
        )
        assertEquals(
            "6f8326744bef03faa520ad9c5cff673fa7bf9ccb9aa8f2a43d6de263c4ed7708",
            shared.toHex(),
        )
    }

    /** Two parties completing the exchange with the same password agree on the key. */
    @Test
    fun matchingPasswordsYieldSameSecret() {
        val g = P256.generator
        val pin = BigInteger.fromInt(2468)

        // Party A's two ephemeral keys, Party B's two ephemeral keys.
        val a1 = BigInteger.fromInt(11111); val aPub1 = g * a1
        val a2 = BigInteger.fromInt(22222); val aPub2 = g * a2
        val b1 = BigInteger.fromInt(33333); val bPub1 = g * b1
        val b2 = BigInteger.fromInt(44444); val bPub2 = g * b2

        val aRound2 = EcJpake.secondRoundProof(aPub1, bPub1, bPub2, a2, pin, byteArrayOf(1), BigInteger.fromInt(55555)).pubKey
        val bRound2 = EcJpake.secondRoundProof(bPub1, aPub1, aPub2, b2, pin, byteArrayOf(2), BigInteger.fromInt(66666)).pubKey

        val aShared = EcJpake.deriveSharedSecret(bRound2, bPub2, a2, pin)
        val bShared = EcJpake.deriveSharedSecret(aRound2, aPub2, b2, pin)
        assertContentEquals(aShared, bShared)
    }

    private fun bn(h: String) = BigInteger.parseString(h, 16)
    private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    companion object {
        private fun hex(s: String) =
            ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

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
    }
}
