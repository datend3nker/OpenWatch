package tech.mmarca.openwatch.domain.insights

import java.time.LocalDate
import kotlin.math.roundToInt
import tech.mmarca.openwatch.domain.preferences.BiologicalSex
import tech.mmarca.openwatch.domain.preferences.BodyProfile

/**
 * The two numbers the heart rate zones rest on. The body profile itself is
 * shared over OpenLink.
 *
 * Maximum: the one the user entered (a measured maximum is what the ACSM
 * prefers over any formula); else the highest heart rate on record when it is
 * a real maximum, at least 150 bpm and 60 above resting. That bar has no
 * published source: it is OpenVitals' heuristic (its MaxHeartRate.kt), kept so
 * both apps agree. Else the age-predicted one:
 * 208 − 0.7 × age (Tanaka, Monahan and Seals, JACC 2001, doi:10.1016/S0735-1097(00)01054-8),
 * 206 − 0.88 × age for women (Gulati et al., Circulation 2010,
 * doi:10.1161/CIRCULATIONAHA.110.939249).
 *
 * Resting: the one the user entered; else the latest night's.
 *
 * With a resting rate the watch places the zones on the heart rate reserve
 * (Karvonen), without one on the maximum, both at the ACSM's intensity classes
 * (Garber et al., Med Sci Sports Exerc 2011, doi:10.1249/MSS.0b013e318213fefb).
 */
data class HeartRateZoneBasis(val maxBpm: Int?, val restingBpm: Int?) {
    companion object {
        const val ObservedMaxMinimumBpm = 150
        const val ObservedMaxRestingDeltaBpm = 60

        fun of(
            profile: BodyProfile,
            today: LocalDate = LocalDate.now(),
            observedMaxBpm: Int? = null,
            nightRestingBpm: Int? = null,
        ): HeartRateZoneBasis {
            val normalized = profile.normalized(today)
            val resting = normalized.restingHeartRateBpm ?: nightRestingBpm
            val observed = observedMaxBpm?.takeIf { max ->
                resting != null && max >= maxOf(ObservedMaxMinimumBpm, resting + ObservedMaxRestingDeltaBpm)
            }
            val predicted = normalized.ageYears(today)?.let { age -> predictedMax(age, normalized.sex) }
            return HeartRateZoneBasis(normalized.maxHeartRateBpm ?: observed ?: predicted, normalized.restingHeartRateBpm)
        }

        fun predictedMax(ageYears: Int, sex: BiologicalSex?): Int =
            if (sex == BiologicalSex.FEMALE) (206 - 0.88 * ageYears).roundToInt() else (208 - 0.7 * ageYears).roundToInt()
    }
}
