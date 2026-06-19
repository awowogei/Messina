package messina.sensors

import messina.sensors.libre3.Libre3

expect class Tag {
    val id: ByteArray

    suspend fun transceive(data: ByteArray): ByteArray
}

suspend fun Tag.read() {
    if (this.id.size == 8 && this.id[7].toInt() == -32) {
        Libre3.initialize(this)
    } else {
        throw IllegalArgumentException("Not a supported sensor")
    }
}
