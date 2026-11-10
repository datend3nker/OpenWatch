package tech.mmarca.openwatch.domain.insights

import androidx.health.connect.client.records.ExerciseSessionRecord
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openwatch.domain.preferences.BiologicalSex
import tech.mmarca.openwatch.domain.preferences.BodyProfile

/** The workout calorie estimate: heart rate first, then the Compendium MET, never without a weight. */
class WorkoutEnergyTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 10)

    // 30 years old: Mifflin-St Jeor 1648.75 kcal/day, so 68.698 kcal in an hour.
    private val profile = BodyProfile(
        birthYear = 1996,
        weightKg = 70.0,
        heightCm = 175.0,
        sex = BiologicalSex.MALE,
    )

    private fun estimate(
        type: Int = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        heartRate: Double? = null,
        body: BodyProfile = profile,
    ) = estimateWorkoutEnergy(type, durationMinutes = 60.0, meanHeartRateBpm = heartRate, profile = body, today = today)

    @Test
    fun `without heart rate the Compendium MET gives the gross and the own basal rate the resting share`() {
        // Running, code 12150, 8.0 MET: 8 * 3.5 * 70 * 60 * 5 / 1000 = 588 kcal gross.
        val result = estimate() as WorkoutEnergyResult.Estimate

        assertEquals(WorkoutEnergyMethod.COMPENDIUM_MET, result.method)
        assertEquals(588.0, result.totalKcal, 1e-6)
        assertEquals(588.0 - 68.6979166, result.activeKcal, 1e-6)
    }

    @Test
    fun `a heart rate inside Keytel's range uses Keytel's equation`() {
        // Men: -55.0969 + 0.6309*150 + 0.1988*70 + 0.2017*30 = 59.5051 kJ/min, 853.32 kcal in an hour.
        val result = estimate(heartRate = 150.0) as WorkoutEnergyResult.Estimate

        assertEquals(WorkoutEnergyMethod.HEART_RATE, result.method)
        assertEquals(853.3236138, result.totalKcal, 1e-6)
        assertEquals(853.3236138 - 68.6979166, result.activeKcal, 1e-6)
    }

    @Test
    fun `women use their own Keytel coefficients`() {
        // -20.4022 + 0.4472*140 - 0.1263*60 + 0.074*30 = 36.8478 kJ/min.
        assertEquals(
            36.8478,
            KeytelHeartRate.kilojoulesPerMinute(BiologicalSex.FEMALE, 140.0, 60.0, 30),
            1e-9,
        )
    }

    @Test
    fun `a heart rate below 57 percent of the maximum falls back to the MET`() {
        // Tanaka: 208 - 0.7*30 = 187 bpm; 57 % of it is 106.6.
        val result = estimate(heartRate = 100.0) as WorkoutEnergyResult.Estimate

        assertEquals(WorkoutEnergyMethod.COMPENDIUM_MET, result.method)
    }

    @Test
    fun `a heart rate without sex or age falls back to the MET`() {
        val result = estimate(heartRate = 150.0, body = profile.copy(sex = null)) as WorkoutEnergyResult.Estimate

        assertEquals(WorkoutEnergyMethod.COMPENDIUM_MET, result.method)
    }

    @Test
    fun `without a basal rate the resting share is one MET`() {
        // No height: no Mifflin-St Jeor, so 1 * 3.5 * 70 * 60 * 5 / 1000 = 73.5 kcal rest.
        val result = estimate(body = profile.copy(heightCm = null)) as WorkoutEnergyResult.Estimate

        assertEquals(588.0 - 73.5, result.activeKcal, 1e-6)
    }

    @Test
    fun `no weight means no estimate, and says so`() {
        assertEquals(
            WorkoutEnergyResult.MissingInputs(setOf(WorkoutEnergyInput.WEIGHT)),
            estimate(body = profile.copy(weightKg = null)),
        )
    }

    @Test
    fun `an activity the Compendium does not list gets no estimate`() {
        assertEquals(WorkoutEnergyResult.Unavailable, estimate(type = ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR))
        assertNull(CompendiumMet.forExerciseType(ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING))
    }

    @Test
    fun `the table keeps the Compendium code beside each value`() {
        assertEquals(
            CompendiumActivity("15710", 4.0),
            CompendiumMet.forExerciseType(ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL),
        )
    }

    @Test
    fun `a run at a pace ACSM covers is estimated from its distance`() {
        // 12 km in 60 minutes = 200 m/min, above 134: 0.2 * 12000 * 70 / 1000 * 4.9 = 823.2 kcal net.
        val result = estimateWorkoutEnergy(
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            durationMinutes = 60.0,
            meanHeartRateBpm = null,
            profile = profile,
            today = today,
            distanceMeters = 12_000.0,
        ) as WorkoutEnergyResult.Estimate

        assertEquals(WorkoutEnergyMethod.DISTANCE_ON_FOOT, result.method)
        assertEquals(823.2, result.activeKcal, 1e-6)
        assertEquals(823.2 + 68.6979166, result.totalKcal, 1e-6)
    }

    @Test
    fun `a walk counts 0_1 ml per kg per metre, only between 50 and 100 metres a minute`() {
        // 4.8 km in 60 minutes = 80 m/min: 0.1 * 4800 * 70 / 1000 * 4.9 = 164.64 kcal.
        assertEquals(
            164.64,
            AcsmDistanceOnFoot.netKcal(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, 4_800.0, 60.0, 70.0)!!,
            1e-9,
        )
        // 120 m/min is too fast for the walking equation and too slow for the running one.
        assertNull(AcsmDistanceOnFoot.netKcal(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, 7_200.0, 60.0, 70.0))
        assertNull(AcsmDistanceOnFoot.netKcal(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, 7_200.0, 60.0, 70.0))
        assertNull(AcsmDistanceOnFoot.netKcal(ExerciseSessionRecord.EXERCISE_TYPE_HIKING, 4_800.0, 60.0, 70.0))
    }

    @Test
    fun `heart rate still comes before the distance`() {
        val result = estimateWorkoutEnergy(
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            durationMinutes = 60.0,
            meanHeartRateBpm = 150.0,
            profile = profile,
            today = today,
            distanceMeters = 12_000.0,
        ) as WorkoutEnergyResult.Estimate

        assertEquals(WorkoutEnergyMethod.HEART_RATE, result.method)
    }
}
