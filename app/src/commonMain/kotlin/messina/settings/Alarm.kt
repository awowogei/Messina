package messina.settings

import androidx.compose.runtime.mutableStateOf
import messina.Glucose
import messina.sensors.SensorEvents
import messina.sensors.SensorId
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

private const val ALARM_COOLDOWN_MS: Long = 15 * 60 * 1000

expect object AlarmController {
    val active: MutableStateFlow<Boolean>

    fun start(alarm: Alarm)
    fun stop()
}


@Serializable
sealed class Alarm {
    @SerialName("enabled")
    private var _enabled: Boolean = true
    private val enabledState by lazy { mutableStateOf(_enabled) }
    var enabled: Boolean
        get() = enabledState.value
        set(value) {
            enabledState.value = value; _enabled = value; Settings.save()
        }

    @SerialName("ignoreMute")
    private var _ignoreMute: Boolean = true
    private val ignoreMuteState by lazy { mutableStateOf(_ignoreMute) }
    var ignoreMute: Boolean
        get() = ignoreMuteState.value
        set(value) {
            ignoreMuteState.value = value; _ignoreMute = value; Settings.save()
        }

    @SerialName("vibration")
    private var _vibration: Boolean = true
    private val vibrationState by lazy { mutableStateOf(_vibration) }
    var vibration: Boolean
        get() = vibrationState.value
        set(value) {
            vibrationState.value = value; _vibration = value; Settings.save()
        }

    @SerialName("sound")
    private var _sound: Boolean = true
    private val soundState by lazy { mutableStateOf(_sound) }
    var sound: Boolean
        get() = soundState.value
        set(value) {
            soundState.value = value; _sound = value; Settings.save()
        }

    @SerialName("volume")
    private var _volume: AlarmVolume = AlarmVolume.Loud
    private val volumeState by lazy { mutableStateOf(_volume) }
    var volume: AlarmVolume
        get() = volumeState.value
        set(value) {
            volumeState.value = value; _volume = value; Settings.save()
        }

    @SerialName("notification")
    private var _notification: Boolean = true
    private val notificationState by lazy { mutableStateOf(_notification) }
    var notification: Boolean
        get() = notificationState.value
        set(value) {
            notificationState.value = value; _notification = value; Settings.save()
        }

    @Serializable
    @SerialName("Glucose")
    class Glucose(
        @SerialName("threshold")
        private var _threshold: messina.Glucose,
    ) : Alarm() {
        private val thresholdState by lazy { mutableStateOf(_threshold) }
        var threshold: messina.Glucose
            get() = thresholdState.value
            set(value) {
                thresholdState.value = value; _threshold = value; Settings.save()
            }

        @SerialName("direction")
        private var _direction: AlarmDirection = run {
            val midpoint =
                (Settings.targetRange.first.toMgDl() + Settings.targetRange.second.toMgDl()) / 2.0
            if (_threshold.toMgDl() > midpoint) AlarmDirection.Above else AlarmDirection.Below
        }
        private val directionState by lazy { mutableStateOf(_direction) }
        var direction: AlarmDirection
            get() = directionState.value
            set(value) {
                directionState.value = value; _direction = value; Settings.save()
            }
    }

    @Serializable
    @SerialName("Connection")
    class Connection : Alarm()
}

suspend fun triggerAlarms() = coroutineScope {
    launch { triggerGlucoseAlarms() }
    launch { triggerConnectionAlarms() }
}

private suspend fun triggerGlucoseAlarms() {
    var lastGlucose: Glucose? = null
    val alarmLastTriggered = mutableMapOf<Int, Long>()

    SensorEvents.glucoseReading.collect { event ->
        val previous = lastGlucose
        lastGlucose = event.glucose
        val now = Clock.System.now().epochSeconds * 1000L

        Settings.alarms.forEachIndexed { index, alarm ->
            if (!alarm.enabled || alarm !is Alarm.Glucose) return@forEachIndexed
            val threshold = alarm.threshold.toMgDl()
            val cur = event.glucose.toMgDl()
            val prev = previous?.toMgDl()
            val triggered = when (alarm.direction) {
                AlarmDirection.Above -> cur >= threshold && (prev == null || prev < threshold)
                AlarmDirection.Below -> cur <= threshold && (prev == null || prev > threshold)
            }
            if (triggered && now - (alarmLastTriggered[index] ?: 0L) >= ALARM_COOLDOWN_MS) {
                alarmLastTriggered[index] = now
                AlarmController.start(alarm)
            }
        }
    }
}

private suspend fun triggerConnectionAlarms() = coroutineScope {
    val pending = mutableMapOf<SensorId, Job>()

    launch {
        SensorEvents.sensorDisconnected.collect { sensorId ->
            pending[sensorId]?.cancel()
            pending[sensorId] = launch {
                // TODO: This should be configurable in the alarm settings
                delay(20.minutes)
                pending.remove(sensorId)
                Settings.alarms.forEach { alarm ->
                    if (alarm.enabled && alarm is Alarm.Connection) {
                        AlarmController.start(alarm)
                    }
                }
            }
        }
    }

    launch {
        SensorEvents.sensorConnected.collect { sensorId ->
            pending.remove(sensorId)?.cancel()
        }
    }

    launch {
        SensorEvents.sensorRemoved.collect { sensor ->
            pending.remove(sensor.id)?.cancel()
        }
    }
}

@Serializable
enum class AlarmDirection {
    Above, Below;

    fun next(): AlarmDirection = if (this == Above) Below else Above
}

@Serializable
enum class AlarmVolume {
    Low, Normal, Loud, Increasing;

    fun next(): AlarmVolume = entries[(ordinal + 1) % entries.size]
}
