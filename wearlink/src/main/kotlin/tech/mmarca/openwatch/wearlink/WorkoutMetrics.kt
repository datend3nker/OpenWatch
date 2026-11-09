package tech.mmarca.openwatch.wearlink

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a workout comes to, worked out from its heart rate, its route and the
 * body profile. Pure and shared, so the watch's summary and what the phone
 * writes to Health Connect are the same numbers. Every formula names its
 * source; where there is none the comment says so.
 */
object WorkoutMetrics {

    /** One heart rate reading. */
    data class Beat(val epochMillis: Long, val bpm: Int)

    /** What the energy and the load need of the wearer; any may be missing. */
    data class Body(
        val weightKg: Double? = null,
        val heightCm: Double? = null,
        val ageYears: Int? = null,
        /** Null when unknown: the sex-specific formulas then take the mean of both. */
        val female: Boolean? = null,
    )

    /** One split: the time the distance [index] + 1 kilometres took, pauses left out. */
    data class Split(val index: Int, val startMillis: Long, val endMillis: Long, val activeSeconds: Long)

    data class Summary(
        val averageBpm: Int?,
        val maxBpm: Int?,
        /** Seconds in each of the five zones, zone 1 first ([HeartRateZones]); below zone 1 is not counted. */
        val zoneSeconds: List<Long>,
        val trimp: Double?,
        /** Energy above rest, kcal: Keytel minus Mifflin-St Jeor. Null without weight, age and height. */
        val activeKcal: Double?,
        /** Whole energy, kcal: Keytel alone. Null without weight and age. */
        val totalKcal: Double?,
        val splits: List<Split>,
        /** Seconds per kilometre over the active time; null without distance. */
        val paceSecondsPerKm: Double?,
        /** ml/kg/min; null for a sport or a session it does not apply to. */
        val vo2Max: Double?,
    )

    /** Readings further apart than this are a gap, not a reading that lasted. */
    const val MAX_SAMPLE_GAP_MILLIS: Long = 30_000

    fun summarize(
        workout: WearLinkWorkout,
        beats: List<Beat>,
        zones: HeartRateZones.Basis?,
        body: Body,
        sport: WorkoutSport?,
    ): Summary {
        val active = activeBeats(workout, beats)
        val spans = spans(active, workout)
        val total = spans.sumOf { it.second }.toDouble()
        val average = if (total > 0) (spans.sumOf { (beat, ms) -> beat.bpm * ms.toDouble() } / total).toInt() else null
        val zoneSeconds = LongArray(HeartRateZones.COUNT)
        if (zones != null) {
            for ((beat, ms) in spans) HeartRateZones.zoneOf(beat.bpm.toDouble(), zones)?.let { zoneSeconds[it] += ms / 1000 }
        }
        val splits = splits(workout)
        val distance = workout.distanceMetres
        val activeSeconds = activeMillis(workout) / 1000.0
        return Summary(
            averageBpm = average,
            maxBpm = active.maxOfOrNull { it.bpm },
            zoneSeconds = zoneSeconds.toList(),
            trimp = zones?.restingBpm?.let { banisterTrimp(spans, it, zones.maxBpm, body.female) },
            activeKcal = activeKcal(spans, body),
            totalKcal = keytelKcal(spans, body),
            splits = splits,
            paceSecondsPerKm = if (distance != null && distance > 0) activeSeconds / (distance / 1000.0) else null,
            vo2Max = if (sport != null && zones?.restingBpm != null) vo2Max(workout, active, zones, sport) else null,
        )
    }

    /** The readings inside the session and outside its pauses. */
    fun activeBeats(workout: WearLinkWorkout, beats: List<Beat>): List<Beat> = beats
        .filter { it.epochMillis in workout.startMillis..workout.endMillis }
        .filter { beat -> workout.pauses.none { beat.epochMillis in it.startMillis until it.endMillis } }
        .sortedBy { it.epochMillis }

    /**
     * Each reading with how long it stands: until the next one, a pause or the
     * end of the session, at most [MAX_SAMPLE_GAP_MILLIS].
     */
    fun spans(beats: List<Beat>, workout: WearLinkWorkout): List<Pair<Beat, Long>> = beats.mapIndexed { i, beat ->
        val next = beats.getOrNull(i + 1)?.epochMillis ?: workout.endMillis
        val pause = workout.pauses.filter { it.startMillis > beat.epochMillis }.minOfOrNull { it.startMillis } ?: Long.MAX_VALUE
        beat to (minOf(next, pause, workout.endMillis) - beat.epochMillis).coerceIn(0, MAX_SAMPLE_GAP_MILLIS)
    }

    private fun activeMillis(workout: WearLinkWorkout): Long =
        (workout.endMillis - workout.startMillis) - workout.pauses.sumOf { (it.endMillis - it.startMillis).coerceAtLeast(0) }

    /**
     * Banister's training impulse: minutes times the heart rate reserve fraction
     * times 0.64·e^(1.92·HRr) for men, 0.86·e^(1.67·HRr) for women. Morton,
     * Fitz-Clarke and Banister, J Appl Physiol 1990;69(3):1171-1177,
     * doi:10.1152/jappl.1990.69.3.1171. Unknown sex takes the mean of both.
     */
    fun banisterTrimp(spans: List<Pair<Beat, Long>>, restingBpm: Int, maxBpm: Int, female: Boolean?): Double? {
        if (maxBpm <= restingBpm || spans.isEmpty()) return null
        return spans.sumOf { (beat, ms) ->
            val reserve = ((beat.bpm - restingBpm).toDouble() / (maxBpm - restingBpm)).coerceIn(0.0, 1.0)
            val male = 0.64 * exp(1.92 * reserve)
            val woman = 0.86 * exp(1.67 * reserve)
            val weight = when (female) {
                true -> woman
                false -> male
                null -> (male + woman) / 2
            }
            ms / 60_000.0 * reserve * weight
        }
    }

    /**
     * Energy from heart rate, Keytel et al., "Prediction of energy expenditure
     * from heart rate monitoring during submaximal exercise", J Sports Sci
     * 2005;23(3):289-297, doi:10.1080/02640410470001730089, the equations
     * without VO2max, in kJ/min:
     * men −55.0969 + 0.6309·HR + 0.1988·kg + 0.2017·age,
     * women −20.4022 + 0.4472·HR − 0.1263·kg + 0.074·age.
     * Unknown sex takes the mean of both. A minute never counts below the
     * resting rate of [mifflinKcalPerMinute] when that is known, nor below zero.
     */
    fun keytelKcal(spans: List<Pair<Beat, Long>>, body: Body): Double? {
        val kg = body.weightKg ?: return null
        val age = body.ageYears ?: return null
        val floor = mifflinKcalPerMinute(body) ?: 0.0
        return spans.sumOf { (beat, ms) -> maxOf(keytelKcalPerMinute(beat.bpm.toDouble(), kg, age, body.female), floor) * ms / 60_000.0 }
    }

    private fun keytelKcalPerMinute(bpm: Double, kg: Double, age: Int, female: Boolean?): Double {
        val male = -55.0969 + 0.6309 * bpm + 0.1988 * kg + 0.2017 * age
        val woman = -20.4022 + 0.4472 * bpm - 0.1263 * kg + 0.074 * age
        val kj = when (female) {
            true -> woman
            false -> male
            null -> (male + woman) / 2
        }
        return (kj / KJ_PER_KCAL).coerceAtLeast(0.0)
    }

    /** [keytelKcal] less the resting energy of the same minutes: what the session added. */
    fun activeKcal(spans: List<Pair<Beat, Long>>, body: Body): Double? {
        val total = keytelKcal(spans, body) ?: return null
        val rest = mifflinKcalPerMinute(body) ?: return null
        return (total - rest * spans.sumOf { it.second } / 60_000.0).coerceAtLeast(0.0)
    }

    /**
     * Resting energy per minute, Mifflin et al., "A new predictive equation for
     * resting energy expenditure in healthy individuals", Am J Clin Nutr
     * 1990;51(2):241-247, doi:10.1093/ajcn/51.2.241: 10·kg + 6.25·cm − 5·age,
     * +5 for men, −161 for women, per day. Unknown sex takes the mean.
     */
    fun mifflinKcalPerMinute(body: Body): Double? {
        val kg = body.weightKg ?: return null
        val cm = body.heightCm ?: return null
        val age = body.ageYears ?: return null
        val sexTerm = when (body.female) {
            true -> -161.0
            false -> 5.0
            null -> -78.0
        }
        return (10 * kg + 6.25 * cm - 5 * age + sexTerm) / MINUTES_PER_DAY
    }

    /** The route's distance, haversine between fixes on a sphere of the mean Earth radius. */
    fun metresBetween(a: WearLinkWorkout.RoutePoint, b: WearLinkWorkout.RoutePoint): Double {
        val lat1 = Math.toRadians(a.latitudeE7 / 1e7)
        val lat2 = Math.toRadians(b.latitudeE7 / 1e7)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians((b.longitudeE7 - a.longitudeE7) / 1e7)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_METRES * asin(sqrt(h))
    }

    /** One split per whole kilometre of the route, its time without pauses. */
    fun splits(workout: WearLinkWorkout): List<Split> {
        val route = workout.route.sortedBy { it.epochMillis }
        if (route.size < 2) return emptyList()
        val splits = ArrayList<Split>()
        var metres = 0.0
        var splitStart = workout.startMillis
        for (i in 1 until route.size) {
            val step = metresBetween(route[i - 1], route[i])
            val before = metres
            metres += step
            while (metres >= (splits.size + 1) * METRES_PER_KM && step > 0) {
                // The kilometre mark falls inside this step: time it in proportion.
                val share = ((splits.size + 1) * METRES_PER_KM - before) / step
                val at = route[i - 1].epochMillis + ((route[i].epochMillis - route[i - 1].epochMillis) * share).toLong()
                splits += Split(splits.size, splitStart, at, activeBetween(workout, splitStart, at) / 1000)
                splitStart = at
            }
        }
        return splits
    }

    private fun activeBetween(workout: WearLinkWorkout, from: Long, to: Long): Long =
        (to - from) - workout.pauses.sumOf { p -> (minOf(p.endMillis, to) - maxOf(p.startMillis, from)).coerceAtLeast(0) }

    /**
     * VO2max from a walk, run or hike outdoors: for each steady minute, the
     * oxygen cost of the speed from the ACSM's metabolic equations (walking
     * 0.1 ml/kg/min per m/min, running 0.2, plus 3.5 at rest; ACSM's Guidelines
     * for Exercise Testing and Prescription, 11th ed., 2021), divided by the
     * heart rate reserve fraction, since the fraction of heart rate reserve
     * tracks the fraction of VO2 reserve (Swain and Leutholtz, Med Sci Sports
     * Exerc 1997;29(3):410-414, doi:10.1097/00005768-199703000-00018). The
     * median of the minutes counts. Flat ground is assumed: the slope terms
     * need an elevation the GPS gives too coarsely. Minutes below 40 % or above
     * 90 % of the reserve, or slower than walking, are left out (an app choice,
     * no source). Only outdoor walking, running and hiking: the equations are
     * for those, and the same restriction holds for the watches that publish
     * such estimates (Apple: outdoor walk, run and hike).
     */
    fun vo2Max(workout: WearLinkWorkout, beats: List<Beat>, zones: HeartRateZones.Basis, sport: WorkoutSport): Double? {
        val resting = zones.restingBpm ?: return null
        if (sport !in VO2_SPORTS || zones.maxBpm <= resting) return null
        val route = workout.route.sortedBy { it.epochMillis }
        if (route.size < 2) return null
        val estimates = ArrayList<Double>()
        var from = 0
        for (to in 1 until route.size) {
            val span = route[to].epochMillis - route[from].epochMillis
            if (span < MINUTE_MILLIS) continue
            val inPause = workout.pauses.any { it.startMillis < route[to].epochMillis && it.endMillis > route[from].epochMillis }
            if (!inPause) {
                val metres = (from until to).sumOf { metresBetween(route[it], route[it + 1]) }
                val speed = metres / (span / MINUTE_MILLIS.toDouble())
                val minute = beats.filter { it.epochMillis in route[from].epochMillis..route[to].epochMillis }
                if (minute.isNotEmpty() && speed >= MIN_WALKING_METRES_PER_MINUTE) {
                    val bpm = minute.map { it.bpm }.average()
                    val reserve = (bpm - resting) / (zones.maxBpm - resting)
                    if (reserve in MIN_VO2_RESERVE..MAX_VO2_RESERVE) {
                        val running = sport == WorkoutSport.RUNNING || speed > RUNNING_METRES_PER_MINUTE
                        val vo2 = REST_VO2 + (if (running) 0.2 else 0.1) * speed
                        estimates += (vo2 - REST_VO2) / reserve + REST_VO2
                    }
                }
            }
            from = to
        }
        if (estimates.size < MIN_VO2_MINUTES) return null
        return estimates.sorted()[estimates.size / 2]
    }

    /**
     * Heart rate recovery: the heart rate at the end of exercise less the one
     * a minute (and two) later. Cole et al., "Heart-rate recovery immediately
     * after exercise as a predictor of mortality", N Engl J Med
     * 1999;341(18):1351-1357, doi:10.1056/NEJM199910283411804: a fall of 12
     * beats or less in the first minute was abnormal. Each value is the mean of
     * the readings within five seconds of its moment.
     */
    fun recovery(beats: List<Beat>, stopMillis: Long): Pair<Int?, Int?> {
        fun around(at: Long) = beats.filter { it.epochMillis in (at - RECOVERY_WINDOW_MILLIS)..(at + RECOVERY_WINDOW_MILLIS) }
            .map { it.bpm }.takeIf { it.isNotEmpty() }?.average()
        val end = beats.filter { it.epochMillis in (stopMillis - RECOVERY_WINDOW_MILLIS)..stopMillis }
            .map { it.bpm }.takeIf { it.isNotEmpty() }?.average() ?: return null to null
        val one = around(stopMillis + MINUTE_MILLIS)?.let { (end - it).toInt() }
        val two = around(stopMillis + 2 * MINUTE_MILLIS)?.let { (end - it).toInt() }
        return one to two
    }

    private val VO2_SPORTS = setOf(WorkoutSport.WALKING, WorkoutSport.RUNNING, WorkoutSport.HIKING)
    private const val KJ_PER_KCAL = 4.184
    private const val MINUTES_PER_DAY = 1440.0
    private const val EARTH_RADIUS_METRES = 6_371_008.8
    private const val METRES_PER_KM = 1000.0
    private const val MINUTE_MILLIS = 60_000L
    private const val REST_VO2 = 3.5
    private const val MIN_WALKING_METRES_PER_MINUTE = 50.0
    private const val RUNNING_METRES_PER_MINUTE = 134.0
    private const val MIN_VO2_RESERVE = 0.4
    private const val MAX_VO2_RESERVE = 0.9
    private const val MIN_VO2_MINUTES = 5
    private const val RECOVERY_WINDOW_MILLIS = 5_000L
}

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
