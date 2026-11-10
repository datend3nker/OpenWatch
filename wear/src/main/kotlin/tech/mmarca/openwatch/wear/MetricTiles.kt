package tech.mmarca.openwatch.wear

import tech.mmarca.openwatch.wear.ui.components.formatMetricValue
import tech.mmarca.openwatch.wearlink.AscentCredit

/*
 * The metric tiles. Each reads today's rows for its metric and says
 * why when it has nothing to show: a permission that is off, or a sensor
 * the watch lacks. Distance and active calories are the phone's figures. The accents copy the phone app's `ui/theme/Color.kt`;
 * a change to that palette belongs here too.
 */

/**
 * The newest heart rate, the day's quarter-hour means, and its range. The
 * newest sample can be from before midnight, as long as it is under an hour
 * old ([TileHeartRate]); the curve and the range are today's only.
 */
class HeartRateTileService : MetricTileService() {

    /** `HeartColor`. */
    override val accent: Int = 0xFFD2497B.toInt()

    override val titleRes: Int = R.string.tile_heart_rate_label

    override fun read(day: TileDay, chart: TileChart, store: MetricStore): MetricTileContent {
        val granted = WearPermissions.hasHeartRate(this)
        val now = TileHeartRate.of(granted, store.latest(WearMetrics.HEART_RATE), day.nowMillis)
        val samples = if (granted) {
            store.since(WearMetrics.HEART_RATE, day.startMillis - 1, TileDay.MAX_HEART_RATE_SAMPLES_PER_DAY)
        } else {
            emptyList()
        }
        val (value, caption) = when (now) {
            TileHeartRate.NotGranted -> TILE_NO_VALUE to getString(R.string.tile_not_granted)
            TileHeartRate.None -> TILE_NO_VALUE to getString(R.string.tile_heart_rate_none)
            is TileHeartRate.Recent -> now.bpm.toString() to when (now.minutesAgo) {
                0L -> getString(R.string.tile_heart_rate_caption_now)
                else -> resources.getQuantityString(
                    R.plurals.tile_heart_rate_caption_minutes,
                    now.minutesAgo.toInt(),
                    now.minutesAgo.toInt(),
                )
            }
        }
        // Said as a sentence: the caption's unit belongs with the number, and its dot is not read out well.
        val spoken = when {
            now !is TileHeartRate.Recent -> caption
            now.minutesAgo == 0L -> getString(R.string.tile_heart_rate_spoken_now, now.bpm)
            else -> resources.getQuantityString(
                R.plurals.tile_heart_rate_spoken_minutes,
                now.minutesAgo.toInt(),
                now.bpm,
                now.minutesAgo.toInt(),
            )
        }
        val title = getString(titleRes)
        val detail = day.range(samples)?.let { getString(R.string.tile_heart_rate_range, it.first, it.last) }
        return MetricTileContent(
            title = title,
            value = value,
            caption = caption,
            accent = accent,
            description = listOfNotNull("$title: $spoken", detail).joinToString(", "),
            chart = chart.heartRate(day.meanPerBucket(samples), day),
            detail = detail,
        )
    }
}

/** The steps since midnight and their running total through the day. */
class StepsTileService : MetricTileService() {

    /** `StepsColor`. */
    override val accent: Int = 0xFF3F9A63.toInt()

    override val titleRes: Int = R.string.tile_steps_label

    override fun read(day: TileDay, chart: TileChart, store: MetricStore): MetricTileContent {
        val missing = when {
            !StepRecorder.hasStepCounter(this) -> R.string.tile_steps_no_sensor
            !WearPermissions.hasActivityRecognition(this) -> R.string.tile_not_granted
            else -> null
        }
        if (missing != null) return unavailable(getString(missing))
        val minutes = store.since(WearMetrics.STEPS, day.startMillis - 1, TileDay.MAX_MINUTES_PER_DAY)
        val running = day.cumulative(TileDay.stepAmounts(minutes))
        val title = getString(titleRes)
        val value = formatCount(running.last().toLong())
        val caption = getString(R.string.tile_steps_caption)
        return MetricTileContent(title, value, caption, accent, "$title: $value $caption", chart.cumulative(running, day))
    }
}

/**
 * The floors climbed since midnight: the barometer's metres that enough
 * steps carry, over three metres a floor, as the phone counts them before it
 * writes them to Health Connect. Whole floors and whole metres, both rounded
 * down, as the phone's dashboard shows floors.
 */
class FloorsTileService : MetricTileService() {

    /** `FloorsColor`. */
    override val accent: Int = 0xFFA8881F.toInt()

    override val titleRes: Int = R.string.tile_floors_label

    override fun read(day: TileDay, chart: TileChart, store: MetricStore): MetricTileContent {
        val missing = when {
            !AscentRecorder.hasBarometer(this) -> R.string.tile_floors_no_sensor
            // Height counts only with steps around it, so floors need the step counter too.
            !StepRecorder.hasStepCounter(this) -> R.string.tile_steps_no_sensor
            !WearPermissions.hasActivityRecognition(this) -> R.string.tile_not_granted
            else -> null
        }
        if (missing != null) return unavailable(getString(missing))
        val ascent = store.since(WearMetrics.ASCENT, day.startMillis - 1, TileDay.MAX_MINUTES_PER_DAY)
        // The minute before midnight carries the first minute's credit.
        val steps = store.since(WearMetrics.STEPS, day.startMillis - TileDay.MINUTE_MILLIS - 1, TileDay.MAX_MINUTES_PER_DAY + 1)
        val metres = day.cumulative(TileDay.creditedAscent(ascent, steps))
        val running = metres.map { it / AscentCredit.METRES_PER_FLOOR }
        val title = getString(titleRes)
        val value = formatCount(TileDay.wholeDown(running.last()))
        val caption = getString(R.string.tile_floors_caption)
        val detail = getString(R.string.tile_floors_climbed, formatCount(TileDay.wholeDown(metres.last())))
        return MetricTileContent(
            title = title,
            value = value,
            caption = caption,
            accent = accent,
            description = "$title: $value $caption, $detail",
            chart = chart.cumulative(running, day),
            detail = detail,
        )
    }
}

/**
 * The distance walked today, as the phone estimates it from the step minutes
 * and last sent it (`PUT_DAY`): the watch has no step length of its own. No
 * curve, since the phone sends the day's total only.
 */
class DistanceTileService : MetricTileService() {

    /** `DistanceColor`. */
    override val accent: Int = 0xFF3B7DD8.toInt()

    override val titleRes: Int = R.string.metric_distance

    override fun read(day: TileDay, chart: TileChart, store: MetricStore): MetricTileContent {
        val metres = DaySummaryStore(this).today()?.distanceMetres ?: return unavailable(getString(R.string.tile_needs_phone))
        return fromPhone(formatMetricValue(MetricTile.DISTANCE, metres.toDouble(), UnitSystem.METRIC), getString(R.string.unit_km))
    }
}

/** Today's walking energy, as the phone estimates it and last sent it (`PUT_DAY`). No curve, as for the distance. */
class ActiveCaloriesTileService : MetricTileService() {

    /** `ActiveCaloriesColor`. */
    override val accent: Int = 0xFFDE6C39.toInt()

    override val titleRes: Int = R.string.metric_active_calories

    override fun read(day: TileDay, chart: TileChart, store: MetricStore): MetricTileContent {
        val kcal = DaySummaryStore(this).today()?.activeKcal ?: return unavailable(getString(R.string.tile_needs_phone))
        return fromPhone(formatCount(kcal.toLong()), getString(R.string.unit_kcal))
    }
}

/** A tile of one of the phone's figures for today: the value, its unit and that it is as of the last sync. */
private fun MetricTileService.fromPhone(value: String, unit: String): MetricTileContent {
    val title = getString(titleRes)
    val detail = getString(R.string.tile_from_phone)
    return MetricTileContent(title, value, unit, accent, "$title: $value $unit, $detail", chart = null, detail = detail)
}
