package messina.share

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import messina.Database
import messina.sensors.GlucoseReading
import messina.sensors.Sensor
import messina.settings.Settings
import messina.http.Http
import kotlinx.coroutines.Job
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.format
import kotlinx.datetime.offsetAt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val LIBRE3_START =
    "https://fsll3.freestyleserver.com/Payloads/Mobile/FSLibre3/Android/Assets/3.3.0/DE.json"
private const val GATEWAY = "FSLibreLink3.Android"
private const val APP_PLATFORM = "Android/14/FSL3/3.3.0.9092"

class LibreViewException(message: String) : RuntimeException(message)

@Serializable
private data class LibreViewJson(
    val email: String = "",
    val password: String = "",
    val accountUuid: String? = null,
    val userToken: String? = null,
    val deviceId: String = "",
    val syncEnabled: Boolean = false,
    val requireAccount: Boolean = true,
)

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

    private var _syncEnabled: Boolean by mutableStateOf(false)
    var syncEnabled: Boolean
        get() = _syncEnabled
        set(value) {
            _syncEnabled = value; save()
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
        _syncEnabled = false
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
            _syncEnabled = data.syncEnabled
            _requireAccount = data.requireAccount
            if (data.deviceId.isNotEmpty()) deviceId = data.deviceId
        }
        save()
    }

    private fun save() {
        val data = LibreViewJson(
            email = email,
            password = password,
            accountUuid = accountUuid,
            userToken = userToken,
            deviceId = deviceId,
            syncEnabled = _syncEnabled,
            requireAccount = _requireAccount,
        )
        Database.execute(
            "INSERT OR REPLACE INTO storage (key, value) VALUES ('libreview', ?)",
            arrayOf(Json.encodeToString(data))
        )
    }

    suspend fun upload(sensor: Sensor, history: List<GlucoseReading>) {
        if (!this.syncEnabled || !this.loggedIn || sensor !is Sensor.Libre3 || history.isEmpty()) return
        val tz = TimeZone.currentSystemDefault()
        val serialBits = serialBits(sensor.serialNumber.decodeToString())

        val readings = history
            .distinctBy { it.time.epochSeconds / 300 }
            .map { reading ->
                mapOf(
                    "extendedProperties" to mapOf(
                        "canMerge" to true,
                        "isFirstAfterTimeChange" to false,
                        "factoryTimestamp" to gmtTimestamp(reading.time),
                    ),
                    "recordNumber" to (serialBits or ((reading.time.epochSeconds / 300) and 0x7FFFF)),
                    "timestamp" to localTimestamp(reading.time, tz),
                    "valueInMgPerDl" to reading.glucose.toMgDl(),
                )
            }
        post(readings)
    }

    suspend fun register(sensor: Sensor) {
        if (sensor !is Sensor.Libre3) return
        val tz = TimeZone.currentSystemDefault()
        val serialBits = serialBits(sensor.serialNumber.decodeToString())

        post(
            newSensor = mapOf(
                "type" to "com.abbottdiabetescare.informatics.sensorstart",
                "extendedProperties" to mapOf(
                    "factoryTimestamp" to gmtTimestamp(sensor.activationTime),
                    "puckGen" to 0,
                    "wearDuration" to 21600,
                    "warmupTime" to 60,
                    "productType" to 4,
                ),
                "recordNumber" to (serialBits or (sensor.activationTime.epochSeconds / 60 and 0x7FFFF)),
                "timestamp" to localTimestamp(Clock.System.now(), tz),
            )
        )
    }

    private suspend fun post(
        readings: List<Map<String, Any>> = emptyList(),
        newSensor: Map<String, Any>? = null,
    ) {
        val token = userToken ?: throw LibreViewException("Not logged in")
        val (baseUrl, apiKey) = fetchConfig()

        val now = Clock.System.now()
        val tz = TimeZone.currentSystemDefault()

        val targetLowMgDl = Settings.targetRange.first.toMgDl().roundToInt()
        val targetHighMgDl = Settings.targetRange.second.toMgDl().roundToInt()

        val body = mapOf(
            "DeviceData" to mapOf(
                "deviceSettings" to mapOf(
                    "factoryConfig" to mapOf("UOM" to Settings.glucoseUnit.toString()),
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
                        "hardwareDescriptor" to "android",
                        "hardwareName" to "android",
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
                    "currentGlucoseEntries" to emptyList<Any>(),
                    "foodEntries" to emptyList<Any>(),
                    "genericEntries" to listOfNotNull(newSensor),
                    "insulinEntries" to emptyList<Any>(),
                    "scheduledContinuousGlucoseEntries" to readings,
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
    }
}

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

private fun serialBits(serial: String): Long {
    var acc = 0L
    for (ch in serial) acc = (acc shl 5) or serialCharBits(ch).toLong()
    return acc shl 19
}

private fun localTimestamp(instant: Instant, tz: TimeZone): String {
    // "2022-10-18T11:57:31.795+02:00"
    return DateTimeComponents.Formats.ISO_DATE_TIME_OFFSET.format {
        setDateTimeOffset(instant, tz.offsetAt(instant))
    }
}

private fun gmtTimestamp(instant: Instant): String = instant.toString()