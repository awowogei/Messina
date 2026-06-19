package messina.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import messina.Database
import messina.Glucose
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SettingsJson(
    val glucoseUnit: GlucoseUnit = GlucoseUnit.Mmol,
    val keepScreenOn: Boolean = false,
    val statusBarGlucose: Boolean = true,
    val theme: Theme = Theme.System,
    val alarms: List<Alarm> = listOf(
        Alarm.Connection(),
        Alarm.Glucose(Glucose.fromMgDl(250.0)),
        Alarm.Glucose(Glucose.fromMgDl(70.0))
    ),
    val targetRangeLow: Glucose = Glucose.fromMgDl(75.0),
    val targetRangeHigh: Glucose = Glucose.fromMgDl(180.0),
)

object Settings {
    private var _glucoseUnit: GlucoseUnit by mutableStateOf(GlucoseUnit.Mmol)
    var glucoseUnit: GlucoseUnit
        get() = _glucoseUnit
        set(value) {
            _glucoseUnit = value; save()
        }

    private var _keepScreenOn: Boolean by mutableStateOf(false)
    var keepScreenOn: Boolean
        get() = _keepScreenOn
        set(value) {
            _keepScreenOn = value; save()
        }

    private var _statusBarGlucose: Boolean by mutableStateOf(true)
    var statusBarGlucose: Boolean
        get() = _statusBarGlucose
        set(value) {
            _statusBarGlucose = value; save()
        }

    private var _theme: Theme by mutableStateOf(Theme.System)
    var theme: Theme
        get() = _theme
        set(value) {
            _theme = value; save()
        }

    private var _targetRange: Pair<Glucose, Glucose> by mutableStateOf(
        Pair(Glucose.fromMgDl(75.0), Glucose.fromMgDl(180.0))
    )
    var targetRange: Pair<Glucose, Glucose>
        get() = _targetRange
        set(value) {
            _targetRange = value; save()
        }

    val alarms = mutableStateListOf(
        Alarm.Connection(),
        Alarm.Glucose(Glucose.fromMgDl(250.0)),
        Alarm.Glucose(Glucose.fromMgDl(70.0))
    )

    init {
        val json = Database.execute("SELECT value FROM storage WHERE key = 'settings'") { rows ->
            rows.next()?.getText(0)
        }
        if (json != null) {
            val data = Json.decodeFromString<SettingsJson>(json)
            _glucoseUnit = data.glucoseUnit
            _keepScreenOn = data.keepScreenOn
            _statusBarGlucose = data.statusBarGlucose
            _theme = data.theme
            _targetRange = Pair(data.targetRangeLow, data.targetRangeHigh)
            alarms.clear()
            alarms.addAll(data.alarms)
        }

        GlobalScope.launch { triggerAlarms() }
    }

    fun addGlucoseAlarm(level: Glucose) {
        alarms.add(Alarm.Glucose(level))
        save()
    }

    fun removeAlarm(alarm: Alarm) {
        alarms.remove(alarm)
        save()
    }

    fun save() {
        val data = SettingsJson(
            glucoseUnit,
            keepScreenOn,
            statusBarGlucose,
            theme,
            alarms.toList(),
            targetRange.first,
            targetRange.second
        )
        Database.execute(
            "INSERT OR REPLACE INTO storage (key, value) VALUES ('settings', ?)",
            arrayOf(Json.encodeToString(data))
        )
    }
}

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF007AFF),
    onPrimary = Color.White,
    secondary = Color(0xFF4CAF50),
    onSecondary = Color.White,
    tertiary = Color(0xFF34C759),
    onTertiary = Color.White,
    error = Color(0xFFFF3B30),
    onError = Color.White,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF808080),
    outline = Color(0xFFB0B0B0),
    outlineVariant = Color(0xFFE0E0E0),
)

// TODO: Copy pasted, looks bad
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4DA6FF),
    onPrimary = Color.Black,
    secondary = Color(0xFF30D158),
    onSecondary = Color.Black,
    tertiary = Color(0xFF34C759),
    onTertiary = Color.Black,
    error = Color(0xFFFF6B60),
    onError = Color.Black,
    surface = Color(0xFF2C2C2E),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF3a3a3c),
    onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFF636366),
    outlineVariant = Color(0xFF3A3A3C),
)

@Serializable
enum class Theme {
    System, Dark, Light;

    fun next(): Theme = entries[(ordinal + 1) % entries.size]

    companion object {
        @Composable
        fun isDarkMode(): Boolean = when (Settings.theme) {
            System -> isSystemInDarkTheme()
            Dark -> true
            Light -> false
        }

        @Composable
        fun colorScheme(): ColorScheme {
            return if (isDarkMode()) {
                DarkColorScheme
            } else {
                LightColorScheme
            }
        }
    }
}

@Serializable
enum class GlucoseUnit {
    Mmol,
    MgDl;

    override fun toString(): String {
        return when (this) {
            Mmol -> "mmol/L"
            MgDl -> "mg/dL"
        }
    }
}

expect fun getApplicationPath(): String