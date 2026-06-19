package messina.logging

import android.util.Log

@PublishedApi
internal actual fun log(level: LogLevel, tag: String, message: String) {
    when (level) {
        LogLevel.TRACE -> Log.v(tag, message)
        LogLevel.DEBUG -> Log.d(tag, message)
        LogLevel.INFO  -> Log.i(tag, message)
        LogLevel.WARN  -> Log.w(tag, message)
        LogLevel.ERROR -> Log.e(tag, message)
    }
}