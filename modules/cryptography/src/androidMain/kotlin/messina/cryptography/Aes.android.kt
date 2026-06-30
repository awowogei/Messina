package messina.cryptography

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

actual fun aesEcbEncrypt(key: ByteArray, data: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/ECB/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    return cipher.doFinal(data)
}
