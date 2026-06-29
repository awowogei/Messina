package messina.cryptography

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH

@OptIn(ExperimentalForeignApi::class)
actual fun sha1(data: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA1_DIGEST_LENGTH)
    data.usePinned { pinned ->
        digest.usePinned {
            CC_SHA1(pinned.addressOf(0), data.size.convert(), it.addressOf(0))
        }
    }
    return digest.asByteArray()
}
