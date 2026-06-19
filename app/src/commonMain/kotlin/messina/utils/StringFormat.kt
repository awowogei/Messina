package messina.utils

import kotlin.jvm.JvmName
import kotlin.math.*

private class FormatSpec(
    val flags: Set<Char>,   // e.g. '-', '0', '+', ' '
    val width: Int?,
    val precision: Int?,
    val type: Char          // e.g. 'd', 'f', 's', 'x'
) {
    companion object {
        fun parse(spec: String): FormatSpec {
            var i = 0
            val flags = mutableSetOf<Char>()

            while (i < spec.length && spec[i] in "-0+ #") flags.add(spec[i++])

            val widthStart = i
            while (i < spec.length && spec[i].isDigit()) i++
            val width = if (i > widthStart) spec.substring(widthStart, i).toInt() else null

            var precision: Int? = null
            if (i < spec.length && spec[i] == '.') {
                i++
                val precStart = i
                while (i < spec.length && spec[i].isDigit()) i++
                precision = if (i > precStart) spec.substring(precStart, i).toInt() else 0
            }

            if (i >= spec.length) throw IllegalArgumentException("Missing type char in spec: '$spec'")
            return FormatSpec(flags, width, precision, spec[i])
        }
    }

    fun format(value: Long): String {
        val negative = value < 0
        // Note: Long.MIN_VALUE negation overflows — acceptable edge case
        val absVal = if (negative) -value else value

        val digits = when (this.type) {
            'd' -> absVal.toString()
            'x' -> absVal.toString(16)
            'X' -> absVal.toString(16).uppercase()
            'o' -> absVal.toString(8)
            'b' -> absVal.toString(2)
            else -> throw IllegalArgumentException("Unknown int format type: '${this.type}'")
        }

        val prefix = when {
            negative -> "-"
            '+' in this.flags -> "+"
            ' ' in this.flags -> " "
            else -> ""
        }

        // Zero-pad fills the digit field, not the prefix
        val zeroPad = '0' in this.flags && '-' !in this.flags
        val paddedDigits = if (zeroPad && this.width != null) {
            digits.padStart((this.width - prefix.length).coerceAtLeast(digits.length), '0')
        } else digits

        return applyWidth(prefix + paddedDigits, this.width, this.flags)
    }

    fun format(value: Double): String {
        fun pow10(n: Int): Long {
            var result = 1L
            repeat(n) { result *= 10L }
            return result
        }

        // Rounds to `precision` decimal places using integer arithmetic to avoid
        // floating-point drift in the fractional portion.
        fun formatFixed(absVal: Double, precision: Int): String {
            val factor = pow10(precision)
            val rounded = round(absVal * factor.toDouble()).toLong()
            val intPart = rounded / factor
            val fracPart = rounded % factor
            return if (precision == 0) "$intPart"
            else "$intPart.${fracPart.toString().padStart(precision, '0')}"
        }

        fun formatScientific(absVal: Double, precision: Int, uppercase: Boolean): String {
            val eLetter = if (uppercase) "E" else "e"
            if (absVal == 0.0) {
                val frac = if (precision > 0) "." + "0".repeat(precision) else ""
                return "0${frac}${eLetter}+00"
            }
            val exp = floor(log10(absVal)).toInt()
            val mantissa = absVal / 10.0.pow(exp.toDouble())
            val expSign = if (exp >= 0) "+" else "-"
            val expStr = abs(exp).toString().padStart(2, '0')
            return "${formatFixed(mantissa, precision)}$eLetter$expSign$expStr"
        }

        // %g: use scientific if exp < -4 or exp >= precision, otherwise fixed,
        // and strip trailing zeros.
        fun formatGeneral(absVal: Double, precision: Int, uppercase: Boolean): String {
            val p = if (precision == 0) 1 else precision
            if (absVal == 0.0) return "0"
            val exp = floor(log10(absVal)).toInt()
            return if (exp < -4 || exp >= p) {
                formatScientific(absVal, p - 1, uppercase)
            } else {
                val decimalPlaces = (p - 1 - exp).coerceAtLeast(0)
                formatFixed(absVal, decimalPlaces).trimEnd('0').trimEnd('.')
            }
        }

        val negative = value < 0.0
        val absVal = abs(value)

        val formatted = when (this.type) {
            'f' -> formatFixed(absVal, this.precision ?: 6)
            'e' -> formatScientific(absVal, this.precision ?: 6, uppercase = false)
            'E' -> formatScientific(absVal, this.precision ?: 6, uppercase = true)
            'g', 'G' -> formatGeneral(absVal, this.precision ?: 6, uppercase = this.type == 'G')
            else -> throw IllegalArgumentException("Unknown float format type: '${this.type}'")
        }

        val prefix = when {
            negative -> "-"
            '+' in this.flags -> "+"
            ' ' in this.flags -> " "
            else -> ""
        }

        val zeroPad = '0' in this.flags && '-' !in this.flags
        val paddedFormatted = if (zeroPad && this.width != null) {
            formatted.padStart((this.width - prefix.length).coerceAtLeast(formatted.length), '0')
        } else formatted

        return applyWidth(prefix + paddedFormatted, this.width, this.flags)
    }

    fun format(value: String): String {
        if (this.type != 's') throw IllegalArgumentException("StringFormatArg only handles %s, got '${this.type}'")
        val s =
            if (this.precision != null && this.precision < value.length) value.take(this.precision) else value
        return applyWidth(s, this.width, this.flags)
    }

}

private fun applyWidth(s: String, width: Int?, flags: Set<Char>): String {
    if (width == null || s.length >= width) return s
    val pad = " ".repeat(width - s.length)
    return if ('-' in flags) s + pad else pad + s
}

@JvmName("_format")
fun String.format(vararg args: Any?): String = messina.utils.format(this, *args)
fun format(template: String, vararg args: Any?): String {
    val sb = StringBuilder()
    var i = 0
    var argIndex = 0

    while (i < template.length) {
        if (template[i] != '%') {
            sb.append(template[i++]); continue
        }
        i++ // skip '%'
        if (i >= template.length) throw IllegalArgumentException("Trailing % in format string")
        if (template[i] == '%') {
            sb.append('%'); i++; continue
        }

        val specStart = i
        while (i < template.length && template[i] in "-0+ #") i++ // flags
        while (i < template.length && template[i].isDigit()) i++  // width
        if (i < template.length && template[i] == '.') {          // precision
            i++
            while (i < template.length && template[i].isDigit()) i++
        }
        if (i >= template.length) throw IllegalArgumentException("Incomplete specifier in: '$template'")
        i++ // type char

        val spec = FormatSpec.parse(template.substring(specStart, i))
        if (argIndex >= args.size) throw IllegalArgumentException("Too few arguments for: '$template'")
        when (val it = args[argIndex++]) {
            is Int -> sb.append(spec.format(it.toLong()))
            is Long -> sb.append(spec.format(it))
            is Double -> sb.append(spec.format(it))
            is Float -> sb.append(spec.format(it.toDouble()))
            is String -> sb.append(spec.format(it))
            else -> throw IllegalArgumentException("Unsupported argument type: ${it?.let { it::class.simpleName } ?: "null"}")
        }
    }

    return sb.toString()
}
