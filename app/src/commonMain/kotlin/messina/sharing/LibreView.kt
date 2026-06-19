package messina.sharing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import messina.Database
import messina.sensors.Sensor
import messina.sensors.Sensors
import messina.settings.GlucoseUnit
import messina.settings.Settings
import messina.http.Http
import kotlinx.coroutines.Job
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.char
import kotlinx.datetime.format.format
import kotlinx.datetime.offsetAt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val LIBRE3_START =
    "https://fsll3.freestyleserver.com/Payloads/Mobile/FSLibre3/Android/Assets/3.3.0/DE.json"
private const val GATEWAY = "FSLibreLink3.Android"
private const val APP_PLATFORM = "Android/14/FSL3/3.3.0.9092"

class LibreViewException(message: String) : RuntimeException(message)

object LibreView {
    private var _email: String by mutableStateOf("")
    var email: String
        get() = _email
        set(value) {
            if (_email != value) {
                _email = value
                accountUuid = null
                userToken = null
            }
            save()
        }

    private var _password: String by mutableStateOf("")
    var password: String
        get() = _password
        set(value) {
            if (_password != value) {
                _password = value
                accountUuid = null
                userToken = null
            }
            save()
        }

    var accountUuid: String? by mutableStateOf(null)
        private set

    var userToken: String? = null
        private set

    var loginJob: Job? by mutableStateOf(null)

    var status: String? by mutableStateOf(null)

    private val sensorStates: MutableMap<Long, SensorSyncState> = mutableMapOf()

    private var _syncData: Boolean by mutableStateOf(false)
    var syncData: Boolean
        get() = _syncData
        set(value) {
            _syncData = value; save()
        }

    // Require an account in order to connect to libre sensors
    private var _requireAccount: Boolean by mutableStateOf(true)
    var requireAccount: Boolean
        get() = _requireAccount
        set(value) {
            _requireAccount = value; save()
        }

    val loggedIn: Boolean get() = accountUuid != null

    fun logout() {
        accountUuid = null
        userToken = null
        _syncData = false
        save()
    }

    private var deviceId: String = Uuid.random().toString()

    fun accountId(): UInt {
        var id = 0u
        // If there is no accountUuid it means the user has explicitly turned off using a
        // LibreView account.
        val uuid = accountUuid ?: return id
        for (byte in uuid.encodeToByteArray()) {
            id = (id * 0x811C9DC5u) xor byte.toUInt()
        }
        return id
    }

    suspend fun login() {
        if (email.isEmpty() || password.isEmpty()) {
            throw LibreViewException("Email and password required")
        }

        val (baseUrl, apiKey) = fetchConfig()

        var setDevice = false
        repeat(2) {
            val body = mapOf(
                "Culture" to "en-US",
                "DeviceId" to deviceId,
                "Password" to password,
                "SetDevice" to setDevice,
                "UserName" to email,
                "Domain" to "Libreview",
                "GatewayType" to GATEWAY,
            )
            val headers = mapOf(
                "Content-Type" to "application/json",
                "Platform" to "Android",
                "Version" to "3.3.0",
                "Abbott-ADC-App-Platform" to APP_PLATFORM,
                "Accept-Language" to "en-US",
                "x-api-key" to apiKey,
                "x-newyu-token" to "",
            )

            val response = Http.post("$baseUrl/api/nisperson/getauthentication", body, headers)
            if (response.code !in 200..299 || response.body == null) {
                throw LibreViewException("Auth HTTP ${response.code}")
            }

            val obj = Json.parseToJsonElement(response.body!!).jsonObject
            val status = obj["status"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
            if (status != 0) {
                val reason = obj["reason"]?.jsonPrimitive?.content
                if (status == 20 && reason == "wrongDeviceForUser" && !setDevice) {
                    setDevice = true
                    return@repeat
                }
                throw LibreViewException("Auth status=$status reason=$reason")
            }

            val result = obj["result"]?.jsonObject ?: throw LibreViewException("Missing result")
            accountUuid = result["AccountId"]?.jsonPrimitive?.content
            userToken = result["UserToken"]?.jsonPrimitive?.content
            save()
            return
        }
        throw LibreViewException("Auth retry exhausted")
    }

    private suspend fun fetchConfig(): Pair<String, String> {
        val startResp = Http.get(LIBRE3_START)
        if (startResp.body == null) throw LibreViewException("Config start HTTP ${startResp.code}")
        val configUrl = Json.parseToJsonElement(startResp.body!!)
            .jsonObject["Configuration"]?.jsonPrimitive?.content
            ?: throw LibreViewException("Missing Configuration url")

        val configResp = Http.get(configUrl)
        if (configResp.body == null) throw LibreViewException("Config HTTP ${configResp.code}")
        val configObj = Json.parseToJsonElement(configResp.body!!).jsonObject
        val baseUrl = configObj["newYuUrl"]?.jsonPrimitive?.content
            ?: throw LibreViewException("Missing newYuUrl")
        val apiKey = configObj["newYuApiKey"]?.jsonPrimitive?.content
            ?: throw LibreViewException("Missing newYuApiKey")
        return baseUrl to apiKey
    }

    init {
        val json =
            Database.execute("SELECT value FROM storage WHERE key = 'libreview'") { rows ->
                rows.next()?.getText(0)
            }
        if (json != null) {
            val data = Json.decodeFromString<LibreViewJson>(json)
            _email = data.email
            _password = data.password
            accountUuid = data.accountUuid
            userToken = data.userToken
            _syncData = data.syncData
            _requireAccount = data.requireAccount
            if (data.deviceId.isNotEmpty()) deviceId = data.deviceId
            sensorStates.putAll(data.sensorStates.mapKeys { it.key.toLong() })
        }
        pruneSensorStates()
        save()
    }

    // Drops state for sensors that no longer exist in the DB or are past 15 days from activation.
    private fun pruneSensorStates() {
        if (sensorStates.isEmpty()) return
        val now = Clock.System.now()
        val keep = mutableSetOf<Long>()
        Database.execute("SELECT rowid, sensor FROM sensors") { rows ->
            for (row in rows) {
                val rowid = row.getLong(0)
                if (rowid !in sensorStates) continue
                val sensor =
                    runCatching { Json.decodeFromString<Sensor>(row.getText(1)) }.getOrNull()
                if (sensor is Sensor.Libre3 && (now - sensor.activationTime) < 15.days) {
                    keep += rowid
                }
            }
        }
        sensorStates.keys.retainAll(keep)
    }

    private fun save() {
        val data = LibreViewJson(
            email = email,
            password = password,
            accountUuid = accountUuid,
            userToken = userToken,
            deviceId = deviceId,
            syncData = _syncData,
            requireAccount = _requireAccount,
            sensorStates = sensorStates.mapKeys { it.key.toString() },
        )
        Database.execute(
            "INSERT OR REPLACE INTO storage (key, value) VALUES ('libreview', ?)",
            arrayOf(Json.encodeToString(data))
        )
    }

    suspend fun sendMeasurements() {
        val token = userToken ?: throw LibreViewException("Not logged in")
        val (baseUrl, apiKey) = fetchConfig()

        val now = Clock.System.now()
        val tz = TimeZone.currentSystemDefault()

        val current = mutableListOf<Map<String, Any>>()
        val scheduled = mutableListOf<Map<String, Any>>()
        val generic = mutableListOf<Map<String, Any>>()
        // sensorId.value -> latestReadingEpochSeconds
        val touched = mutableListOf<Pair<Long, Long>>()

        for (sensor in Sensors.active) {
            if (sensor !is Sensor.Libre3) continue
            val state = sensorStates[sensor.id.value] ?: SensorSyncState()
            val serialBits = serialBits(sensor.serialNumber.decodeToString())

            val readings = Database.execute(
                "SELECT time, glucose FROM glucose_history WHERE sensor_id = ? AND time > ? ORDER BY time ASC",
                arrayOf(sensor.id.value, state.lastReadingTime),
            ) { rows ->
                buildList {
                    for (row in rows) add(row.getLong(0) to row.getDouble(1))
                }
            }
            if (readings.isEmpty() && state.announced) continue

            if (!state.announced) {
                generic += sensorStartEntry(sensor, serialBits, tz)
            }

            readings.dropLast(1).forEach { (timeSec, mgdl) ->
                scheduled += historicEntry(Instant.fromEpochSeconds(timeSec), mgdl, serialBits, tz)
            }
            readings.lastOrNull()?.let { (timeSec, mgdl) ->
                current += currentEntry(Instant.fromEpochSeconds(timeSec), mgdl, serialBits, tz)
            }
            readings.lastOrNull()?.let { touched += sensor.id.value to it.first }
        }

        if (current.isEmpty() && scheduled.isEmpty() && generic.isEmpty()) return

        val targetLowMgDl = Settings.targetRange.first.toMgDl().roundToInt()
        val targetHighMgDl = Settings.targetRange.second.toMgDl().roundToInt()
        val uom = if (Settings.glucoseUnit == GlucoseUnit.Mmol) "mmol/L" else "mg/dL"

        val body = mapOf(
            "DeviceData" to mapOf(
                "deviceSettings" to mapOf(
                    "factoryConfig" to mapOf("UOM" to uom),
                    "firmwareVersion" to "3.3.0",
                    "miscellaneous" to mapOf(
                        "selectedLanguage" to "en-US",
                        "valueGlucoseTargetRangeLowInMgPerDl" to targetLowMgDl,
                        "valueGlucoseTargetRangeHighInMgPerDl" to targetHighMgDl,
                        "selectedTimeFormat" to "24hr",
                        "selectedCarbType" to "grams of carbs",
                    ),
                    "timestamp" to localTimestamp(now, tz),
                ),
                "header" to mapOf(
                    "device" to mapOf(
                        "hardwareDescriptor" to "messina",
                        "hardwareName" to "messina",
                        "modelName" to "com.freestylelibre3.app.de",
                        "osType" to "Android",
                        "osVersion" to "14",
                        "uniqueIdentifier" to deviceId,
                    )
                ),
                "measurementLog" to mapOf(
                    "capabilities" to listOf(
                        "scheduledContinuousGlucose",
                        "unscheduledContinuousGlucose",
                        "currentGlucose",
                        "insulin",
                        "food",
                        "generic-com.abbottdiabetescare.informatics.sensorstart",
                        "generic-com.abbottdiabetescare.informatics.sensorEnd",
                    ),
                    "currentGlucoseEntries" to current,
                    "foodEntries" to emptyList<Any>(),
                    "genericEntries" to generic,
                    "insulinEntries" to emptyList<Any>(),
                    "scheduledContinuousGlucoseEntries" to scheduled,
                    "unscheduledContinuousGlucoseEntries" to emptyList<Any>(),
                )
            ),
            "UserToken" to token,
            "Domain" to "Libreview",
            "GatewayType" to GATEWAY,
        )

        val headers = mapOf(
            "Content-Type" to "application/json",
            "Platform" to "Android",
            "Version" to "3.3.0",
            "Abbott-ADC-App-Platform" to APP_PLATFORM,
            "Accept-Language" to "en-US",
            "x-api-key" to apiKey,
            "x-newyu-token" to token,
        )

        val response = Http.post("$baseUrl/api/measurements", body, headers, timeout = 60_000)
        if (response.code !in 200..299 || response.body == null) {
            throw LibreViewException("Measurements HTTP ${response.code}")
        }
        val status = Json.parseToJsonElement(response.body!!)
            .jsonObject["status"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        if (status != 0) {
            throw LibreViewException("Measurements status=$status")
        }

        for ((sensorIdValue, lastTime) in touched) {
            sensorStates[sensorIdValue] =
                SensorSyncState(announced = true, lastReadingTime = lastTime)
        }
        save()
    }
}

@Serializable
private data class SensorSyncState(
    val announced: Boolean = false,
    val lastReadingTime: Long = 0L,
)

// Decodes one character of a Libre serial to its 5-bit value. The alphabet is 0-9 then A-Z
// with B,I,O,S folded onto 8,1,0,5 (the easily-confused glyphs).
private fun serialCharBits(c: Char): Int {
    val ch = when (c.uppercaseChar()) {
        'B' -> '8'; 'I' -> '1'; 'O' -> '0'; 'S' -> '5'
        else -> c.uppercaseChar()
    }
    return when {
        ch <= '9' -> ch.code - '0'.code
        ch == 'A' -> 10
        ch <= 'H' -> ch.code - 'A'.code + 9
        ch <= 'N' -> ch.code - 'A'.code + 8
        ch <= 'R' -> ch.code - 'A'.code + 7
        else -> ch.code - 'A'.code + 6
    }
}

// LibreView's recordNumber for a reading is `serialBits | readingBits`: the upper bits encode
// which sensor it came from (this function), the lower bits encode which reading within the
// sensor. For a Libre 3 serial (9 base-32 chars = 45 bits), the result occupies bits 19..63,
// leaving 19 low bits for the per-reading id.
private fun serialBits(serial: String): Long {
    var acc = 0L
    for (ch in serial) acc = (acc shl 5) or serialCharBits(ch).toLong()
    return acc shl 19
}

private fun recordNumber(serialBits: Long, sensor: Sensor.Libre3, time: Instant): Long {
    val minutes = ((time - sensor.activationTime).inWholeMinutes).coerceAtLeast(0).toInt()
    return serialBits or (minutes and 0x7FFFF).toLong()
}

private fun localTimestamp(instant: Instant, tz: TimeZone): String {
    // "2022-10-18T11:57:31.795+02:00"
    return DateTimeComponents.Formats.ISO_DATE_TIME_OFFSET.format {
        setDateTimeOffset(instant, tz.offsetAt(instant))
    }
}

private fun gmtTimestamp(instant: Instant): String = instant.toString()

private fun sensorStartEntry(
    sensor: Sensor.Libre3,
    serialBits: Long,
    tz: TimeZone,
): Map<String, Any> {
    val now = Clock.System.now()
    return mapOf(
        "type" to "com.abbottdiabetescare.informatics.sensorstart",
        "extendedProperties" to mapOf(
            "factoryTimestamp" to gmtTimestamp(sensor.activationTime),
            "puckGen" to 0,
            "wearDuration" to 21600,
            "warmupTime" to 60,
            "productType" to 4,
        ),
        "recordNumber" to recordNumber(serialBits, sensor, sensor.activationTime),
        "timestamp" to localTimestamp(now, tz),
    )
}

private fun historicEntry(
    time: Instant,
    mgdl: Double,
    serialBits: Long,
    tz: TimeZone,
): Map<String, Any> = mapOf(
    "extendedProperties" to mapOf(
        "canMerge" to true,
        "isFirstAfterTimeChange" to false,
        "factoryTimestamp" to gmtTimestamp(time),
    ),
    "recordNumber" to (serialBits or ((time.epochSeconds / 300) and 0x7FFFF)),
    "timestamp" to localTimestamp(time, tz),
    "valueInMgPerDl" to mgdl,
)

private fun currentEntry(
    time: Instant,
    mgdl: Double,
    serialBits: Long,
    tz: TimeZone,
): Map<String, Any> = mapOf(
    "extendedProperties" to mapOf(
        "trendArrow" to "Undetermined",
        "isActionable" to true,
        "isViewed" to false,
        "factoryTimestamp" to gmtTimestamp(time),
        "isFirstAfterTimeChange" to false,
    ),
    "recordNumber" to (serialBits or ((time.epochSeconds / 300) and 0x7FFFF)),
    "timestamp" to localTimestamp(time, tz),
    "valueInMgPerDl" to mgdl,
)

@Serializable
private data class LibreViewJson(
    val email: String = "",
    val password: String = "",
    val accountUuid: String? = null,
    val userToken: String? = null,
    val deviceId: String = "",
    val syncData: Boolean = false,
    val requireAccount: Boolean = true,
    val sensorStates: Map<String, SensorSyncState> = emptyMap(),
)
