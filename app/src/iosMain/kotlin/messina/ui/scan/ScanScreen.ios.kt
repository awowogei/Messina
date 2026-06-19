package messina.ui.scan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import messina.sensors.Tag
import messina.sensors.read
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import platform.CoreNFC.NFCPollingISO15693
import platform.CoreNFC.NFCReaderSessionInvalidationErrorSessionTimeout
import platform.CoreNFC.NFCReaderSessionInvalidationErrorUserCanceled
import platform.CoreNFC.NFCTagProtocol
import platform.CoreNFC.NFCTagReaderSession
import platform.CoreNFC.NFCTagReaderSessionDelegateProtocol
import platform.Foundation.NSError
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun NfcScan(errorMessage: MutableStateFlow<String?>, onClose: () -> Unit) {
    val delegate = remember(errorMessage) { ScanDelegate(errorMessage, onClose) }
    DisposableEffect(delegate) {
        val session = if (NFCTagReaderSession.readingAvailable()) {
            NFCTagReaderSession(
                pollingOption = NFCPollingISO15693,
                delegate = delegate,
                queue = null,
            ).also {
                it.alertMessage = "Hold the top of your phone over the sensor"
                it.beginSession()
            }
        } else {
            errorMessage.value = "NFC is not available on this device"
            null
        }
        onDispose { session?.invalidateSession() }
    }
}

class ScanDelegate(
    val errorMessage: MutableStateFlow<String?>,
    val onClose: () -> Unit,
) : NSObject(), NFCTagReaderSessionDelegateProtocol {

    @OptIn(ExperimentalForeignApi::class)
    override fun tagReaderSession(session: NFCTagReaderSession, didDetectTags: List<*>) {
        // A new tag encounter starts fresh; don't leave an error from a previous attempt
        errorMessage.value = null

        val tag = (didDetectTags.firstOrNull() as? NFCTagProtocol)?.asNFCISO15693Tag()
        if (tag == null) {
            session.restartPolling()
            return
        }

        session.connectToTag(tag) { error: NSError? ->
            if (error != null) {
                errorMessage.value = error.localizedDescription
                session.restartPolling()
                return@connectToTag
            }

            // read() suspends on each command; completions arrive on the session queue,
            // which stays free because nothing here blocks it
            GlobalScope.launch {
                try {
                    Tag(tag).read()
                    session.invalidateSession()
                } catch (e: Exception) {
                    errorMessage.value = e.message ?: "Failed to read sensor"
                    session.restartPolling()
                }
            }
        }
    }

    override fun tagReaderSession(session: NFCTagReaderSession, didInvalidateWithError: NSError) {
        when (didInvalidateWithError.code) {
            // An invalidated session can never be restarted, so once the system sheet is
            // gone the screen has no purpose. Canceled covers the user dismissing the
            // sheet as well as our own invalidateSession() after a successful read.
            NFCReaderSessionInvalidationErrorUserCanceled.toLong(),
            NFCReaderSessionInvalidationErrorSessionTimeout.toLong(),
                -> dispatch_async(dispatch_get_main_queue()) { onClose() }

            else -> errorMessage.value = didInvalidateWithError.localizedDescription
        }
    }
}
