package tech.mmarca.openwatch.wearlink

/**
 * The `BC` line of the link: one body composition measurement the watch
 * took with the Samsung platform. Apart from [WearLinkProtocol] only to keep
 * that file within its size.
 */
object WearLinkBodyLines {

    /**
     * One body composition measurement, keyed on its time: body fat in tenths
     * of a per cent, fat-free mass and body water in tenths of a kilogram.
     */
    data class BodyComposition(
        val epochMillis: Long,
        val bodyFatPermille: Int,
        val fatFreeMassHectograms: Int,
        val bodyWaterHectograms: Int,
    )

    fun format(measurement: BodyComposition): String =
        "${WearLinkProtocol.BODY_COMPOSITION} ${measurement.epochMillis} ${measurement.bodyFatPermille} " +
            "${measurement.fatFreeMassHectograms} ${measurement.bodyWaterHectograms}"

    /** Null unless [line] is a well-formed `BC` line: a time, a body fat between 1 and 75 per cent, and two masses up to 300 kg. */
    fun parse(line: String): BodyComposition? {
        val fields = line.trim().split(' ').filter { it.isNotEmpty() }
        if (fields.size != 5 || fields[0] != WearLinkProtocol.BODY_COMPOSITION) return null
        val at = fields[1].toLongOrNull() ?: return null
        val fat = fields[2].toIntOrNull() ?: return null
        val fatFree = fields[3].toIntOrNull() ?: return null
        val water = fields[4].toIntOrNull() ?: return null
        if (at <= 0 || fat !in 10..750 || fatFree !in 1..MAX_HECTOGRAMS || water !in 1..MAX_HECTOGRAMS) return null
        return BodyComposition(at, fat, fatFree, water)
    }

    private const val MAX_HECTOGRAMS = 3000
}
