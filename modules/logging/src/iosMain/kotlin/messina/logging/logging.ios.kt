package messina.logging

import platform.Foundation.NSLog

@PublishedApi
internal actual fun log(level: LogLevel, tag: String, message: String) {
    NSLog("[%s] %s: %s", level.label, tag, message)
}