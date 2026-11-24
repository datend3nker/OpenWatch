package tech.mmarca.openwatch.wearlink

import kotlin.math.roundToLong

/**
 * One entry made on the watch: a value the wearer logged by hand (a glass of
 * water, a weight, a blood pressure, a body fat, a blood oxygen, a breathing
 * rate, a body temperature, a blood glucose, a height, a breathing session) or one a
 * spot measurement took (a heart rate, an HRV, a body composition's body fat, lean
 * body mass and body water), as `SINCE lg` carries it.
 *
 * The values are whole thousandths of the [Type]'s unit, so a value reads
 * back the same on both sides (the protocol's integers; a `Double` printed
 * as text can come out with an exponent). An entry is keyed on its time,
 * when it was logged: a session ([Type.MINDFULNESS]) ended then, and began
 * its minutes before.
 */
data class WearLinkEntry(
    val epochMillis: Long,
    val type: Type,
    /** The value in thousandths of [type]'s unit. */
    val value1000: Long,
    /** The second value in thousandths: the diastolic pressure of a [Type.BLOOD_PRESSURE]; null for the others. */
    val second1000: Long? = null,
    /** True for a value a sensor of the watch measured, false for one entered by hand. */
    val measured: Boolean = false,
) {
    /** The value in [type]'s unit. */
    val value: Double get() = value1000 / THOUSAND

    /** The second value in [type]'s unit, or null. */
    val second: Double? get() = second1000?.let { it / THOUSAND }

    /**
     * What an entry records, and the unit of its value. The [token] is what
     * the line carries: never renamed. A type this build does not know makes
     * the line unreadable to it, and it is skipped.
     */
    enum class Type(val token: String) {
        /** Millilitres drunk. */
        WATER("water"),

        /** Body weight, kilograms. */
        WEIGHT("weight"),

        /** Systolic pressure, mmHg; the diastolic is the second value. */
        BLOOD_PRESSURE("bp"),

        /** A breathing session, minutes. */
        MINDFULNESS("mindful"),

        /** Heart rate, beats per minute. */
        HEART_RATE("hr"),

        /** Heart rate variability as RMSSD, milliseconds. */
        HRV("hrv"),

        /** Body fat, percent of the body's mass. */
        BODY_FAT("bodyfat"),

        /** Blood oxygen saturation, percent. */
        BLOOD_OXYGEN("spo2"),

        /** Breathing rate, breaths per minute. */
        RESPIRATORY_RATE("breath"),

        /** Body temperature, degrees Celsius. */
        BODY_TEMPERATURE("temp"),

        /** Blood glucose, millimoles per litre. */
        BLOOD_GLUCOSE("glucose"),

        /** Height, centimetres. */
        HEIGHT("height"),

        /** Lean body mass, kilograms: what a body composition measurement leaves after the fat. */
        LEAN_BODY_MASS("lean"),

        /** Total body water, kilograms. */
        BODY_WATER("bodywater"),
        ;

        companion object {
            fun fromToken(token: String): Type? = entries.firstOrNull { it.token == token }
        }
    }

    companion object {
        private const val THOUSAND = 1000.0

        /** The entry for [value] (and [second]) in [type]'s unit, each rounded to the nearest thousandth. */
        fun of(epochMillis: Long, type: Type, value: Double, second: Double? = null, measured: Boolean = false): WearLinkEntry =
            WearLinkEntry(epochMillis, type, (value * THOUSAND).roundToLong(), second?.let { (it * THOUSAND).roundToLong() }, measured)
    }
}

/**
 * The line of a [WearLinkEntry]:
 *
 * `LG <epochMillis> <type> <value1000> <second1000|-> <m|h>`
 *
 * `type` is a [WearLinkEntry.Type] token (`water`, `weight`, `bp`, `mindful`,
 * `hr`, `hrv`, `bodyfat`, `spo2`, `breath`, `temp`, `glucose`, `height`, `lean`, `bodywater`); `value1000` and `second1000` are whole thousandths of its
 * unit, `-` for no second value; `m` marks a value a sensor measured, `h` one
 * entered by hand. A reader takes the six fields it knows and ignores any
 * after them, so the line may grow at its end.
 */
object WearLinkEntries {

    const val WORD: String = "LG"

    /** A value a sensor of the watch measured. */
    const val MEASURED: String = "m"

    /** A value the wearer entered by hand. */
    const val BY_HAND: String = "h"

    /** A million units: above any value a type can have, far below a `Long`'s end. */
    const val MAX_VALUE1000: Long = 1_000_000_000L

    private const val NO_VALUE = "-"
    private const val FIELDS = 6

    fun format(entry: WearLinkEntry): String =
        "$WORD ${entry.epochMillis} ${entry.type.token} ${entry.value1000} ${entry.second1000 ?: NO_VALUE} " +
            if (entry.measured) MEASURED else BY_HAND

    /**
     * Null unless [line] is a well-formed `LG` line: a time, a type this build
     * knows, a value above zero, a second value where the type has one (the
     * diastolic of `bp`), and the flag.
     */
    fun parse(line: String): WearLinkEntry? {
        val f = line.trim().split(' ').filter { it.isNotEmpty() }
        if (f.size < FIELDS || f[0] != WORD) return null
        val at = f[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val type = WearLinkEntry.Type.fromToken(f[2]) ?: return null
        val value = f[3].toLongOrNull()?.takeIf { it in 1..MAX_VALUE1000 } ?: return null
        val second = if (f[4] == NO_VALUE) null else f[4].toLongOrNull()?.takeIf { it in 1..MAX_VALUE1000 } ?: return null
        if (type == WearLinkEntry.Type.BLOOD_PRESSURE && second == null) return null
        val measured = when (f[5]) {
            MEASURED -> true
            BY_HAND -> false
            else -> return null
        }
        return WearLinkEntry(at, type, value, second, measured)
    }
}
