package messina.sensors

import androidx.compose.runtime.mutableStateListOf
import messina.Glucose
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.Instant

class GlucoseReadings(private val maxAge: Duration? = null) {
    private val readings = mutableStateListOf<GlucoseReading>()

    val raw: List<GlucoseReading> get() = readings

    private fun ema(readings: List<GlucoseReading>): List<GlucoseReading> {
        if (readings.isEmpty()) return readings
        val decay = 0.7
        var value = readings.first().glucose.toMgDl()
        var prev = readings.first().time
        return readings.map { (time, glucose) ->
            val alpha = 1.0 - decay.pow((time - prev).toDouble(DurationUnit.MINUTES))
            value = alpha * glucose.toMgDl() + (1 - alpha) * value
            prev = time
            GlucoseReading(time, Glucose.fromMgDl(value))
        }
    }

    fun smoothed(): List<GlucoseReading> = ema(readings)

    fun add(reading: GlucoseReading) {
        readings.add(reading)
        trim()
    }

    fun replaceReadings(replacement: List<GlucoseReading>) {
        readings.clear()
        readings.addAll(replacement)
        trim()
    }

    // Drops everything older than this.maxAge before the newest reading.
    private fun trim() {
        val maxAge = maxAge ?: return
        val newest = readings.lastOrNull()?.time ?: return
        readings.removeAll { it.time <= newest - maxAge }
    }

    fun trend(at: Instant? = null): Glucose? {
        val end = if (at == null) readings.size else readings.indexOfLast { it.time <= at } + 1
        if (end < 2) return null
        val window = ema(readings.subList(maxOf(0, end - 15), end))
        val (lastTime, lastGlucose) = window[window.size - 1]
        val (prevTime, prevGlucose) = window[window.size - 2]
        val mins = (lastTime - prevTime).toDouble(DurationUnit.MINUTES)
        if (mins <= 0.0 || mins > 5.0) return null
        return Glucose.fromMgDl((lastGlucose.toMgDl() - prevGlucose.toMgDl()) / mins)
    }
}

data class GlucoseReading(
    val time: Instant,
    val glucose: Glucose,
)