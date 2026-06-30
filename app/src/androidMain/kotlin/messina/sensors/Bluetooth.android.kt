package messina.sensors

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.juul.kable.Peripheral
import com.juul.kable.toIdentifier
import messina.ContextStore.Companion.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private fun macOf(address: ByteArray) = address.joinToString(":") { it.toHexString() }.uppercase()

internal actual suspend fun acquirePeripheral(
    address: ByteArray,
    advertisedName: String?
): Peripheral {
    return Peripheral(macOf(address).toIdentifier()) {
        autoConnectIf { true }
    }
}

/**
 * Create an OS bond and suspend until it actually completes — mirroring Juggluco, which
 * starts data collection only from its BOND_BONDED callback. Kable 0.43.1 has no bonding
 * support (the upstream PR is unmerged), so we drive createBond() directly and track
 * completion via the ACTION_BOND_STATE_CHANGED broadcast (the same signal that PR uses).
 */
@SuppressLint("MissingPermission")
internal actual suspend fun bondDevice(address: ByteArray): Boolean {
    val manager = ApplicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val device = manager?.adapter?.getRemoteDevice(macOf(address)) ?: return false
    if (device.bondState == BluetoothDevice.BOND_BONDED) return true

    return suspendCancellableCoroutine { continuation ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (device.bondState) {
                    BluetoothDevice.BOND_BONDED -> finish(true)
                    BluetoothDevice.BOND_NONE -> finish(false)
                    else -> {} // BOND_BONDING: keep waiting
                }
            }

            private fun finish(bonded: Boolean) {
                runCatching { ApplicationContext.unregisterReceiver(this) }
                if (continuation.isActive) continuation.resume(bonded)
            }
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ApplicationContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            ApplicationContext.registerReceiver(receiver, filter)
        }
        continuation.invokeOnCancellation { runCatching { ApplicationContext.unregisterReceiver(receiver) } }

        // If bonding can't even be started (and isn't already underway), give up.
        if (!device.createBond() && device.bondState != BluetoothDevice.BOND_BONDING) {
            runCatching { ApplicationContext.unregisterReceiver(receiver) }
            if (continuation.isActive) continuation.resume(false)
        }
    }
}
