package messina.ui.main

import messina.Glucose
import messina.sensors.GlucoseReading
import messina.sensors.SensorId
import messina.sensors.Sensors
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private fun previewReadings(
    id: SensorId,
    seed: Int,
    start: Double,
    fromMinutesAgo: Int = 360,
    toMinutesAgo: Int = 0,
): List<GlucoseReading> {
    val rng = Random(seed)
    var mgdl = start
    val now = Clock.System.now()
    return (fromMinutesAgo downTo toMinutesAgo).map { minutesAgo ->
        mgdl = (mgdl + rng.nextDouble() * 6 - 3).coerceIn(65.0, 220.0)
        GlucoseReading(id, now - minutesAgo.minutes, Glucose.fromMgDl(mgdl))
    }
}

// A MainState pre-populated with synthetic readings for every sensor currently registered in
// [Sensors] (see Sensors.addDummySensors). loadData is suppressed so the constructed cache isn't
// wiped by a database reload, which lets the glucose displays and the chart draw from the same data
// and exercise the multi-sensor coloring without real hardware.
private class PreviewMainState : MainState() {
    init {
        Sensors.active.forEachIndexed { index, sensor ->
            cache[sensor.id] = previewReadings(
                sensor.id,
                seed = 42 + index * 13,
                start = 105.0 + index * 40.0,
            ).toMutableList()
        }
    }

    override fun loadData(start: Instant, end: Instant) {}
}

// Substitute this for `viewModel()` as the `state` argument of MainScreen to preview the screen
// with the dummy sensors injected by Sensors.addDummySensors.
fun mainStatePreview(): MainState = PreviewMainState()

// Like [PreviewMainState], but also seeds a disconnected sensor — one whose id isn't registered in
// [Sensors]. The connected sensors only cover the recent half of the portrait (3h) span, while the
// old sensor fills the older half, so the chart holds more curves than there are live sensors. This
// exercises the highlight/display logic when scrubbing across curves the live sensor set doesn't own.
private class DisconnectedSensorPreviewMainState : MainState() {
    init {
        Sensors.active.forEachIndexed { index, sensor ->
            cache[sensor.id] = previewReadings(
                sensor.id,
                seed = 42 + index * 13,
                start = 105.0 + index * 40.0,
                fromMinutesAgo = 90,
            ).toMutableList()
        }
        val oldId = SensorId(99)
        cache[oldId] = previewReadings(
            oldId,
            seed = 7,
            start = 140.0,
            fromMinutesAgo = 180,
            toMinutesAgo = 100,
        ).toMutableList()
    }

    override fun loadData(start: Instant, end: Instant) {}
}

fun mainStateDisconnectedSensorPreview(): MainState = DisconnectedSensorPreviewMainState()
