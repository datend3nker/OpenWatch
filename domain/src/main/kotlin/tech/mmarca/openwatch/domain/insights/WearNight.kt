package tech.mmarca.openwatch.domain.insights

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import tech.mmarca.openwatch.domain.model.WearSleepMinute

/** The window a night is estimated from: the evening before through the next early afternoon. */
fun sleepNightWindow(night: LocalDate, offset: ZoneOffset): Pair<Instant, Instant> = Pair(
    night.minusDays(1).atTime(NightWindowStart).toInstant(offset),
    night.atTime(NightWindowEnd).toInstant(offset),
)

/**
 * One night from the watch's minutes, as the phone and the watch both
 * compute it: the minutes in the night's window, the estimated session and
 * the resting heart rate. Pure.
 */
object WearNight {

    /** The rows inside the window of [night]. */
    fun minutesOf(minutes: List<WearSleepMinute>, night: LocalDate, offset: ZoneOffset): List<WearSleepMinute> {
        val (from, to) = sleepNightWindow(night, offset)
        return minutes.filter { !it.time.isBefore(from) && it.time.isBefore(to) }
    }

    /** The estimated night for [night], or null when the minutes hold no night. */
    fun estimate(minutes: List<WearSleepMinute>, night: LocalDate, offset: ZoneOffset): WearSleepEstimator.Night? =
        WearSleepEstimator.estimate(minutesOf(minutes, night, offset))

    /**
     * The numbers behind [night], as key and value in a fixed order: what the log of a
     * debug run shows. The same numbers [WearSleepEstimator.Night] holds, nothing recomputed.
     */
    fun reasons(night: WearSleepEstimator.Night): List<Pair<String, Any>> {
        val session = night.session
        return listOf(
            "bedTime" to night.bedTime.toEpochMilli(),
            "onset" to session.onset.toEpochMilli(),
            "end" to session.end.toEpochMilli(),
            "inProgress" to session.inProgress,
            "sleepMin" to session.sleepMinutes,
            "awakeMin" to session.awakeMinutes,
            "unknownMin" to session.unknownMinutes,
            "windowMin" to night.window.length,
            "windowSource" to night.window.source,
            "wornMin" to night.wornMinutes,
            "notWornMin" to night.notWornMinutes,
            "notWorn" to night.notWornReasons.entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}:${it.value}" },
        )
    }

    /** The resting heart rate of a finished night, or null while it runs or when too few minutes count. */
    fun restingHeartRate(minutes: List<WearSleepMinute>, night: WearSleepEstimator.Night): Int? {
        val session = night.session
        if (session.inProgress) return null
        return NocturnalRestingHeartRate.of(minutes, session.onset, session.end)
    }
}

private val NightWindowStart: LocalTime = LocalTime.of(18, 0)
private val NightWindowEnd: LocalTime = LocalTime.of(14, 0)
