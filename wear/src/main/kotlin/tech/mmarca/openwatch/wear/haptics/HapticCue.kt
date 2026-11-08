package tech.mmarca.openwatch.wear.haptics

/** How the watch buzzes. Pure data, so the choice can be tested off the watch. */
enum class HapticCue(
    /** Alternating off and on times in milliseconds; it starts with an off time. */
    val timings: LongArray,
) {
    /** One short pulse: a result is in, or a workout was saved. */
    PULSE(longArrayOf(0, 80)),

    /** Two short pulses: a measurement failed. */
    DOUBLE_PULSE(longArrayOf(0, 80, 120, 80)),
}

/** The cue for the end of a workout: a pulse when it was saved, nothing for a slip that was dropped. */
fun hapticCueForWorkoutEnd(saved: Boolean): HapticCue? = if (saved) HapticCue.PULSE else null
