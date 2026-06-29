package messina.share

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import messina.Database
import messina.Glucose
import messina.cryptography.sha1
import messina.http.Http
import messina.sensors.GlucoseEvent
import messina.sensors.Sensor
import kotlin.math.roundToInt

class NightScoutException(message: String) : RuntimeException(message)

@Serializable
private data class NightScoutJson(
    val url: String = "",
    val secret: String = "",
    val connected: Boolean = false,
    val syncEnabled: Boolean = false,
)

object NightScout {
    private var _url: String by mutableStateOf("")
    var url: String
        get() = _url
        set(value) {
            if (_url != value) _url = value
            save()
        }

    private var _secret: String by mutableStateOf("")
    var secret: String
        get() = _secret
        set(value) {
            if (_secret != value) _secret = value
            save()
        }

    var connected: Boolean by mutableStateOf(false)
        private set

    private var _syncEnabled: Boolean by mutableStateOf(false)
    var syncEnabled: Boolean
        get() = _syncEnabled
        set(value) {
            _syncEnabled = value; save()
        }

    var connectJob: Job? by mutableStateOf(null)
    var status: String? by mutableStateOf(null)

    fun disconnect() {
        connected = false
        save()
    }

    init {
        val json = Database.execute("SELECT value FROM storage WHERE key = 'nightscout'") { rows ->
            rows.next()?.getText(0)
        }
        if (json != null) {
            val data = Json.decodeFromString<NightScoutJson>(json)
            _url = data.url
            _secret = data.secret
            connected = data.connected
            _syncEnabled = data.syncEnabled
        }
    }

    private fun save() {
        val data = NightScoutJson(
            url = url,
            secret = secret,
            connected = connected,
            syncEnabled = syncEnabled,
        )
        Database.execute(
            "INSERT OR REPLACE INTO storage (key, value) VALUES ('nightscout', ?)",
            arrayOf(Json.encodeToString(data))
        )
    }

    private fun apiSecret(): String = sha1(secret.encodeToByteArray()).toHexString()

    private fun endpoint(path: String): String = url.trimEnd('/') + path

    // Checks the URL and secret against the instance
    suspend fun connect() {
        if (url.isBlank() || secret.isBlank()) throw NightScoutException("URL and secret required")
        val response = Http.get(
            endpoint("/api/v1/entries.json?count=1"),
            mapOf("api-secret" to apiSecret()),
        )
        when (response.code) {
            in 200..299 -> {}
            401 -> throw NightScoutException("Bad API secret")
            else -> throw NightScoutException("HTTP ${response.code}")
        }
        connected = true
        save()
    }

    private fun direction(trend: Glucose?): String {
        if (trend == null) return "NOT COMPUTABLE"
        return when (trend.toMgDl()) {
            in 3.5..Double.MAX_VALUE -> "DoubleUp"
            in 2.0..3.5 -> "SingleUp"
            in 1.0..2.0 -> "FortyFiveUp"
            in -1.0..1.0 -> "Flat"
            in -2.0..-1.0 -> "FortyFiveDown"
            in -3.5..-2.0 -> "SingleDown"
            else -> "DoubleDown"
        }
    }

    suspend fun upload(sensor: Sensor, event: GlucoseEvent) {
        if (!syncEnabled || !connected) return

        val entry = mapOf(
            "type" to "sgv",
            "sgv" to event.glucose.toMgDl().roundToInt(),
            "direction" to direction(event.trend),
            "device" to "messina://${sensor.name()}/${sensor.id.value}",
            "date" to event.time.toEpochMilliseconds(),
            "dateString" to event.time.toString(),
        )

        val response = Http.post(
            endpoint("/api/v1/entries"),
            listOf(entry),
            mapOf(
                "Content-Type" to "application/json",
                "api-secret" to apiSecret(),
            ),
            timeout = 60_000,
        )
        if (response.code !in 200..299) {
            throw NightScoutException("Entries HTTP ${response.code}")
        }
    }
}
