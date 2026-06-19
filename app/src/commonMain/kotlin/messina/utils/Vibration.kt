package messina.utils

expect class Vibration(timings: LongArray, amplitudes: IntArray, repeat: Boolean = false) {
    fun start()

    companion object {
        fun stop()
    }
}
