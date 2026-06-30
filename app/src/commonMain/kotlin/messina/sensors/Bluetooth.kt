package messina.sensors

import com.juul.kable.Characteristic
import com.juul.kable.Peripheral
import com.juul.kable.State
import messina.logging.error
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

// MAC address for Android, name for IOS
internal expect suspend fun acquirePeripheral(
    address: ByteArray,
    advertisedName: String?
): Peripheral

// Create an OS-level bond with the device (Dexcom G7 requires bonding). On iOS bonding is
// established implicitly during pairing, so this is a no-op there.
internal expect suspend fun bondDevice(address: ByteArray): Boolean

suspend fun sensorBluetoothConnection(
    sensorId: SensorId,
    address: ByteArray,
    advertisedName: String?,
    onDisconnect: (State.Disconnected.Status?) -> Unit = {},
    session: suspend CoroutineScope.(Peripheral) -> Unit,
) {
    val peripheral = acquirePeripheral(address, advertisedName)

    // A subset of multi-packet operations on the libre3 must be handled serially.
    // To avoid race conditions but keep the async, everything is done on one thread.
    val context = Dispatchers.Default.limitedParallelism(1)

    try {
        while (true) {
            try {
                val connection = peripheral.connect()
                SensorEvents.sensorConnected.send(sensorId)
                connection.launch(context) {
                    try {
                        session(peripheral)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error { "Sensor session failed: $e" }
                        peripheral.disconnect()
                    }
                }

                val state = peripheral.state
                    .first { it is State.Disconnected } as State.Disconnected
                SensorEvents.sensorDisconnected.send(sensorId)
                onDisconnect(state.status)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error { "Connection attempt failed: $e" }
            }
            delay(30.seconds)
        }
    } finally {
        peripheral.close()
    }
}

suspend fun CoroutineScope.subscribe(
    peripheral: Peripheral,
    characteristic: Characteristic,
    handler: suspend (ByteArray) -> Unit = {},
): Job {
    val subscribed = CompletableDeferred<Unit>()
    val job = launch {
        try {
            peripheral
                .observe(characteristic) { subscribed.complete(Unit) }
                .collect { handler(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error { "Subscription to ${characteristic.characteristicUuid} failed: $e" }
            peripheral.disconnect()
        }
    }
    subscribed.await()
    return job
}
