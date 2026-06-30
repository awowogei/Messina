package messina.cryptography

import kotlin.test.Test
import kotlin.test.assertContentEquals

class AesTest {

    private fun hex(s: String) =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    @Test
    fun fips197KnownAnswer() {
        // FIPS-197 Appendix B / C.1 AES-128 single block.
        val key = hex("000102030405060708090a0b0c0d0e0f")
        val plain = hex("00112233445566778899aabbccddeeff")
        val expected = hex("69c4e0d86a7b0430d8cdb78070b4c55a")
        assertContentEquals(expected, aesEcbEncrypt(key, plain))
    }

    @Test
    fun matchesReferenceChallengeBlock() {
        // From ecJPake.cpp's encrypt8AES: the 8-byte challenge 2a404290c4b63b01 is
        // doubled to a 16-byte block, AES-encrypted under the shared key, and its
        // first 8 bytes (13ab13f6975e3082) are the G7 response. The doubling and
        // truncation are caller concerns; this pins the underlying block cipher.
        val key = hex("6f8326744bef03faa520ad9c5cff673f")
        val block = hex("2a404290c4b63b012a404290c4b63b01")
        val out = aesEcbEncrypt(key, block)
        assertContentEquals(hex("13ab13f6975e3082"), out.copyOfRange(0, 8))
    }
}
