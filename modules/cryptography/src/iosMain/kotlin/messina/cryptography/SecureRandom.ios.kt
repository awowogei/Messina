@file:OptIn(ExperimentalForeignApi::class)

package messina.cryptography

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

actual fun secureRandomBytes(size: Int): ByteArray {
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    val status = out.usePinned {
        SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0))
    }
    check(status == errSecSuccess) { "SecRandomCopyBytes failed: $status" }
    return out
}
