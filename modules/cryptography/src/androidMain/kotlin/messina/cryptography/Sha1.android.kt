package messina.cryptography

import java.security.MessageDigest


private val sha1Digest = MessageDigest.getInstance("SHA-1")

actual fun sha1(data: ByteArray): ByteArray {
    return sha1Digest.digest(data)
}
