package tech.mmarca.openwatch.wear

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

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
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val pastDays = HashMap<LocalDate, DayReadings>()

    /** The tiles' values now, read again every [REFRESH_MILLIS] while someone collects. */
    fun metrics(): Flow<Map<MetricTile, MetricUiState>> = flow {
        while (true) {
            emit(load())
            delay(REFRESH_MILLIS)
        }
    }.flowOn(Dispatchers.IO)

    internal fun load(): Map<MetricTile, MetricUiState> {
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val days = (DAYS - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            if (back == 0) read(date, zone) else pastDays.getOrPut(date) { read(date, zone) }
        }
        pastDays.keys.retainAll { it >= today.minusDays(DAYS.toLong()) }
        return WearReadings.metrics(days, clock.instant(), zone)
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
        )
    }

    private companion object {
        const val DAYS = 7
        const val REFRESH_MILLIS = 30_000L

        /** Above a day's most: a heart rate sample every ten seconds is 8 640. */
        const val ROW_LIMIT = 10_000
    }
}
