package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import tech.mmarca.openwatch.wearlink.DaySummary
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/**
 * The phone's figures per local day that the watch shows on its screens
 * (`PUT_DAY`): the longest night that ended on the day, with its stages, the
 * day's latest resting heart rate, and the active energy and distance Health
 * Connect holds. Pure, so it is testable without a device.
 */
object WearOsDaySummaries {

    /** One day's totals as Health Connect aggregates them; null when not read. */
    data class Totals(val activeKcal: Double?, val distanceMetres: Double?)

    private val AWAKE_STAGES = setOf(
        SleepSessionRecord.STAGE_TYPE_AWAKE,
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
    )

    fun summaries(
        days: List<LocalDate>,
        zone: ZoneId,
        sleep: List<SleepSessionRecord>,
        resting: List<RestingHeartRateRecord>,
        totals: Map<LocalDate, Totals>,
    ): List<DaySummary> = days.map { date ->
        val night = sleep.filter { it.endTime.atZone(zone).toLocalDate() == date }
            .maxByOrNull { Duration.between(it.startTime, it.endTime) }
        val restingBpm = resting.filter { it.time.atZone(zone).toLocalDate() == date }.maxByOrNull { it.time }?.beatsPerMinute
        val dayTotals = totals[date]
        val stages = night?.let(::stageMinutes)
        DaySummary(
            epochDay = date.toEpochDay(),
            sleepMinutes = night?.let { asleepMinutes(it, stages) },
            awakeMinutes = stages?.get(Stage.AWAKE),
            remMinutes = stages?.get(Stage.REM),
            lightMinutes = stages?.get(Stage.LIGHT),
            deepMinutes = stages?.get(Stage.DEEP),
            restingBpm = restingBpm?.toInt()?.coerceIn(0, WearLinkProtocol.MAX_BPM),
            activeKcal = dayTotals?.activeKcal?.let { Math.round(it).toInt() },
            distanceMetres = dayTotals?.distanceMetres?.let { Math.round(it).toInt() },
        )
    }

    private enum class Stage { AWAKE, REM, LIGHT, DEEP }

    /** Minutes per stage, or null for a session without stages. A plain "sleeping" stage counts as light. */
    private fun stageMinutes(session: SleepSessionRecord): Map<Stage, Int>? {
        if (session.stages.isEmpty()) return null
        val minutes = HashMap<Stage, Long>()
        for (stage in session.stages) {
            val kind = when (stage.stage) {
                in AWAKE_STAGES -> Stage.AWAKE
                SleepSessionRecord.STAGE_TYPE_REM -> Stage.REM
                SleepSessionRecord.STAGE_TYPE_DEEP -> Stage.DEEP
                SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_SLEEPING -> Stage.LIGHT
                else -> continue
            }
            minutes.merge(kind, Duration.between(stage.startTime, stage.endTime).toMinutes(), Long::plus)
        }
        return minutes.mapValues { (_, value) -> value.toInt().coerceIn(0, MINUTES_PER_DAY) }
    }

    /** The session without its awake stages; the whole session when it has none. */
    private fun asleepMinutes(session: SleepSessionRecord, stages: Map<Stage, Int>?): Int {
        val total = Duration.between(session.startTime, session.endTime).toMinutes().toInt()
        val asleep = if (stages == null) total else stages.filterKeys { it != Stage.AWAKE }.values.sum()
        return asleep.coerceIn(0, MINUTES_PER_DAY)
    }

    /** The local days from [count] - 1 days ago to today, oldest first. */
    fun lastDays(now: Instant, zone: ZoneId, count: Int): List<LocalDate> {
        val today = now.atZone(zone).toLocalDate()
        return (count - 1 downTo 0).map { today.minusDays(it.toLong()) }
    }

    private const val MINUTES_PER_DAY = 24 * 60
}
