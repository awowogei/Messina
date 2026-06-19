package messina.sensors

import com.juul.kable.Peripheral
import com.juul.kable.toIdentifier

internal actual suspend fun acquirePeripheral(
    address: ByteArray,
    advertisedName: String?
): Peripheral {
    val mac = address.joinToString(":") { it.toHexString() }.uppercase()
    return Peripheral(mac.toIdentifier()) {
        autoConnectIf { true }
    }
}
