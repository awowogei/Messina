package messina.sensors

import com.juul.kable.Filter
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import messina.logging.info
import kotlinx.coroutines.flow.first
import platform.Foundation.NSUserDefaults
import kotlin.uuid.Uuid

internal actual suspend fun acquirePeripheral(
    address: ByteArray,
    advertisedName: String?
): Peripheral {
    // Once a peripheral has been matched by name its identifier is cached so reconnects can skip
    // scanning entirely. The identifier is local to this app+device, which is why the cache key is
    // derived from the sensor's stable MAC address.
    val identifierKey = "ble-peripheral-${address.toHexString()}"
    val defaults = NSUserDefaults.standardUserDefaults

    defaults.stringForKey(identifierKey)?.let { cached ->
        try {
            info { "Connecting to known peripheral $cached" }
            return Peripheral(Uuid.parse(cached))
        } catch (e: NoSuchElementException) {
            info { "Known peripheral $cached not found, scanning again" }
        }
    }

    requireNotNull(advertisedName) { "No advertised name and no cached identifier, cannot connect" }

    info { "Scanning for '$advertisedName'" }
    val advertisement = Scanner {
        filters {
            match { name = Filter.Name.Exact(advertisedName) }
        }
    }.advertisements.first()

    defaults.setObject(advertisement.identifier.toString(), forKey = identifierKey)
    return Peripheral(advertisement)
}
