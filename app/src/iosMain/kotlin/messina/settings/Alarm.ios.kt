package messina.settings

import messina.utils.Vibration
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

actual object AlarmController {
    actual val active = MutableStateFlow(false)
    private var audioPlayer: AVAudioPlayer? = null

    @OptIn(ExperimentalForeignApi::class)
    actual fun start(alarm: Alarm) {
        this.stop()
        this.active.value = true

        if (alarm.vibration) {
            Vibration(longArrayOf(0, 500, 300), intArrayOf(0, 255, 0), repeat = true).start()
        }

        // Make it run on the main thread so it doesn't silently fail
        dispatch_async(dispatch_get_main_queue()) {
            if (alarm.sound) {
                @OptIn(ExperimentalForeignApi::class)
                AVAudioSession.sharedInstance().let { session ->
                    val category =
                        if (alarm.ignoreMute) AVAudioSessionCategoryPlayback else AVAudioSessionCategoryAmbient
                    session.setCategory(category, error = null)
                    session.setActive(true, error = null)
                }

                val url = NSBundle.mainBundle().URLForResource("alarm", withExtension = "caf")
                    ?: NSBundle.mainBundle().URLForResource("alarm", withExtension = "mp3")
                    ?: NSURL.fileURLWithPath("/System/Library/Audio/UISounds/alarm.caf")

                audioPlayer = AVAudioPlayer(contentsOfURL = url, error = null).also { player ->
                    player.numberOfLoops = -1
                    player.prepareToPlay()
                    player.play()
                }
            }
        }
    }

    actual fun stop() {
        audioPlayer?.stop()
        audioPlayer = null
        Vibration.stop()
        this.active.value = false
    }
}
