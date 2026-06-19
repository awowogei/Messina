@file:OptIn(ExperimentalForeignApi::class)

package messina.sensors

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreNFC.NFCISO15693TagProtocol
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

actual class Tag(val tag: NFCISO15693TagProtocol) {
    // Common code expects Android's order
    actual val id: ByteArray = tag.identifier.toByteArray().reversedArray()

    // Mirrors Android's NfcV.transceive: `data` is a raw ISO15693 frame of flags byte,
    // command code and payload, and the response flags byte is put back in front of the
    // response so the common parsing sees the same bytes on both platforms.
    actual suspend fun transceive(data: ByteArray): ByteArray =
        suspendCoroutine { continuation ->
            tag.sendRequestWithFlag(
                flags = data[0].toLong(),
                commandCode = (data[1].toInt() and 0xFF).toLong(),
                data = data.copyOfRange(2, data.size).toNSData(),
            ) { responseFlag, responseData, error ->
                if (error != null) {
                    continuation.resumeWithException(
                        RuntimeException(error.localizedDescription)
                    )
                } else {
                    continuation.resume(
                        byteArrayOf(responseFlag.toByte()) +
                                (responseData?.toByteArray() ?: ByteArray(0))
                    )
                }
            }
        }
}

internal fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned {
        NSData.create(bytes = it.addressOf(0), length = size.toULong())
    }
}

internal fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) {
        out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    }
    return out
}
