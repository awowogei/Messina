package messina.cryptography

import java.security.MessageDigest


private val sha256Digest = MessageDigest.getInstance("SHA-256")

actual fun sha256(data: ByteArray): ByteArray {
    return sha256Digest.digest(data)
}