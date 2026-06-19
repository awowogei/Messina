package messina

import messina.settings.GlucoseUnit
import messina.settings.Settings
import messina.utils.format
import kotlinx.serialization.Serializable

@Serializable
class Glucose private constructor(private val mgdl: Double) {
    val value: Double
        get() = when (Settings.glucoseUnit) {
            GlucoseUnit.MgDl -> mgdl
            GlucoseUnit.Mmol -> toMmol()
        }

    fun toMgDl(): Double = this.mgdl
    fun toMmol(): Double = this.mgdl / 18.0

    fun format(decimals: Int = 1): String = when (Settings.glucoseUnit) {
        GlucoseUnit.Mmol -> "%.${decimals}f".format(value)
        GlucoseUnit.MgDl -> "%.0f".format(value)
    }

    override fun toString(): String {
        return "${this.format()} ${Settings.glucoseUnit}"
    }

    companion object {
        fun fromMmol(mmol: Double) = Glucose(mmol * 18.0)
        fun fromMgDl(mgdl: Double) = Glucose(mgdl)
    }
}