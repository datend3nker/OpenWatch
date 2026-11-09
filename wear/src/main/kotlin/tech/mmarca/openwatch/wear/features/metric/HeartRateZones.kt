package tech.mmarca.openwatch.wear.features.metric

import tech.mmarca.openwatch.wear.R

/*
 * The five zones are the American College of Sports Medicine's intensity
 * classes: very light, light, moderate, vigorous and near-maximal. Garber et
 * al., "Quantity and quality of exercise ...", ACSM position stand, Med Sci
 * Sports Exerc 2011;43(7):1334-1359, doi:10.1249/MSS.0b013e318213fefb, table 4.
 * Very light has no lower bound there; here it starts at rest (reserve) or at
 * nothing (maximum), so a sample counts towards a zone whenever it is above rest.
 */

/** Lower bound of each zone as a share of the maximum heart rate, zone 1 first: without a resting rate (ACSM %HRmax). */
private val MaxShareBounds = listOf(0.0, 0.57, 0.64, 0.77, 0.96)

/** Lower bound of each zone as a share of the heart rate reserve, zone 1 first: with a resting rate (ACSM %HRR). */
private val ReserveShareBounds = listOf(0.0, 0.30, 0.40, 0.60, 0.90)

/** The five zones' names, light to maximum. */
val HeartZoneLabels = listOf(
    R.string.zone_very_light,
    R.string.zone_light,
    R.string.zone_moderate,
    R.string.zone_hard,
    R.string.zone_maximum,
)

/**
 * What the zones rest on: the maximum heart rate and, when known, the resting
 * one. With a resting rate a zone is a share of the heart rate reserve
 * between the two (Karvonen, Ann Med Exp Biol Fenn 1957); without one, a
 * share of the maximum.
 */
data class ZoneBasis(val maxBpm: Int, val restingBpm: Int? = null) {
    private val resting: Int? get() = restingBpm?.takeIf { it < maxBpm }

    /** Where [bpm] sits between the floor (rest, or nothing) and the maximum, 0 to 1 and beyond. */
    fun share(bpm: Double): Double {
        val floor = resting?.toDouble() ?: 0.0
        return (bpm - floor) / (maxBpm - floor)
    }

    /** The zones' lower bounds on [share]'s scale, zone 1 first. */
    val bounds: List<Double> get() = if (resting != null) ReserveShareBounds else MaxShareBounds

    companion object {
        /** Until the phone sends the profile's: a common middle-aged guess. */
        val Default = ZoneBasis(190)
    }
}

/** The zone index, 0 to 4, for [bpm]; null below zone 1. */
fun heartRateZone(bpm: Double, basis: ZoneBasis): Int? {
    val share = basis.share(bpm)
    return basis.bounds.indexOfLast { share >= it }.takeIf { it >= 0 }
}

/** Where [bpm] sits across all five zones, 0 at the start of zone 1 and 1 at the maximum. */
fun heartRateZoneProgress(bpm: Double, basis: ZoneBasis): Float =
    ((basis.share(bpm) - basis.bounds.first()) / (1 - basis.bounds.first())).toFloat().coerceIn(0f, 1f)

/** The share of [samples] in each of the five zones. Samples below zone 1 count towards none. */
fun heartRateZoneShares(samples: List<Double>, basis: ZoneBasis): List<Float> {
    if (samples.isEmpty()) return List(basis.bounds.size) { 0f }
    val counts = IntArray(basis.bounds.size)
    samples.forEach { bpm -> heartRateZone(bpm, basis)?.let { counts[it]++ } }
    return counts.map { it.toFloat() / samples.size }
}
