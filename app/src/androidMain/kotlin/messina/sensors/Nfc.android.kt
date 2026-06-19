package messina.sensors

import android.nfc.tech.NfcV

actual class Tag(val tag: NfcV) {
    actual val id: ByteArray = tag.tag.id

    // CoreNFC retries failed frames on its own while Android hands every transient RF
    // error straight to us, even with the tag still in the field. The sensor commands
    // are idempotent, so blind retries are safe and align the platforms.
    actual suspend fun transceive(data: ByteArray): ByteArray {
        var failure: Exception? = null
        repeat(3) {
            try {
                return this.tag.transceive(data)
            } catch (e: Exception) {
                failure = e
            }
        }
        throw failure!!
    }
}

