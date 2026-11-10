package tech.mmarca.openwatch.wear

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeRange
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import android.graphics.drawable.Icon
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.mmarca.openwatch.wear.ui.components.formatMetricValue
import tech.mmarca.openwatch.wearlink.AscentCredit

/**
 * Today's values for a watch face, from the watch's own rows as the tiles
 * read them ([WearReadingsSource.today]). A metric with nothing to show gives
 * no data, and the watch face shows its own empty state; a day's count is
 * valid until midnight, so yesterday's never lingers. The system asks again
 * every ten minutes (the manifest's update period). No Play Services.
 */
abstract class MetricComplicationService : SuspendingComplicationDataSourceService() {

    protected abstract val iconRes: Int

    /** What the complication shows from [today], or null for nothing. */
    internal abstract fun read(today: DayReadings, now: Instant, endOfDay: Instant, type: ComplicationType): ComplicationData?

    /** A sample value for the watch face's picker. */
    internal abstract fun preview(type: ComplicationType): ComplicationData?

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val endOfDay = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant()
        read(WearReadingsSource(MetricStore(this@MetricComplicationService)).today(), Instant.now(), endOfDay, request.complicationType)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = preview(type)

    protected fun shortText(text: String, title: String?, description: String, validUntil: Instant?): ShortTextComplicationData =
        ShortTextComplicationData.Builder(PlainComplicationText.Builder(text).build(), PlainComplicationText.Builder(description).build())
            .setMonochromaticImage(MonochromaticImage.Builder(Icon.createWithResource(this, iconRes)).build())
            .apply { title?.let { setTitle(PlainComplicationText.Builder(it).build()) } }
            .apply { validUntil?.let { setValidTimeRange(TimeRange.before(it)) } }
            .build()

    protected fun range(value: Double, max: Double, text: String, description: String, validUntil: Instant?): RangedValueComplicationData =
        RangedValueComplicationData.Builder(value.coerceIn(0.0, max).toFloat(), 0f, max.toFloat(), PlainComplicationText.Builder(description).build())
            .setText(PlainComplicationText.Builder(text).build())
            .setMonochromaticImage(MonochromaticImage.Builder(Icon.createWithResource(this, iconRes)).build())
            .apply { validUntil?.let { setValidTimeRange(TimeRange.before(it)) } }
            .build()

}

/**
 * The newest heart rate, no older than [FRESH_MINUTES]: a watch face shows
 * the number without its age, so an old one would pass for now.
 */
class HeartRateComplicationService : MetricComplicationService() {
    override val iconRes: Int = R.drawable.ic_metric_heart_rate

    override fun read(today: DayReadings, now: Instant, endOfDay: Instant, type: ComplicationType): ComplicationData? {
        val sample = today.latestHeartRate ?: return null
        val validUntil = Instant.ofEpochMilli(sample.epochMillis).plusSeconds(FRESH_MINUTES * 60)
        if (!validUntil.isAfter(now)) return null
        return heartRate(sample.bpm.toString(), type, validUntil)
    }

    override fun preview(type: ComplicationType): ComplicationData? = heartRate("72", type, null)

    private fun heartRate(bpm: String, type: ComplicationType, validUntil: Instant?): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        val unit = getString(R.string.unit_bpm)
        return shortText(bpm, unit, "${getString(R.string.metric_heart_rate)}: $bpm $unit", validUntil)
    }

    private companion object {
        const val FRESH_MINUTES = 10L
    }
}

/** Today's steps, as a number or as their share of a daily goal of [GOAL_STEPS]. */
class StepsComplicationService : MetricComplicationService() {
    override val iconRes: Int = R.drawable.ic_metric_steps

    override fun read(today: DayReadings, now: Instant, endOfDay: Instant, type: ComplicationType): ComplicationData? {
        if (!today.hasSteps) return null
        return steps(today.stepsByHour.sum(), type, endOfDay)
    }

    override fun preview(type: ComplicationType): ComplicationData? = steps(5_432.0, type, null)

    private fun steps(steps: Double, type: ComplicationType, validUntil: Instant?): ComplicationData? {
        val text = "%,d".format(steps.roundToInt())
        val description = "${getString(R.string.metric_steps)}: $text"
        return when (type) {
            ComplicationType.SHORT_TEXT -> shortText(text, getString(R.string.unit_steps), description, validUntil)
            ComplicationType.RANGED_VALUE -> range(steps, GOAL_STEPS, text, description, validUntil)
            else -> null
        }
    }

    private companion object {
        /** The phone app's default daily step goal. */
        const val GOAL_STEPS = 8_000.0
    }
}

/** Today's floors climbed, from the barometer's credited ascent. */
class FloorsComplicationService : MetricComplicationService() {
    override val iconRes: Int = R.drawable.ic_metric_floors

    override fun read(today: DayReadings, now: Instant, endOfDay: Instant, type: ComplicationType): ComplicationData? {
        if (!today.hasAscent || type != ComplicationType.SHORT_TEXT) return null
        return floors((today.ascentByHour.sum() / AscentCredit.METRES_PER_FLOOR).roundToInt(), endOfDay)
    }

    override fun preview(type: ComplicationType): ComplicationData? = if (type == ComplicationType.SHORT_TEXT) floors(7, null) else null

    private fun floors(floors: Int, validUntil: Instant?): ComplicationData =
        shortText(floors.toString(), getString(R.string.unit_floors), "${getString(R.string.metric_floors)}: $floors", validUntil)
}

/**
 * Today's distance, as the phone estimates it from the step minutes and last
 * sent it (`PUT_DAY`). Nothing before the phone sent the day.
 */
class DistanceComplicationService : MetricComplicationService() {
    override val iconRes: Int = R.drawable.ic_metric_distance

    override fun read(today: DayReadings, now: Instant, endOfDay: Instant, type: ComplicationType): ComplicationData? {
        val metres = DaySummaryStore(this).today()?.distanceMetres ?: return null
        return distance(metres.toDouble(), type, endOfDay)
    }

    override fun preview(type: ComplicationType): ComplicationData? = distance(4_890.0, type, null)

    private fun distance(metres: Double, type: ComplicationType, validUntil: Instant?): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        val text = formatMetricValue(MetricTile.DISTANCE, metres, UnitSystem.METRIC)
        val unit = getString(R.string.unit_km)
        return shortText(text, unit, "${getString(R.string.metric_distance)}: $text $unit", validUntil)
    }
}

/** Today's walking energy, as the phone estimates it and last sent it (`PUT_DAY`). */
class ActiveCaloriesComplicationService : MetricComplicationService() {
    override val iconRes: Int = R.drawable.ic_metric_active_calories

    override fun read(today: DayReadings, now: Instant, endOfDay: Instant, type: ComplicationType): ComplicationData? {
        val kcal = DaySummaryStore(this).today()?.activeKcal ?: return null
        return calories(kcal, type, endOfDay)
    }

    override fun preview(type: ComplicationType): ComplicationData? = calories(312, type, null)

    private fun calories(kcal: Int, type: ComplicationType, validUntil: Instant?): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        val text = "%,d".format(kcal)
        val unit = getString(R.string.unit_kcal)
        return shortText(text, unit, "${getString(R.string.metric_active_calories)}: $text $unit", validUntil)
    }
}
