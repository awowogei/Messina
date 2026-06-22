package messina.androidapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import messina.GlobalState
import messina.android.R
import messina.sensors.SensorEvents
import androidx.compose.runtime.snapshotFlow
import messina.Glucose
import messina.settings.AlarmController
import messina.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "ForegroundServiceChannel"
private const val NOTIFICATION_ID = 1

class ForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastGlucose: Glucose? = null

    override fun onCreate() {
        super.onCreate()

        GlobalState.initialize()

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()

        startForeground(
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )

        serviceScope.launch {
            SensorEvents.glucoseReading.collect { event ->
                lastGlucose = event.glucose
                updateNotification()
            }
        }

        serviceScope.launch {
            snapshotFlow { Settings.statusBarGlucose }.collect {
                updateNotification()
            }
        }

        serviceScope.launch {
            AlarmController.active.collect { updateNotification() }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification() {
        val notification = buildNotification()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
//        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentText(lastGlucose?.toString() ?: "Waiting for reading")
            .setContentIntent(buildContentIntent())
            .setOngoing(true)           // makes it non-dismissable by the user
            .setOnlyAlertOnce(true)     // no sound/vibration on updates
        if (Settings.statusBarGlucose) {
            builder.setSmallIcon(buildTextIcon())
        } else {
            builder.setSmallIcon(R.drawable.messina_foreground)
        }
        return builder.build()
    }

    private fun buildContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildTextIcon(): Icon {
        val text = lastGlucose?.format() ?: "-"
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = 1f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = size * 0.9f
        }
        // Compress horizontally if too wide, keeping the height (and visual size) constant
        val textWidth = textPaint.measureText(text)
        if (textWidth > size - 2f) {
            textPaint.textScaleX = (size - 2f) / textWidth
        }
        val x = size / 2f
        val y = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, x, y, textPaint)
        return Icon.createWithBitmap(bitmap)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Foreground Service",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Keeps the app alive in the background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
