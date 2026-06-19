package messina.utils

import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import messina.ContextStore.Companion.ApplicationContext

private val vibrator: Vibrator by lazy {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ApplicationContext.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        ApplicationContext.getSystemService(Vibrator::class.java)
    }
}

actual class Vibration actual constructor(
    private val timings: LongArray,
    private val amplitudes: IntArray,
    private val repeat: Boolean,
) {
    actual fun start() {
        val effect = VibrationEffect.createWaveform(timings, amplitudes, if (repeat) 0 else -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(
                effect,
                VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build(),
            )
        } else {
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        }
    }

    actual companion object {
        actual fun stop() = vibrator.cancel()
    }
}
