package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.data.repository.ImportedRecordRepository
import tech.mmarca.openwatch.devices.FakeSharedPreferences
import tech.mmarca.openwatch.domain.insights.WorkoutEnergyMethod
import tech.mmarca.openwatch.domain.insights.WorkoutEnergyResult
import tech.mmarca.openwatch.domain.preferences.BiologicalSex
import tech.mmarca.openwatch.domain.preferences.BodyProfile

class WearOsWorkoutImportTest {

    private val start = Instant.parse("2026-11-08T16:00:00Z")
    private val zone = ZoneOffset.ofHours(1)

    private fun run(route: Boolean = true, distance: Int? = 4200) = WearOsWorkout(
        start = start,
        end = start.plusSeconds(1800),
        exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        steps = 4000,
        distanceMetres = distance,
        ascentMetres = 12.0,
        pauses = listOf(start.plusSeconds(600)..start.plusSeconds(660)),
        route = if (!route) emptyList() else listOf(
            WearOsWorkoutPoint(start.minusSeconds(5), 47.5, 11.2, null, null),
            WearOsWorkoutPoint(start.plusSeconds(10), 47.5, 11.2, 534.0, 8.0),
            WearOsWorkoutPoint(start.plusSeconds(20), 47.5001, 11.2001, 534.5, 6.0),
        ),
    )

    @Test
    fun `a run becomes a session with its route inside it, its pause, and a distance`() {
        val records = WearOsWorkoutImport.records(run(), zone)
        val session = records.filterIsInstance<ExerciseSessionRecord>().single()
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, session.exerciseType)
        assertEquals("wearos_workout_${start.toEpochMilli()}", session.metadata.clientRecordId)
        assertEquals(ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE, session.segments.single().segmentType)
        val distance = records.filterIsInstance<DistanceRecord>().single()
        assertEquals(4200.0, distance.distance.inMeters, 1e-9)
        assertEquals(
            setOf(WearOsWorkoutImport.sessionPermission, WearOsWorkoutImport.routePermission, WearOsWorkoutImport.distancePermission),
            WearOsWorkoutImport.permissions(run()),
        )
    }

    @Test
    fun `an indoor workout asks only for the session`() {
        val records = WearOsWorkoutImport.records(run(route = false, distance = null), zone)
        assertTrue(records.single() is ExerciseSessionRecord)
        assertTrue((records.single() as ExerciseSessionRecord).exerciseRouteResult !is androidx.health.connect.client.records.ExerciseRouteResult.Data)
        assertEquals(setOf(WearOsWorkoutImport.sessionPermission), WearOsWorkoutImport.permissions(run(route = false, distance = null)))
    }

    @Test
    fun `without the grants nothing is written and the cursor stays, with them the cursor moves`() = runTest {
        val repository = mockk<ImportedRecordRepository>(relaxed = true)
        val cursors = WearOsSyncCursorStore(FakeSharedPreferences())
        var granted = setOf(WearOsWorkoutImport.sessionPermission)
        val sync = WearOsWorkoutSync(repository, cursors, { granted }, { zone })
        val page = WearOsWorkoutPage(listOf(run()), hasMore = false)

        val refused = sync.sync("watch") { page }!!
        assertEquals(0, refused.written)
        assertEquals(setOf(WearOsWorkoutImport.routePermission, WearOsWorkoutImport.distancePermission), refused.withheld)
        assertEquals(Instant.EPOCH, cursors.workoutCursor("watch"))

        granted = WearOsWorkoutImport.permissions(run())
        coEvery { repository.insertImportedRecords(any()) } returns Unit
        val done = sync.sync("watch") { since -> if (since == Instant.EPOCH) page else WearOsWorkoutPage(emptyList(), false) }!!
        assertEquals(1, done.written)
        assertEquals(start, cursors.workoutCursor("watch"))
        coVerify(exactly = 1) { repository.insertImportedRecords(any()) }
    }

    // 30 on that day: Mifflin-St Jeor 1648.75 kcal/day, 33.2 kcal of it in the 29 minutes moved.
    private val body = BodyProfile(birthYear = 1996, weightKg = 70.0, heightCm = 175.0, sex = BiologicalSex.MALE)
    private val today = LocalDate.of(2026, 11, 8)

    @Test
    fun `a run's calories come from the watch's heart rate, without the pause`() {
        val samples = listOf(
            start.plusSeconds(60) to 150L,
            start.plusSeconds(630) to 60L, // in the pause: left out
            start.plusSeconds(1200) to 150L,
            start.plusSeconds(1900) to 60L, // after the end: left out
        )
        val (record, result) = WearOsWorkoutImport.activeCalories(run(), zone, body, samples, today)

        // Keytel, men: 59.5051 kJ/min at 150 bpm, 412.44 kcal in 29 minutes, less 33.20 at rest.
        assertEquals(WorkoutEnergyMethod.HEART_RATE, (result as WorkoutEnergyResult.Estimate).method)
        assertEquals(379.2357536, record!!.energy.inKilocalories, 1e-6)
        assertEquals("wearos_workout_energy_${start.toEpochMilli()}", record.metadata.clientRecordId)
        assertEquals(start, record.startTime)
    }

    @Test
    fun `without heart rate a run at a running pace is counted from its distance`() {
        // 4200 m in 29 minutes is 145 m/min: ACSM, 0.2 * 4200 * 70 / 1000 * 4.9 = 288.12 kcal.
        val (record, result) = WearOsWorkoutImport.activeCalories(run(), zone, body, emptyList(), today)

        assertEquals(WorkoutEnergyMethod.DISTANCE_ON_FOOT, (result as WorkoutEnergyResult.Estimate).method)
        assertEquals(288.12, record!!.energy.inKilocalories, 1e-6)
    }

    @Test
    fun `without a weight there is no record, and the result says what is missing`() {
        val (record, result) = WearOsWorkoutImport.activeCalories(run(), zone, body.copy(weightKg = null), emptyList(), today)

        assertNull(record)
        assertTrue(result is WorkoutEnergyResult.MissingInputs)
    }
}
