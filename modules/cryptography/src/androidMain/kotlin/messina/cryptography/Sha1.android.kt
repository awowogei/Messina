package messina.cryptography

import java.security.MessageDigest

actual fun sha1(data: ByteArray): ByteArray {
    return MessageDigest.getInstance("SHA-1").digest(data)
}