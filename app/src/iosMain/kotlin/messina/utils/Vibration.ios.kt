package messina.utils

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSTimer
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private var alarmTimer: NSTimer? = null

actual class Vibration actual constructor(
    private val timings: LongArray,
    private val amplitudes: IntArray,
    private val repeat: Boolean,
) {
    @OptIn(ExperimentalForeignApi::class)
    actual fun start() {
        dispatch_async(dispatch_get_main_queue()) {
            alarmTimer?.invalidate()
            alarmTimer = null

            val generator =
                UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy)
            generator.prepare()
            generator.impactOccurred()

            if (repeat) {
                alarmTimer = NSTimer.scheduledTimerWithTimeInterval(
                    interval = timings.sum().coerceAtLeast(1) / 1000.0,
                    repeats = true,
                    block = { generator.impactOccurred() },
                )
            }
        }
    }

    actual companion object {
        @OptIn(ExperimentalForeignApi::class)
        actual fun stop() {
            dispatch_async(dispatch_get_main_queue()) {
                alarmTimer?.invalidate()
                alarmTimer = null
            }
        }
    }
}
