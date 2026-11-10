package tech.mmarca.openwatch.domain.insights

import androidx.health.connect.client.records.ExerciseSessionRecord
import java.time.LocalDate
import tech.mmarca.openwatch.domain.preferences.BiologicalSex
import tech.mmarca.openwatch.domain.preferences.BodyProfile

/** A body profile field the workout calorie estimate cannot do without. */
enum class WorkoutEnergyInput { WEIGHT }

/** How a workout's calories were estimated. */
enum class WorkoutEnergyMethod {
    /** Keytel et al. 2005, from the session's mean heart rate. */
    HEART_RATE,

    /** ACSM's metabolic equation for walking or running on the level, from distance and pace. */
    DISTANCE_ON_FOOT,

    /** The activity's MET value in the 2024 Adult Compendium. */
    COMPENDIUM_MET,
}

sealed interface WorkoutEnergyResult {
    data class Estimate(
        val activeKcal: Double,
        val totalKcal: Double,
        val method: WorkoutEnergyMethod,
    ) : WorkoutEnergyResult

    /** The profile lacks a field the estimate needs: say which, never guess it. */
    data class MissingInputs(val inputs: Set<WorkoutEnergyInput>) : WorkoutEnergyResult

    /** No sourced energy cost for this activity, or no usable duration. */
    data object Unavailable : WorkoutEnergyResult
}

/**
 * The calories a workout burned, estimated, for a session that recorded none.
 *
 * In this order: from the heart rate when the session has one in the range
 * Keytel's equation was derived on and the profile has sex and age; from the
 * distance of a walk or run whose pace lies where ACSM's equation holds
 * ([AcsmDistanceOnFoot]); otherwise from the activity's Compendium MET. The
 * resting share for the same time is the wearer's own basal metabolic rate
 * (Mifflin-St Jeor) when the profile has every field it needs, else 1 MET.
 * Heart rate and MET give gross energy, active = gross − resting; ACSM's
 * horizontal component is already net of rest.
 *
 * Without a weight there is no estimate: [WorkoutEnergyResult.MissingInputs].
 */
fun estimateWorkoutEnergy(
    exerciseType: Int,
    durationMinutes: Double,
    meanHeartRateBpm: Double?,
    profile: BodyProfile,
    today: LocalDate,
    distanceMeters: Double? = null,
): WorkoutEnergyResult {
    if (!(durationMinutes > 0.0) || !durationMinutes.isFinite()) return WorkoutEnergyResult.Unavailable
    val weightKg = profile.weightKg ?: return WorkoutEnergyResult.MissingInputs(setOf(WorkoutEnergyInput.WEIGHT))
    val restingKcal = restingKcal(profile, weightKg, durationMinutes, today)

    val heartRateGross = meanHeartRateBpm?.let { heartRate ->
        keytelGrossKcal(profile, weightKg, heartRate, durationMinutes, today)
    }
    val distanceNet = distanceMeters?.let {
        AcsmDistanceOnFoot.netKcal(exerciseType, it, durationMinutes, weightKg)
    }
    val (activeKcal, method) = when {
        heartRateGross != null -> (heartRateGross - restingKcal) to WorkoutEnergyMethod.HEART_RATE
        distanceNet != null -> distanceNet to WorkoutEnergyMethod.DISTANCE_ON_FOOT
        else -> {
            val met = CompendiumMet.forExerciseType(exerciseType)?.met ?: return WorkoutEnergyResult.Unavailable
            (CompendiumMet.grossKcal(met, weightKg, durationMinutes) - restingKcal) to WorkoutEnergyMethod.COMPENDIUM_MET
        }
    }
    if (!(activeKcal > 0.0)) return WorkoutEnergyResult.Unavailable
    return WorkoutEnergyResult.Estimate(
        activeKcal = activeKcal,
        totalKcal = activeKcal + restingKcal,
        method = method,
    )
}

private fun restingKcal(profile: BodyProfile, weightKg: Double, durationMinutes: Double, today: LocalDate): Double =
    profile.basalMetabolicRateKcal(today)?.let { it * durationMinutes / MinutesPerDay }
        ?: CompendiumMet.grossKcal(met = 1.0, weightKg = weightKg, durationMinutes = durationMinutes)

private fun keytelGrossKcal(
    profile: BodyProfile,
    weightKg: Double,
    meanHeartRateBpm: Double,
    durationMinutes: Double,
    today: LocalDate,
): Double? {
    val sex = profile.sex ?: return null
    val age = profile.ageYears(today) ?: return null
    val maxHeartRate = profile.maxHeartRateBpm?.toDouble() ?: ageMaxHeartRate(age, sex).toDouble()
    if (meanHeartRateBpm < KeytelHeartRate.MinFractionOfMax * maxHeartRate) return null
    if (meanHeartRateBpm > KeytelHeartRate.MaxFractionOfMax * maxHeartRate) return null
    val kjPerMinute = KeytelHeartRate.kilojoulesPerMinute(sex, meanHeartRateBpm, weightKg, age)
        .takeIf { it > 0.0 } ?: return null
    return kjPerMinute / KilojoulesPerKilocalorie * durationMinutes
}

/**
 * Keytel et al., "Prediction of energy expenditure from heart rate monitoring
 * during submaximal exercise", J Sports Sci 23(3):289–297 (2005),
 * doi:10.1080/02640410470001730089. Coefficients read in Keytel's PhD thesis
 * (University of Cape Town, 2004), chapter 4, the model without VO2max:
 *
 * EE (kJ/min) = gender × (−55.0969 + 0.6309 HR + 0.1988 weight + 0.2017 age)
 *             + (1 − gender) × (−20.4022 + 0.4472 HR − 0.1263 weight + 0.074 age),
 * gender = 1 for men, 0 for women. Gross energy expenditure, rest included.
 *
 * Derived on 115 adults aged 19 to 45 at steady-state treadmill or cycle work
 * of 57 to 90 % of their maximum heart rate. Outside that heart rate range the
 * estimate is not used: this project's own rule, not the paper's.
 */
object KeytelHeartRate {
    const val MinFractionOfMax = 0.57
    const val MaxFractionOfMax = 0.90

    fun kilojoulesPerMinute(sex: BiologicalSex, heartRateBpm: Double, weightKg: Double, ageYears: Int): Double =
        when (sex) {
            BiologicalSex.MALE -> -55.0969 + 0.6309 * heartRateBpm + 0.1988 * weightKg + 0.2017 * ageYears
            BiologicalSex.FEMALE -> -20.4022 + 0.4472 * heartRateBpm - 0.1263 * weightKg + 0.074 * ageYears
        }
}

/**
 * ACSM's metabolic equations for walking and running on the level, ACSM's
 * Guidelines for Exercise Testing and Prescription, 10th ed. (2018), Table 6.3:
 * VO2 (ml/kg/min) = 3.5 + 0.1 × speed for walking, 3.5 + 0.2 × speed for
 * running, speed in m/min. The 3.5 is rest, so the horizontal part alone is
 * the net cost: 0.1 or 0.2 ml O2 per kg per metre, turned into kcal with the
 * table's 4.9 kcal per litre of O2 (about 0.49 and 0.98 kcal per kg per km).
 * Margaria et al. 1963 (doi:10.1152/jappl.1963.18.2.367) measured the same
 * for running: about 1 kcal per kg per km, independent of speed.
 *
 * The table says where each holds: walking at 50 to 100 m/min, running above
 * 134 m/min. Outside that pace, or for any other activity, there is no answer.
 * Climbing is not counted: a total ascent does not give the grade of each stretch.
 */
object AcsmDistanceOnFoot {
    private const val WalkingMlPerKgMetre = 0.1
    private const val RunningMlPerKgMetre = 0.2
    private const val KcalPerLitreOxygen = 4.9
    private val WalkingPaceMetresPerMinute = 50.0..100.0
    private const val MinRunningPaceMetresPerMinute = 134.0

    fun netKcal(exerciseType: Int, distanceMeters: Double, durationMinutes: Double, weightKg: Double): Double? {
        if (!(distanceMeters > 0.0) || !(durationMinutes > 0.0)) return null
        val pace = distanceMeters / durationMinutes
        val mlPerKgMetre = when (exerciseType) {
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING ->
                WalkingMlPerKgMetre.takeIf { pace in WalkingPaceMetresPerMinute }
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL ->
                RunningMlPerKgMetre.takeIf { pace > MinRunningPaceMetresPerMinute }
            else -> null
        } ?: return null
        return mlPerKgMetre * distanceMeters * weightKg / 1000.0 * KcalPerLitreOxygen
    }
}

private const val MinutesPerDay = 24.0 * 60.0
private const val KilojoulesPerKilocalorie = 4.184
