package tech.mmarca.openwatch.wear

/**
 * Whether the watch should hold the processor awake because the wearer
 * seems to be falling asleep, with no help from bedtime mode. Pure and
 * driven by the minutes the recorder closes, so it is testable without a
 * device. See [BedtimeHold] for why holding is needed at all.
 *
 * Start: the last [Config.quietMinutes] minutes were all worn, still (no
 * steps, no screen, little movement), and the heart rate fell: the mean of
 * the later half is at least [Config.fallBpm] under the earlier half and at
 * least [Config.belowLevelBpm] under the wearer's recent level. The level
 * is the median of the last [Config.levelReadings] readings seen while not
 * holding, so a long quiet sit at a steady pulse never starts a hold. Each
 * half needs a reading, three in all, so a sparse day setting can still
 * start it.
 *
 * Stop: [Config.wakeMinutesToRelease] waking minutes in a row (screen on,
 * steps, or movement of at least [Config.wakeMovement]), the watch off the
 * wrist or on the charger, or [Config.maxHoldMinutes]. After the cap the
 * hold waits [Config.cooldownMinutes] before it may start again. A turn in
 * bed is one or two waking minutes and does not stop it.
 *
 * Against false starts: by day ([Minute.daytime]) the quiet run must be
 * [Config.quietMinutesByDay] long, since sitting still to read or watch a film
 * lowers the pulse too; and when the wearer uses the phone ([onPhoneActive],
 * an unlock the phone reports), a hold this class started ends at once and
 * none starts for [Config.phoneCooldownMinutes]. A glance at the watch is not
 * such a signal: its screen-on minute is one waking minute of the five.
 *
 * Costs battery: the processor stays awake for as long as the hold lasts,
 * up to the cap.
 */
class SleepHold(private val config: Config = Config()) {

    data class Config(
        val quietMinutes: Int = 10,
        val quietMinutesByDay: Int = 20,
        val phoneCooldownMinutes: Int = 30,
        val quietMovement: Float = 2f,
        val wakeMovement: Float = 8f,
        val fallBpm: Float = 2f,
        val belowLevelBpm: Float = 4f,
        val levelReadings: Int = 180,
        val minLevelReadings: Int = 20,
        val wakeMinutesToRelease: Int = 5,
        val maxHoldMinutes: Int = 10 * 60,
        val cooldownMinutes: Int = 60,
    )

    /** One closed minute: what the recorder knows about it. */
    data class Minute(
        val epochMillis: Long,
        val movement: Float,
        val bpm: Int?,
        val screenOn: Boolean,
        val steps: Int,
        val worn: Boolean,
        /** Between 06:00 and 22:00 local, when a longer quiet run is needed. */
        val daytime: Boolean = false,
    ) {
        val waking: Boolean get() = screenOn || steps > 0
    }

    private val recent = ArrayDeque<Minute>()
    private val levelSamples = ArrayDeque<Int>()
    private var holding = false
    private var heldMinutes = 0
    private var wakeStreak = 0
    private var coolUntil = Long.MIN_VALUE

    /** True while the hold wants the processor awake. */
    val isHolding: Boolean get() = holding

    /** Feeds one closed minute, oldest first. Returns [isHolding] after it. */
    fun onMinute(minute: Minute): Boolean {
        if (!minute.worn) {
            reset()
            return holding
        }
        val last = recent.lastOrNull()
        if (last != null && minute.epochMillis - last.epochMillis != MINUTE_MILLIS) recent.clear()
        recent.addLast(minute)
        while (recent.size > maxOf(config.quietMinutes, config.quietMinutesByDay)) recent.removeFirst()
        if (holding) hold(minute) else {
            minute.bpm?.let { levelSamples.addLast(it) }
            while (levelSamples.size > config.levelReadings) levelSamples.removeFirst()
            if (minute.epochMillis >= coolUntil && shouldStart()) {
                holding = true
                heldMinutes = 0
                wakeStreak = 0
            }
        }
        return holding
    }

    /**
     * The wearer used the phone at [epochMillis]: they are awake. A hold
     * ends, and none starts for [Config.phoneCooldownMinutes].
     */
    fun onPhoneActive(epochMillis: Long) {
        reset()
        coolUntil = maxOf(coolUntil, epochMillis + config.phoneCooldownMinutes * MINUTE_MILLIS)
    }

    /** The watch went on the charger or lost its wrist: nothing is held, and the evidence starts again. */
    fun onNotWorn() = reset()

    private fun reset() {
        holding = false
        recent.clear()
        wakeStreak = 0
    }

    private fun hold(minute: Minute) {
        heldMinutes++
        val awake = minute.waking || minute.movement >= config.wakeMovement
        wakeStreak = if (awake) wakeStreak + 1 else 0
        if (wakeStreak >= config.wakeMinutesToRelease) {
            holding = false
            recent.clear()
        } else if (heldMinutes >= config.maxHoldMinutes) {
            holding = false
            recent.clear()
            coolUntil = minute.epochMillis + config.cooldownMinutes * MINUTE_MILLIS
        }
    }

    private fun shouldStart(): Boolean {
        val need = if (recent.lastOrNull()?.daytime == true) config.quietMinutesByDay else config.quietMinutes
        if (recent.size < need) return false
        val run = recent.toList().takeLast(need)
        if (run.any { it.waking || it.movement > config.quietMovement }) return false
        if (levelSamples.size < config.minLevelReadings) return false
        val half = run.size / 2
        val earlier = run.take(half).mapNotNull { it.bpm }
        val later = run.drop(half).mapNotNull { it.bpm }
        if (earlier.isEmpty() || later.isEmpty() || earlier.size + later.size < MIN_READINGS) return false
        val laterMean = later.average()
        val level = levelSamples.sorted()[levelSamples.size / 2]
        return laterMean <= earlier.average() - config.fallBpm && laterMean <= level - config.belowLevelBpm
    }

    private companion object {
        const val MINUTE_MILLIS = 60_000L
        const val MIN_READINGS = 3
    }
}
