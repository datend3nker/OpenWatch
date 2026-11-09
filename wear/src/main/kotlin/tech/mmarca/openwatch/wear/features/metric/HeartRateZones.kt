package tech.mmarca.openwatch.wear.features.metric

import tech.mmarca.openwatch.wearlink.HeartRateZones
import tech.mmarca.openwatch.wear.R

/** What the zones rest on; the ACSM classes and their sources are in [HeartRateZones]. */
typealias ZoneBasis = HeartRateZones.Basis

/** Until the phone sends the profile's: a common middle-aged guess. */
val DefaultZoneBasis = ZoneBasis(190)

/** The five zones' names, light to maximum. */
val HeartZoneLabels = listOf(
    R.string.zone_very_light,
    R.string.zone_light,
    R.string.zone_moderate,
    R.string.zone_hard,
    R.string.zone_maximum,
)

/** The zone index, 0 to 4, for [bpm]; null below zone 1. */
fun heartRateZone(bpm: Double, basis: ZoneBasis): Int? = HeartRateZones.zoneOf(bpm, basis)

/** Where [bpm] sits across all five zones, 0 at the start of zone 1 and 1 at the maximum. */
fun heartRateZoneProgress(bpm: Double, basis: ZoneBasis): Float =
    ((basis.share(bpm) - basis.bounds.first()) / (1 - basis.bounds.first())).toFloat().coerceIn(0f, 1f)

/** The share of [samples] in each of the five zones. Samples below zone 1 count towards none. */
fun heartRateZoneShares(samples: List<Double>, basis: ZoneBasis): List<Float> {
    if (samples.isEmpty()) return List(HeartRateZones.COUNT) { 0f }
    val counts = IntArray(HeartRateZones.COUNT)
    samples.forEach { bpm -> heartRateZone(bpm, basis)?.let { counts[it]++ } }
    return counts.map { it.toFloat() / samples.size }
}
