package messina.settings

import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import messina.ContextStore.Companion.ApplicationContext
import messina.utils.Vibration
import kotlinx.coroutines.flow.MutableStateFlow

actual object AlarmController {
    actual val active = MutableStateFlow(false)
    private var activeSound: Ringtone? = null

    actual fun start(alarm: Alarm) {
        this.stop()
        this.active.value = true

        if (alarm.vibration) {
            Vibration(longArrayOf(0, 500, 300), intArrayOf(0, 255, 0), repeat = true).start()
        }

        if (alarm.sound) {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            activeSound = RingtoneManager.getRingtone(ApplicationContext, uri)?.also { sound ->
                sound.audioAttributes = AudioAttributes.Builder()
                    .setUsage(if (alarm.ignoreMute) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                sound.volume = when (alarm.volume) {
                    AlarmVolume.Low -> 0.3f
                    AlarmVolume.Normal -> 0.6f
                    AlarmVolume.Loud, AlarmVolume.Increasing -> 1.0f
                }
                sound.isLooping = true
                sound.play()
            }
        }
    }

    actual fun stop() {
        activeSound?.stop()
        activeSound = null
        Vibration.stop()
        this.active.value = false
    }
}