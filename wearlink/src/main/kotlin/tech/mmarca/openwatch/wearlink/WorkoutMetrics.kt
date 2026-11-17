package tech.mmarca.openwatch.wearlink

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt
import tech.mmarca.healthcalculations.insights.CardioLoadTimeWindow
import tech.mmarca.healthcalculations.insights.HeartRateRecoveryQuality
import tech.mmarca.healthcalculations.insights.HeartRateZones
import tech.mmarca.healthcalculations.insights.WorkoutEnergyResult
import tech.mmarca.healthcalculations.insights.buildActivitySplits
import tech.mmarca.healthcalculations.insights.calculateHeartRateRecovery
import tech.mmarca.healthcalculations.insights.estimateRouteVo2Max
import tech.mmarca.healthcalculations.insights.estimateWorkoutEnergy
import tech.mmarca.healthcalculations.insights.timeWeightedMeanHeartRate
import tech.mmarca.healthcalculations.insights.workoutTrimp
import tech.mmarca.healthcalculations.model.ExerciseData
import tech.mmarca.healthcalculations.model.ExerciseRoutePoint
import tech.mmarca.healthcalculations.model.ExerciseSegment
import tech.mmarca.healthcalculations.model.ExerciseSegmentData
import tech.mmarca.healthcalculations.model.HeartRateSample
import tech.mmarca.healthcalculations.preferences.BodyProfile

/**
 * What a workout comes to, from its heart rate, its route and the body
 * profile. Every figure is health-calculations' (the same OpenVitals shows),
 * so the watch's summary and what the phone writes to Health Connect are one
 * number; this object only hands the watch's workout to it. The workout's
 * active time is its session less its pauses ([activeWindows]): a pause
 * counts for nothing in any figure.
 */
object WorkoutMetrics {

    /** One heart rate reading. */
    data class Beat(val epochMillis: Long, val bpm: Int)

    /** One split: the time the distance [index] + 1 kilometres took, pauses left out. */
    data class Split(val index: Int, val startMillis: Long, val endMillis: Long, val activeSeconds: Long)

    data class Summary(
        /** The time-weighted mean ([timeWeightedMeanHeartRate]) over the active time. */
        val averageBpm: Int?,
        val maxBpm: Int?,
        /** Seconds in each of the five zones, zone 1 first ([HeartRateZones.timeInZones]); below zone 1 is not counted. */
        val zoneSeconds: List<Long>,
        /** Banister's TRIMP ([workoutTrimp]); null without a resting rate. */
        val trimp: Double?,
        /** Energy above rest, kcal, from [energy]. Null without an estimate (no weight, or no sourced cost). */
        val activeKcal: Double?,
        /** Whole energy, kcal: [activeKcal] plus the resting share of the same minutes. */
        val totalKcal: Double?,
        val splits: List<Split>,
        /** Seconds per kilometre over the active time; null without distance. */
        val paceSecondsPerKm: Double?,
        /** ml/kg/min ([estimateRouteVo2Max]); null for a sport or a session it does not apply to. */
        val vo2Max: Double?,
        /** The heart rate's recovery after the stop ([recovery]); null when it was not measured. */
        val recovery: Recovery? = null,
    )

    /**
     * Heart rate recovery as health-calculations reads it ([calculateHeartRateRecovery]):
     * the falls one and two minutes after the stop, and whether the effort before it
     * makes them comparable. An [HeartRateRecoveryQuality.INVALID] reading carries no fall.
     */
    data class Recovery(val oneMinuteBpm: Int?, val twoMinutesBpm: Int?, val peakBpm: Int?, val quality: HeartRateRecoveryQuality)

    /**
     * [profile] is the wearer's body profile; [day] the day its age is read on,
     * the workout's own day where that is known.
     */
    fun summarize(
        workout: WearLinkWorkout,
        beats: List<Beat>,
        zones: HeartRateZones.Basis?,
        profile: BodyProfile,
        day: LocalDate,
    ): Summary {
        val windows = activeWindows(workout)
        val samples = samples(beats, workout)
        val estimate = energy(workout, beats, profile, day) as? WorkoutEnergyResult.Estimate
        val resting = zones?.restingBpm
        val distance = workout.distanceMetres
        val activeSeconds = windows.sumOf { Duration.between(it.start, it.end).seconds }.toDouble()
        return Summary(
            averageBpm = timeWeightedMeanHeartRate(samples.map { it.time to it.beatsPerMinute }, windows)?.roundToInt(),
            maxBpm = samples.maxOfOrNull { it.beatsPerMinute.toInt() },
            zoneSeconds = zones?.let { basis -> HeartRateZones.timeInZones(samples, basis.boundsBpm, windows).map { it.seconds } }
                ?: List(HeartRateZones.COUNT) { 0L },
            trimp = resting?.let { workoutTrimp(samples, it.toLong(), zones.maxBpm.toLong(), windows, profile.sex) },
            activeKcal = estimate?.activeKcal,
            totalKcal = estimate?.totalKcal,
            splits = splits(workout),
            paceSecondsPerKm = if (distance != null && distance > 0) activeSeconds / (distance / METRES_PER_KM) else null,
            vo2Max = resting?.let {
                estimateRouteVo2Max(workout.exerciseType, routePoints(workout), samples, windows, it, zones.maxBpm)
            },
        )
    }

    /** The session less its pauses: the time every figure counts. */
    fun activeWindows(workout: WearLinkWorkout): List<CardioLoadTimeWindow> {
        val windows = ArrayList<CardioLoadTimeWindow>()
        var from = workout.startMillis
        for (pause in workout.pauses.sortedBy { it.startMillis }) {
            if (pause.startMillis > from) windows += window(from, minOf(pause.startMillis, workout.endMillis))
            from = maxOf(from, pause.endMillis)
        }
        if (workout.endMillis > from) windows += window(from, workout.endMillis)
        return windows
    }

    /**
     * The workout's calories, by health-calculations' one estimate
     * ([estimateWorkoutEnergy], the same OpenVitals uses): Keytel from the
     * time-weighted mean heart rate of the active time, else ACSM from a walk or
     * run's distance, else the activity's Compendium MET, over the active time.
     */
    fun energy(workout: WearLinkWorkout, beats: List<Beat>, profile: BodyProfile, day: LocalDate): WorkoutEnergyResult {
        val windows = activeWindows(workout)
        return estimateWorkoutEnergy(
            exerciseType = workout.exerciseType,
            durationMinutes = windows.sumOf { Duration.between(it.start, it.end).toMillis() } / MINUTE_MILLIS,
            meanHeartRateBpm = timeWeightedMeanHeartRate(samples(beats, workout).map { it.time to it.beatsPerMinute }, windows),
            profile = profile,
            today = day,
            distanceMeters = workout.distanceMetres?.toDouble()?.takeIf { it > 0.0 },
        )
    }

    /**
     * One split per whole kilometre of the route, its time without pauses, cut
     * by health-calculations ([buildActivitySplits], the same cut OpenVitals
     * shows): the kilometre mark is timed in proportion inside the step it
     * falls in, and each split's moving time leaves out the pauses. The
     * trailing part kilometre is not a lap and is dropped.
     */
    fun splits(workout: WearLinkWorkout): List<Split> =
        buildActivitySplits(
            workout = exerciseData(workout),
            routePoints = routePoints(workout),
            speedSamples = emptyList(),
            heartRateSamples = emptyList(),
            splitDistanceMeters = METRES_PER_KM,
        ).splits
            .filter { !it.isPartial }
            .map { Split(it.index - 1, it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.elapsedMs / 1000) }

    /** The workout as health-calculations reads a session: its pauses as PAUSE segments. */
    private fun exerciseData(workout: WearLinkWorkout) = ExerciseData(
        id = "",
        title = null,
        exerciseType = workout.exerciseType,
        startTime = Instant.ofEpochMilli(workout.startMillis),
        endTime = Instant.ofEpochMilli(workout.endMillis),
        durationMs = workout.endMillis - workout.startMillis,
        source = "",
        totalDistanceMeters = workout.distanceMetres?.toDouble(),
        segments = workout.pauses.map {
            ExerciseSegmentData(
                startTime = Instant.ofEpochMilli(it.startMillis),
                endTime = Instant.ofEpochMilli(it.endMillis),
                segmentType = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE,
                repetitions = 0,
            )
        },
    )

    /**
     * The recovery from [stopMillis], by [calculateHeartRateRecovery]: the falls from
     * the peak of the last seconds before the stop, and its verdict on the effort.
     * Whether the effort was near-maximal is judged against [profile]'s entered
     * maximum, else the one its age predicts.
     */
    fun recovery(beats: List<Beat>, stopMillis: Long, profile: BodyProfile, restingBpm: Int?, day: LocalDate): Recovery {
        val reading = calculateHeartRateRecovery(
            recoveryStart = Instant.ofEpochMilli(stopMillis),
            samples = beats.map { it.sample() },
            restingHeartRateBpm = restingBpm,
            ageYears = profile.ageYears(day),
            observedMaxHeartRateBpm = null,
            explicitMaxHeartRateBpm = profile.maxHeartRateBpm,
            sex = profile.sex,
        )
        val measured = reading.quality != HeartRateRecoveryQuality.INVALID
        fun drop(minutes: Long) = reading.markAt(Duration.ofMinutes(minutes))?.dropBpm?.toInt()?.takeIf { measured }
        return Recovery(drop(1), drop(2), reading.peakBpm?.toInt(), reading.quality)
    }

    /** Readings in the two minutes after a stop that show a recovery was measured (one a second), not the day's 10 s grid. */
    const val MIN_RECOVERY_READINGS: Int = 60

    /** The readings inside the session and outside its pauses. */
    private fun samples(beats: List<Beat>, workout: WearLinkWorkout): List<HeartRateSample> = beats
        .filter { it.epochMillis in workout.startMillis..workout.endMillis }
        .filter { beat -> workout.pauses.none { beat.epochMillis in it.startMillis until it.endMillis } }
        .map { it.sample() }

    private fun Beat.sample() = HeartRateSample(Instant.ofEpochMilli(epochMillis), bpm.toLong(), source = "")

    private fun routePoints(workout: WearLinkWorkout): List<ExerciseRoutePoint> = workout.route
        .sortedBy { it.epochMillis }
        .map {
            ExerciseRoutePoint(
                time = Instant.ofEpochMilli(it.epochMillis),
                latitude = it.latitudeE7 / 1e7,
                longitude = it.longitudeE7 / 1e7,
                altitudeMeters = it.altitudeDecimetres?.div(10.0),
                horizontalAccuracyMeters = it.accuracyDecimetres?.div(10.0),
                verticalAccuracyMeters = null,
            )
        }

    private fun window(fromMillis: Long, toMillis: Long) =
        CardioLoadTimeWindow(Instant.ofEpochMilli(fromMillis), Instant.ofEpochMilli(toMillis))

    private const val METRES_PER_KM = 1000.0
    private const val MINUTE_MILLIS = 60_000.0
}
