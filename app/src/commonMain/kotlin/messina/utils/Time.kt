package messina.utils

import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char

class Time(private val local: LocalDateTime, private val tz: TimeZone) {
    fun toHHMM(): String {
        return local.format(LocalDateTime.Format {
            hour()
            char(':')
            minute()
        })
    }

    fun toFullTimestamp(): String {
        return local.format(LocalDateTime.Format {
            year()
            char('/')
            monthNumber()
            char('/')
            day()
            char(' ')
            hour()
            char(':')
            minute()
            char(':')
            second()
        })
    }

    fun toDayMonth(): String {
        return local.format(LocalDateTime.Format {
            monthName(MonthNames.ENGLISH_FULL)
            char(' ')
            day()
        })
    }

    fun day(): Instant = local.date.atStartOfDayIn(tz)

    fun toInstant(): Instant = local.toInstant(tz)

    companion object {
        fun fromInstant(instant: Instant): Time {
            val tz = TimeZone.currentSystemDefault()
            return Time(instant.toLocalDateTime(tz), tz)
        }
    }
}
