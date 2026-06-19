package messina.ui.scan

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcV
import android.os.Bundle
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import messina.sensors.read
import messina.utils.Vibration
import messina.logging.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

@Composable
actual fun NfcScan(
    errorMessage: MutableStateFlow<String?>,
    // Reader mode has no system UI of its own that could end the scan, so this is unused
    onClose: () -> Unit,
) {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val adapter = NfcAdapter.getDefaultAdapter(activity)
        adapter?.enableReaderMode(
            activity, ScanCallbacks(errorMessage),
            NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            Bundle(),
        )
        onDispose { adapter?.disableReaderMode(activity) }
    }
}

class ScanCallbacks(val errorMessage: MutableStateFlow<String?>) : NfcAdapter.ReaderCallback {
    override fun onTagDiscovered(tag: Tag) {
        // A new tag encounter starts fresh; don't leave an error from a previous attempt
        errorMessage.value = null

        val nfcvTag = NfcV.get(tag)

        if (nfcvTag == null) {
            info { "Scanned non-NfcV tag" }
            return
        }

        try {
            nfcvTag.connect()
        } catch (e: Exception) {
            errorMessage.value = e.message ?: "Failed to connect to sensor"
            return
        }

        try {
            runBlocking { messina.sensors.Tag(nfcvTag).read() }
        } catch (e: Exception) {
            // Most commonly TagLostException from pulling the phone away mid-read
            errorMessage.value = e.message ?: "Failed to read sensor"
        }

        Vibration(longArrayOf(0, 200), intArrayOf(0, 255)).start()
    }
}
