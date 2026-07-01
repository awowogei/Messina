@file:OptIn(ExperimentalForeignApi::class)

package messina.cryptography

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCSuccess
import platform.posix.size_tVar

actual fun aesEcbEncrypt(key: ByteArray, data: ByteArray): ByteArray {
    require(data.isNotEmpty()) { "data must not be empty" }
    val out = ByteArray(data.size)
    memScoped {
        val moved = alloc<size_tVar>()
        key.usePinned { keyPin ->
            data.usePinned { dataPin ->
                out.usePinned { outPin ->
                    val status = CCCrypt(
                        kCCEncrypt,
                        kCCAlgorithmAES,
                        kCCOptionECBMode,
                        keyPin.addressOf(0), key.size.convert(),
                        null,
                        dataPin.addressOf(0), data.size.convert(),
                        outPin.addressOf(0), out.size.convert(),
                        moved.ptr,
                    )
                    check(status == kCCSuccess) { "CCCrypt failed: $status" }
                }
            }
        }
    }
    return out
}
