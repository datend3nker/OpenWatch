package tech.mmarca.openwatch.wear

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import tech.mmarca.openwatch.wearlink.DaySummary
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.SkinMinute

/**
 * What the watch itself recorded, as the dashboard and the detail screens
 * read it: today by the hour and the last seven days by the day.
 *
 * A day that is over is read from the store once and kept. Only today is
 * read again, so a refresh costs one day of rows, not the week's. A
 * heart-rate day is the largest, up to some 8 600 rows.
 */
internal class WearReadingsSource(
    private val store: MetricStore,
    private val phoneDays: () -> Map<Long, DaySummary> = { emptyMap() },
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val pastDays = HashMap<LocalDate, DayReadings>()

    /** The tiles' values and the last night's stages. */
    data class Readings(val metrics: Map<MetricTile, MetricUiState>, val sleepStages: Map<SleepStage, Double>)

    /** The tiles' values now, read again every [REFRESH_MILLIS] while someone collects. */
    fun metrics(): Flow<Readings> = flow {
        while (true) {
            emit(load())
            delay(REFRESH_MILLIS)
        }
    }.flowOn(Dispatchers.IO)

    internal fun load(): Readings {
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val days = (DAYS - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            if (back == 0) read(date, zone) else pastDays.getOrPut(date) { read(date, zone) }
        }
        pastDays.keys.retainAll { it >= today.minusDays(DAYS.toLong()) }
        val phone = phoneDays()
        return Readings(WearReadings.metrics(days, clock.instant(), zone, phone), WearReadings.sleepStages(phone))
    }

    /** Today only, read now: what the Today tile shows. */
    fun today(): DayReadings = read(LocalDate.now(clock), clock.zone)

    private fun read(date: LocalDate, zone: ZoneId): DayReadings {
        // A row's time is its minute or sample start; a day is [midnight, next midnight).
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val until = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return WearReadings.day(
            date = date,
            zone = zone,
            heartRate = store.since(WearMetrics.HEART_RATE, from, ROW_LIMIT, until),
            steps = store.since(WearMetrics.STEPS, from, ROW_LIMIT, until),
            ascent = store.since(WearMetrics.ASCENT, from, ROW_LIMIT, until),
            night = mapOf(
                MetricTile.HRV to store.since(WearMetrics.HRV, from, ROW_LIMIT, until).map { it.rmssdMillis },
                MetricTile.BLOOD_OXYGEN to store.since(WearMetrics.SPO2, from, ROW_LIMIT, until).map { it.percent.toDouble() },
                MetricTile.SKIN_TEMPERATURE to skinByQuarterHour(store.since(WearMetrics.SKIN_TEMPERATURE, from, ROW_LIMIT, until)),
                MetricTile.RESPIRATORY_RATE to store.since(WearMetrics.RESPIRATORY_RATE, from, ROW_LIMIT, until)
                    .map { it.breathsPerMinute },
            ),
        )
    }

    /** A night of skin temperature is a reading a minute; the chart takes the mean of each quarter hour. */
    private fun skinByQuarterHour(minutes: List<SkinMinute>): List<Double> =
        minutes.groupBy { it.epochMillis / QUARTER_HOUR_MILLIS }.toSortedMap().values.map { q -> q.map { it.skinCelsius }.average() }

    private companion object {
        const val QUARTER_HOUR_MILLIS = 15 * 60_000L
        const val DAYS = 7
        const val REFRESH_MILLIS = 30_000L

        /** Above a day's most: a heart rate sample every ten seconds is 8 640. */
        const val ROW_LIMIT = 10_000
    }
}
