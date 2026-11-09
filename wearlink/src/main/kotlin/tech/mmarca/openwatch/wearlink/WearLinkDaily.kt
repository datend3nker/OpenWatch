package tech.mmarca.openwatch.wearlink

/**
 * What the phone knows of one local day and the watch shows on its own
 * screens: the night that ended on it, the resting heart rate, and the
 * walking energy and distance the phone estimates from the step minutes.
 * Null is "not known". [epochDay] is the local date's day number.
 */
data class DaySummary(
    val epochDay: Long,
    val sleepMinutes: Int? = null,
    val awakeMinutes: Int? = null,
    val remMinutes: Int? = null,
    val lightMinutes: Int? = null,
    val deepMinutes: Int? = null,
    val restingBpm: Int? = null,
    val activeKcal: Int? = null,
    val distanceMetres: Int? = null,
)

/**
 * One entry made on the watch: a quick log or a spot measurement. [type] is
 * one of [WearLinkDaily.ENTRY_TYPES]; [value2] is the diastolic pressure of
 * a blood pressure entry and null otherwise.
 */
data class WatchEntry(val epochMillis: Long, val type: String, val value: Double, val value2: Double? = null)

/**
 * The lines of `PUT_DAY` and `LG_SINCE` (see [WearLinkProtocol]):
 *
 * - `PUT_DAY <epochDay> <sleep> <awake> <rem> <light> <deep> <restingBpm> <activeKcal> <distanceMetres>`,
 *   each a whole number or `-` → `DAY_OK`, or `ERROR bad_request`.
 * - `LG <epochMillis> <type> <value> <value2|->`, one per entry.
 */
object WearLinkDaily {
    const val WATER = "water"
    const val WEIGHT = "weight"
    const val BLOOD_PRESSURE = "bp"
    const val MINDFULNESS = "mindful"
    const val HEART_RATE = "hr"
    const val HRV = "hrv"
    const val BODY_FAT = "bodyfat"
    const val BLOOD_OXYGEN = "spo2"
    const val RESPIRATORY_RATE = "breath"
    const val BODY_TEMPERATURE = "temp"
    const val BLOOD_GLUCOSE = "glucose"
    val ENTRY_TYPES: Set<String> = setOf(
        WATER, WEIGHT, BLOOD_PRESSURE, MINDFULNESS, HEART_RATE, HRV,
        BODY_FAT, BLOOD_OXYGEN, RESPIRATORY_RATE, BODY_TEMPERATURE, BLOOD_GLUCOSE,
    )

    private const val NO_VALUE = "-"
    private const val MINUTES_PER_DAY = 24 * 60
    private const val MAX_KCAL = 20_000
    private const val MAX_DISTANCE_METRES = 500_000
    private const val MAX_ENTRY_VALUE = 100_000.0

    fun formatPutDay(day: DaySummary): String = listOf(
        WearLinkProtocol.PUT_DAY,
        day.epochDay.toString(),
        field(day.sleepMinutes), field(day.awakeMinutes), field(day.remMinutes), field(day.lightMinutes), field(day.deepMinutes),
        field(day.restingBpm), field(day.activeKcal), field(day.distanceMetres),
    ).joinToString(" ")

    /** Null unless [line] is a well-formed `PUT_DAY`: every value missing or within what a day can hold. */
    fun parsePutDay(line: String): DaySummary? {
        val fields = line.trim().split(' ').filter { it.isNotEmpty() }
        if (fields.size != 10 || fields[0] != WearLinkProtocol.PUT_DAY) return null
        val epochDay = fields[1].toLongOrNull() ?: return null
        val values = fields.drop(2).map { if (it == NO_VALUE) null else it.toIntOrNull() ?: return null }
        val limits = List(5) { MINUTES_PER_DAY } + listOf(WearLinkProtocol.MAX_BPM, MAX_KCAL, MAX_DISTANCE_METRES)
        if (epochDay < 0 || values.zip(limits).any { (value, max) -> value != null && value !in 0..max }) return null
        return DaySummary(epochDay, values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7])
    }

    fun formatEntry(entry: WatchEntry): String =
        "${WearLinkProtocol.ENTRY} ${entry.epochMillis} ${entry.type} ${entry.value} ${entry.value2 ?: NO_VALUE}"

    /** Null unless [line] is a well-formed `LG` line of a known type. */
    fun parseEntry(line: String): WatchEntry? {
        val fields = line.trim().split(' ').filter { it.isNotEmpty() }
        if (fields.size != 5 || fields[0] != WearLinkProtocol.ENTRY) return null
        val at = fields[1].toLongOrNull() ?: return null
        val type = fields[2].takeIf { it in ENTRY_TYPES } ?: return null
        val value = fields[3].toDoubleOrNull() ?: return null
        val value2 = if (fields[4] == NO_VALUE) null else fields[4].toDoubleOrNull() ?: return null
        if (at <= 0 || !value.isFinite() || value !in 0.0..MAX_ENTRY_VALUE) return null
        if (value2 != null && (!value2.isFinite() || value2 !in 0.0..MAX_ENTRY_VALUE)) return null
        return WatchEntry(at, type, value, value2)
    }

    private fun field(value: Int?): String = value?.toString() ?: NO_VALUE
}
