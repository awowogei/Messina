package messina.ui.main

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import messina.Database
import messina.Glucose
import messina.sensors.GlucoseReading
import messina.sensors.SensorEvents
import messina.sensors.SensorId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

open class MainState : ViewModel() {
    // Sliding cache of raw readings used for chart display.
    val cache = mutableStateMapOf<SensorId, MutableList<GlucoseReading>>()

    // Set to either the time of the last glucose reading or the last whole minute, whichever is larger.
    // Causes the chart to shift either when a new glucose reading comes in or a minute passes.
    var chartEnd: Instant by mutableStateOf(Clock.System.now())
        private set

    private var cacheStart: Instant = Instant.DISTANT_PAST
    private var cacheEnd: Instant = Instant.DISTANT_PAST
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            SensorEvents.glucoseReading.collect { event ->
                chartEnd = event.time
                if (event.time in cacheStart..cacheEnd) {
                    cache.getOrPut(event.sensorId) { mutableStateListOf() }.add(event)
                }
            }
        }

        viewModelScope.launch {
            SensorEvents.history.collect { event ->
                if (event.from <= cacheEnd && event.to >= cacheStart) {
                    reloadCache()
                }
            }
        }

        viewModelScope.launch {
            while (true) {
                val now = Clock.System.now()
                val secondsUntilNextMinute = 60 - (now.epochSeconds % 60)
                delay(secondsUntilNextMinute * 1000)
                chartEnd = Clock.System.now()
            }
        }
    }

    // Called by the GlucoseChart whenever the user pans or zooms.
    open fun loadData(start: Instant, end: Instant) {
        if (start < this.cacheStart + 24.hours || end > this.cacheEnd - 24.hours) {
            this.cacheStart = start - 40.hours
            this.cacheEnd = end + 40.hours
            reloadCache()
        }
    }

    private fun reloadCache() {
        this.loadJob?.cancel()
        this.loadJob = viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                val result = mutableMapOf<SensorId, MutableList<GlucoseReading>>()
                Database.execute(
                    "SELECT sensor_id, time, glucose FROM glucose_history " +
                            "WHERE time >= ? AND time <= ? ORDER BY time ASC",
                    arrayOf(cacheStart.epochSeconds, cacheEnd.epochSeconds),
                ) { rows ->
                    for (row in rows) {
                        val id = SensorId(row.getLong(0))
                        val time = Instant.fromEpochSeconds(row.getLong(1))
                        val glucose = Glucose.fromMgDl(row.getDouble(2))
                        val readings = result.getOrPut(id) { mutableStateListOf() }
                        readings.add(GlucoseReading(id, time, glucose))
                    }
                }
                result
            }

            cache.clear()
            cache.putAll(data)
        }
    }
}
