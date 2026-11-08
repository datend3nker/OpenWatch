package tech.mmarca.openwatch.wearlink

/**
 * One workout the watch recorded, as `WO_SINCE` carries it: a `WO` header,
 * then its `WP` pauses and `WR` route points, oldest first. Every number is
 * an integer so a value reads back the same on both sides, and `-` is none.
 *
 * - `WO <startMillis> <endMillis> <exerciseType> <steps|-> <distanceMetres|-> <ascentDecimetres|->`
 *   `exerciseType` is the Health Connect `EXERCISE_TYPE_*` value. `steps`
 *   and `ascentDecimetres` are what the watch counted over the session,
 *   `distanceMetres` is from GPS and `-` for a sport without a route.
 * - `WP <startMillis> <endMillis>` — one pause inside the session.
 * - `WR <millis> <latE7> <lonE7> <altitudeDecimetres|-> <accuracyDecimetres|->`
 *   — one GPS fix: degrees times 10^7, the altitude above sea level and the
 *   horizontal accuracy, both in decimetres.
 *
 * A workout is keyed on its start. Its lines never exceed
 * [WearLinkProtocol.MAX_LINE_LENGTH].
 */
data class WearLinkWorkout(
    val startMillis: Long,
    val endMillis: Long,
    val exerciseType: Int,
    val steps: Int?,
    val distanceMetres: Int?,
    val ascentDecimetres: Int?,
    val pauses: List<Pause> = emptyList(),
    val route: List<RoutePoint> = emptyList(),
) {
    data class Pause(val startMillis: Long, val endMillis: Long)

    data class RoutePoint(
        val epochMillis: Long,
        val latitudeE7: Int,
        val longitudeE7: Int,
        val altitudeDecimetres: Int?,
        val accuracyDecimetres: Int?,
    )
}

/** The lines of a [WearLinkWorkout]. Each parser is null for a line that is not well formed. */
object WearLinkWorkoutLines {

    const val WORKOUT: String = "WO"
    const val PAUSE: String = "WP"
    const val POINT: String = "WR"

    /** No workout is longer than a day; a longer one is a clock that jumped. */
    const val MAX_DURATION_MILLIS: Long = 24L * 60 * 60 * 1000

    /** The most route points one workout carries: a day at one fix every ten seconds. */
    const val MAX_POINTS: Int = 9000

    /** The most pauses one workout carries. */
    const val MAX_PAUSES: Int = 200

    const val MAX_STEPS: Int = 1_000_000
    const val MAX_DISTANCE_METRES: Int = 1_000_000
    const val MAX_ASCENT_DECIMETRES: Int = 10_000_000

    private const val MAX_LATITUDE_E7 = 900_000_000
    private const val MAX_LONGITUDE_E7 = 1_800_000_000
    private const val MAX_ALTITUDE_DECIMETRES = 100_000
    private const val MAX_ACCURACY_DECIMETRES = 100_000
    private const val MAX_EXERCISE_TYPE = 1000
    private const val NO_VALUE = "-"

    fun formatHeader(workout: WearLinkWorkout): String =
        "$WORKOUT ${workout.startMillis} ${workout.endMillis} ${workout.exerciseType} " +
            "${workout.steps ?: NO_VALUE} ${workout.distanceMetres ?: NO_VALUE} ${workout.ascentDecimetres ?: NO_VALUE}"

    /** A header carries no pauses or points; they follow on their own lines. */
    fun parseHeader(line: String): WearLinkWorkout? {
        val f = fields(line)
        if (f.size != 7 || f[0] != WORKOUT) return null
        val start = f[1].toLongOrNull() ?: return null
        val end = f[2].toLongOrNull() ?: return null
        val type = f[3].toIntOrNull() ?: return null
        val steps = optional(f[4], MAX_STEPS) ?: return null
        val distance = optional(f[5], MAX_DISTANCE_METRES) ?: return null
        val ascent = optional(f[6], MAX_ASCENT_DECIMETRES) ?: return null
        if (start <= 0 || end <= start || end - start > MAX_DURATION_MILLIS || type !in 0..MAX_EXERCISE_TYPE) return null
        return WearLinkWorkout(start, end, type, steps.value, distance.value, ascent.value)
    }

    fun formatPause(pause: WearLinkWorkout.Pause): String = "$PAUSE ${pause.startMillis} ${pause.endMillis}"

    fun parsePause(line: String): WearLinkWorkout.Pause? {
        val f = fields(line)
        if (f.size != 3 || f[0] != PAUSE) return null
        val start = f[1].toLongOrNull() ?: return null
        val end = f[2].toLongOrNull() ?: return null
        return if (start > 0 && end > start) WearLinkWorkout.Pause(start, end) else null
    }

    fun formatPoint(point: WearLinkWorkout.RoutePoint): String =
        "$POINT ${point.epochMillis} ${point.latitudeE7} ${point.longitudeE7} " +
            "${point.altitudeDecimetres ?: NO_VALUE} ${point.accuracyDecimetres ?: NO_VALUE}"

    fun parsePoint(line: String): WearLinkWorkout.RoutePoint? {
        val f = fields(line)
        if (f.size != 6 || f[0] != POINT) return null
        val at = f[1].toLongOrNull() ?: return null
        val lat = f[2].toIntOrNull() ?: return null
        val lon = f[3].toIntOrNull() ?: return null
        val altitude = optionalSigned(f[4], MAX_ALTITUDE_DECIMETRES) ?: return null
        val accuracy = optional(f[5], MAX_ACCURACY_DECIMETRES) ?: return null
        if (at <= 0 || lat !in -MAX_LATITUDE_E7..MAX_LATITUDE_E7 || lon !in -MAX_LONGITUDE_E7..MAX_LONGITUDE_E7) return null
        return WearLinkWorkout.RoutePoint(at, lat, lon, altitude.value, accuracy.value)
    }

    /** The header, the pauses and the points of [workout] as wire lines, in the order they are sent. */
    fun lines(workout: WearLinkWorkout): Sequence<String> = sequence {
        yield(formatHeader(workout))
        for (pause in workout.pauses.take(MAX_PAUSES)) yield(formatPause(pause))
        for (point in workout.route.take(MAX_POINTS)) yield(formatPoint(point))
    }

    private class Optional(val value: Int?)

    /** An integer in `0..max` or `-`; null for anything else. */
    private fun optional(field: String, max: Int): Optional? =
        if (field == NO_VALUE) Optional(null) else field.toIntOrNull()?.takeIf { it in 0..max }?.let { Optional(it) }

    private fun optionalSigned(field: String, max: Int): Optional? =
        if (field == NO_VALUE) Optional(null) else field.toIntOrNull()?.takeIf { it in -max..max }?.let { Optional(it) }

    private fun fields(line: String): List<String> = line.trim().split(' ').filter { it.isNotEmpty() }
}
