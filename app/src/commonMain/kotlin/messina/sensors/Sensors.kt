package messina.sensors

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import messina.Database
import messina.Glucose
import messina.sensors.Sensor.Libre3
import messina.sensors.Sensor.Raspberry
import messina.sensors.libre3.libre3Connection
import messina.sensors.raspberry.raspberryConnection
import messina.logging.error
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlin.math.exp
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit
import kotlin.time.Instant

// How many minutes of recent readings are  kept in memory per sensor for smoothing purposes.
private const val RECENT_LIMIT = 15

@Serializable
data class SensorId(val value: Long)

@Serializable
enum class Smoothing(val alphaPerMinute: Double) {
    None(1.0),
    Weak(0.9),
    Medium(0.6),
    Strong(0.3);

    fun next(): Smoothing = entries[(ordinal + 1) % entries.size]

    fun apply(readings: List<GlucoseReading>): List<GlucoseReading> {
        if (readings.isEmpty() || this == None) return readings

        val decay = 1.0 - this.alphaPerMinute
        val out = ArrayList<GlucoseReading>(readings.size)
        var ema = readings.first().glucose.toMgDl()
        var prev = readings.first().time
        for (reading in readings) {
            val dt = (reading.time - prev).toDouble(DurationUnit.MINUTES)
            val alpha = 1.0 - decay.pow(dt)
            ema = alpha * reading.glucose.toMgDl() + (1 - alpha) * ema
            prev = reading.time
            out += reading.copy(glucose = Glucose.fromMgDl(ema))
        }
        return out
    }
}

@Serializable
sealed class Sensor {
    // Initialized from the row id assigned to it in the database
    @Transient
    lateinit var id: SensorId

    @Serializable
    @SerialName("Libre3")
    class Libre3(
        val serialNumber: ByteArray,
        val macAddress: ByteArray,
        var blePin: ByteArray,
        // TODO: This is almost never accurate. Sensor may be drifting while in storage?.
        //  Use the initial sensor status lifecount to swap it out with the correct time.
        val activationTime: Instant,
        @SerialName("sharedStaticKey")
        private var _sharedStaticKey: ByteArray? = null,
        @SerialName("sharedKey")
        private var _sharedKey: ByteArray? = null,
        // Marks the last reading received, defaults to the first possible historical reading.
        // Whenever the gap between this and the latest reading is larger than 5 minutes it
        // triggers a backfill.
        @SerialName("lastReceived")
        private var _lastReceived: Duration = 5.minutes,
    ) : Sensor() {
        var lastReceived: Duration
            get() = _lastReceived
            set(value) {
                _lastReceived = value
                this.save()
            }

        var sharedStaticKey: ByteArray?
            get() = _sharedStaticKey
            set(value) {
                _sharedStaticKey = value
                this.save()
            }

        var sharedKey: ByteArray?
            get() = _sharedKey
            set(value) {
                _sharedKey = value
                this.save()
            }

        fun calibrateReading(reading: Glucose): Glucose {
            // TODO: These values are picked by random. Need test data to fit to.
            // The larger the values the faster the calibration diverges from the reading
            val below = 0.35
            val above = 0.15
            val mmol = reading.toMmol()
            val scale = when {
                mmol < 4.0 -> exp(below * (mmol - 4.0))
                mmol + calibrationOffset.toMmol() > 10.0 -> exp(above * (mmol + calibrationOffset.toMmol() - 10.0))
                else -> 1.0
            }
            return Glucose.fromMmol(mmol + calibrationOffset.toMmol() * scale)
        }
    }

    @Serializable
    @SerialName("RaspberryPi")
    class Raspberry(
        val macAddress: ByteArray = "B827EB759540".hexToByteArray(),
    ) : Sensor()

    @SerialName("active")
    private var _active = true
    private val activeState by lazy { mutableStateOf(_active) }
    var active: Boolean
        get() = activeState.value
        set(value) {
            _active = value
            activeState.value = value
            this.save()
        }

    @SerialName("calibrationOffset")
    var calibrationOffset: Glucose = Glucose.fromMgDl(0.0)
        set(value) {
            field = value
            this.save()
        }

    @SerialName("smoothing")
    private var _smoothing = Smoothing.None
    private val smoothingState by lazy { mutableStateOf(_smoothing) }
    var smoothing: Smoothing
        get() = smoothingState.value
        set(value) {
            _smoothing = value
            smoothingState.value = value
            this.save()
        }

    @Transient
    val recentReadings = mutableStateListOf<GlucoseReading>()
    fun latestReading(): GlucoseReading? = this.smoothing.apply(recentReadings).lastOrNull()

    // Displays as the time since the last reading above the glucose on the main screen
    var lastReadingTime: Instant? by mutableStateOf(null)

    // Whether a bluetooth connection to the sensor is currently established
    var connected: Boolean by mutableStateOf(false)

    @Transient
    private var bluetoothConnection: Job? = null

    fun connectBluetooth() {
        this.bluetoothConnection = GlobalScope.launch {
            try {
                when (this@Sensor) {
                    is Libre3 -> libre3Connection(this@Sensor)
                    is Raspberry -> raspberryConnection(this@Sensor)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error { "Sensor connection failed: $e" }
            }
        }
    }

    fun disconnect() {
        this.bluetoothConnection?.cancel()
        this.bluetoothConnection = null
    }

    fun expiresIn(): Duration = when (this) {
        is Libre3 -> activationTime + 15.days - Clock.System.now()
        is Raspberry -> Duration.INFINITE
    }

    // TODO: These are placeholder names.
    fun name(): String {
        return when (this) {
            is Libre3 -> "Libre 3 · ${
                this.serialNumber.toHexString().takeLast(6).uppercase()
            }"

            is Raspberry -> "Raspberry · ${
                this.macAddress.toHexString().takeLast(6).uppercase()
            }"
        }
    }

    fun save() {
        Database.execute(
            "UPDATE sensors SET sensor = ? WHERE rowid = ?",
            arrayOf(
                Json.encodeToString(this),
                this.id.value
            )
        )
    }
}

object Sensors {
    // All sensors that have ever been added
    private val sensors = mutableStateMapOf<SensorId, Sensor>()

    val size get() = this.sensors.size

    val active get() = this.sensors.values.filter { it.active }
    val inactive get() = this.sensors.values.filter { !it.active }

    // Palette used to color each sensor's glucose curve and display on the main screen.
    val COLORS = listOf(
        Color(0xFF4CAF50), // green
        Color(0xFFE65100), // orange
        Color(0xFFC62828), // red
        Color(0xFF1565C0), // blue
    )

    init {
        GlobalScope.launch {
            SensorEvents.glucoseReading.collect { event ->
                val sensor = get(event.sensorId) ?: return@collect
                sensor.recentReadings.add(event)

                val cutoff = event.time - RECENT_LIMIT.minutes
                sensor.recentReadings.removeAll { it.time <= cutoff }

                // We can't use event.time here because it isn't accurate enough.
                sensor.lastReadingTime = Clock.System.now()

                Database.execute(
                    "INSERT OR REPLACE INTO glucose_history (sensor_id, time, glucose) VALUES (?, ?, ?)",
                    arrayOf(
                        event.sensorId.value,
                        event.time.epochSeconds,
                        event.glucose.toMgDl()
                    )
                )
            }
        }

        GlobalScope.launch {
            SensorEvents.sensorConnected.collect { get(it)?.connected = true }
        }

        GlobalScope.launch {
            SensorEvents.sensorDisconnected.collect { get(it)?.connected = false }
        }

        // Removing a sensor cancels the connection job, so no disconnect event fires for it
        GlobalScope.launch {
            SensorEvents.sensorRemoved.collect { it.connected = false }
        }

        Database.execute("SELECT rowid, sensor FROM sensors") { rows ->
            for (row in rows) {
                val sensor: Sensor = Json.decodeFromString(row.getText(1))
                sensor.id = SensorId(row.getLong(0))
                register(sensor)
            }
        }

//        addDummySensors(2)
    }

    private fun addDummySensors(count: Int) {
        val now = Clock.System.now()
        repeat(count) { i ->
            val sensor = Raspberry(
                macAddress = byteArrayOf()
            )
            sensor.id = SensorId(i.toLong())
            val base = 90.0 + i * 30.0
            sensor.recentReadings.addAll(
                (RECENT_LIMIT - 1 downTo 0).map { minutesAgo ->
                    val mgdl = base + (RECENT_LIMIT - 1 - minutesAgo) * 1.5
                    GlucoseReading(sensor.id, now - minutesAgo.minutes, Glucose.fromMgDl(mgdl))
                }
            )
            sensor.lastReadingTime = now
            sensors[sensor.id] = sensor
        }
    }

    fun add(sensor: Sensor) {
        // The unique id is derived from the sensor info and thus stays static between scans of
        // the same sensor.
        val identifier = run {
            val buffer = Buffer()
            when (sensor) {
                is Libre3 -> {
                    // Each sensor has its own unique first byte
                    buffer.writeByte(0)
                    buffer.write(sensor.serialNumber)
                }

                is Raspberry -> {
                    buffer.writeByte(1)
                }
            }
            buffer.readByteArray()
        }

        Database.execute(
            "INSERT OR IGNORE into sensors (identifier, sensor) values (?, ?)",
            arrayOf(identifier, Json.encodeToString(sensor))
        )

        // In case this is a new sensor this will have no effect
        val savedSensor = Database.execute(
            "SELECT rowid, sensor FROM sensors WHERE identifier = ?",
            arrayOf(identifier)
        ) { rows ->
            val row = rows.next()!!
            val savedSensor: Sensor = Json.decodeFromString(row.getText(1))
            savedSensor.id = SensorId(row.getLong(0))
            savedSensor
        }
        sensor.id = savedSensor.id

        // This is a rescan of an active sensor
        if (this.get(sensor.id)?.active == true) return

        when (savedSensor) {
            is Libre3 -> {
                sensor as Libre3

                sensor.lastReceived = savedSensor.lastReceived
                sensor.sharedStaticKey = savedSensor.sharedStaticKey
                // Ble pin change is guaranteed to require new key agreement
                if (savedSensor.blePin.contentEquals(sensor.blePin)) {
                    // NOTE: This triggers sensor.save() through its setter, but it's no big deal
                    // saving twice
                    sensor.sharedKey = savedSensor.sharedKey
                }
            }

            else -> {}
        }

        // Save the merged sensor state
        sensor.save()

        register(sensor)
        SensorEvents.sensorAdded.send(sensor)
    }

    private fun register(sensor: Sensor) {
        this.sensors[sensor.id] = sensor

        // Restore recent readings from the database so the UI has data to show immediately, before
        // any live readings start arriving on connect.
        Database.execute(
            "SELECT time, glucose FROM glucose_history WHERE sensor_id = ? ORDER BY time DESC LIMIT ?",
            arrayOf(sensor.id.value, RECENT_LIMIT),
        ) { rows ->
            val readings = buildList {
                for (row in rows) {
                    val time = Instant.fromEpochSeconds(row.getLong(0))
                    val glucose = Glucose.fromMgDl(row.getDouble(1))
                    add(GlucoseReading(sensor.id, time, glucose))
                }
            }
            sensor.recentReadings.addAll(readings.asReversed())
            readings.firstOrNull()?.let { newest ->
                val cutoff = newest.time - RECENT_LIMIT.minutes
                sensor.recentReadings.removeAll { it.time <= cutoff }
            }
        }

        if (sensor.active) sensor.connectBluetooth()
    }

    fun get(sensorId: SensorId): Sensor? {
        return this.sensors[sensorId]
    }

    fun isConnected(sensorId: SensorId): Boolean {
        return this.sensors[sensorId]?.active == true
    }

    fun remove(sensorId: SensorId) {
        val sensor = this.sensors[sensorId] ?: return
        sensor.disconnect()
        sensor.active = false
        SensorEvents.sensorRemoved.send(sensor)
    }
}

