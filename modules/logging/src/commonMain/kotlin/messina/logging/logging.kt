package messina.logging

enum class LogLevel(val label: String) {
    TRACE("TRACE"),
    DEBUG("DEBUG"),
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR"),
}

object Logger {
    var name: String = "Application"
    var level: LogLevel = LogLevel.DEBUG

    fun isEnabled(logLevel: LogLevel): Boolean = logLevel.ordinal >= level.ordinal
}

@PublishedApi
internal expect fun log(level: LogLevel, tag: String, message: String)

inline fun trace(tag: String = Logger.name, message: () -> String) {
    if (Logger.isEnabled(LogLevel.TRACE)) {
        log(LogLevel.TRACE, tag, message())
    }
}

inline fun debug(tag: String = Logger.name, message: () -> String) {
    if (Logger.isEnabled(LogLevel.DEBUG)) {
        log(LogLevel.DEBUG, tag, message())
    }
}

inline fun info(tag: String = Logger.name, message: () -> String) {
    if (Logger.isEnabled(LogLevel.INFO)) {
        log(LogLevel.INFO, tag, message())
    }
}

inline fun warn(tag: String = Logger.name, message: () -> String) {
    if (Logger.isEnabled(LogLevel.WARN)) {
        log(LogLevel.WARN, tag, message())
    }
}

inline fun error(tag: String = Logger.name, message: () -> String) {
    if (Logger.isEnabled(LogLevel.ERROR)) {
        log(LogLevel.ERROR, tag, message())
    }
}