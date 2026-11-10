package tech.mmarca.openwatch.wearlink

/**
 * The five heart rate zones: the American College of Sports Medicine's
 * intensity classes very light, light, moderate, vigorous and near-maximal.
 * Garber et al., "Quantity and quality of exercise ...", ACSM position stand,
 * Med Sci Sports Exerc 2011;43(7):1334-1359, doi:10.1249/MSS.0b013e318213fefb,
 * table 4. With a resting rate as shares of the heart rate reserve
 * (Karvonen), without one as shares of the maximum. Very light has no lower
 * bound there; here it starts at rest, or at nothing.
 */
object HeartRateZones {
    const val COUNT = 5

    /** Lower bounds as a share of the maximum (ACSM %HRmax), zone 1 first. */
    val MAX_SHARE_BOUNDS = listOf(0.0, 0.57, 0.64, 0.77, 0.96)

    /** Lower bounds as a share of the reserve (ACSM %HRR), zone 1 first. */
    val RESERVE_SHARE_BOUNDS = listOf(0.0, 0.30, 0.40, 0.60, 0.90)

    data class Basis(val maxBpm: Int, val restingBpm: Int? = null) {
        private val resting: Int? get() = restingBpm?.takeIf { it < maxBpm }

        /** Where [bpm] sits between the floor (rest, or nothing) and the maximum. */
        fun share(bpm: Double): Double {
            val floor = resting?.toDouble() ?: 0.0
            return (bpm - floor) / (maxBpm - floor)
        }

        val bounds: List<Double> get() = if (resting != null) RESERVE_SHARE_BOUNDS else MAX_SHARE_BOUNDS
    }

    /** The zone index, 0 to 4; null below zone 1. */
    fun zoneOf(bpm: Double, basis: Basis): Int? {
        val share = basis.share(bpm)
        return basis.bounds.indexOfLast { share >= it }.takeIf { it >= 0 }
    }
}
