package tech.mmarca.openwatch.domain.insights

import androidx.health.connect.client.records.ExerciseSessionRecord

/** One activity of the Compendium: its five-digit code and MET value. */
data class CompendiumActivity(val code: String, val met: Double)

/**
 * MET values from the 2024 Adult Compendium of Physical Activities: Herrmann
 * et al., J Sport Health Sci 13(1):6–12 (2024), doi:10.1016/j.jshs.2023.10.010,
 * tables at pacompendium.com. Written for adults aged 19 to 59.
 *
 * Each Health Connect exercise type maps to one Compendium code. Where the
 * Compendium has several intensities, the general or moderate entry is used:
 * that choice is this project's, the codes let anyone check it. A type with no
 * matching activity (Australian football, wheelchair, guided breathing) has
 * none, so no estimate is made for it.
 *
 * 1 MET is 3.5 ml O2 per kg per minute and a litre of oxygen is taken as
 * 5 kcal, the conversions the Compendium's corrected-MET page uses: gross kcal
 * = MET × 3.5 × weight × minutes × 5 / 1000.
 */
object CompendiumMet {
    private const val OxygenMlPerKgMinute = 3.5
    private const val KcalPerLitreOxygen = 5.0

    fun grossKcal(met: Double, weightKg: Double, durationMinutes: Double): Double =
        met * OxygenMlPerKgMinute * weightKg * durationMinutes * KcalPerLitreOxygen / 1000.0

    fun forExerciseType(exerciseType: Int): CompendiumActivity? = byExerciseType[exerciseType]

    private val byExerciseType: Map<Int, CompendiumActivity> = mapOf(
        // Running (12) and walking (17)
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to CompendiumActivity("12150", 8.0),
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to CompendiumActivity("12150", 8.0),
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING to CompendiumActivity("17190", 3.8),
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING to CompendiumActivity("17080", 6.0),
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING to CompendiumActivity("17131", 6.8),
        // Bicycling (01)
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING to CompendiumActivity("01014", 7.0),
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to CompendiumActivity("01200", 6.8),
        // Conditioning exercise (02)
        ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP to CompendiumActivity("02008", 5.0),
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS to CompendiumActivity("02030", 3.5),
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL to CompendiumActivity("02048", 5.0),
        ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS to CompendiumActivity("02061", 5.0),
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to CompendiumActivity("02210", 7.0),
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES to CompendiumActivity("02105", 2.8),
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE to CompendiumActivity("02071", 5.0),
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE to CompendiumActivity("02065", 9.3),
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to CompendiumActivity("02054", 3.5),
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING to CompendiumActivity("02101", 2.3),
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to CompendiumActivity("02052", 5.0),
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA to CompendiumActivity("02175", 2.3),
        // Dancing (03)
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING to CompendiumActivity("03025", 4.5),
        // Sports (15)
        ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON to CompendiumActivity("15030", 5.5),
        ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL to CompendiumActivity("15620", 5.0),
        ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL to CompendiumActivity("15055", 7.5),
        ExerciseSessionRecord.EXERCISE_TYPE_BOXING to CompendiumActivity("15110", 5.8),
        ExerciseSessionRecord.EXERCISE_TYPE_CRICKET to CompendiumActivity("15150", 4.8),
        ExerciseSessionRecord.EXERCISE_TYPE_FENCING to CompendiumActivity("15200", 6.0),
        ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN to CompendiumActivity("15230", 8.0),
        ExerciseSessionRecord.EXERCISE_TYPE_FRISBEE_DISC to CompendiumActivity("15240", 3.0),
        ExerciseSessionRecord.EXERCISE_TYPE_GOLF to CompendiumActivity("15255", 4.5),
        ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS to CompendiumActivity("15300", 3.8),
        ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL to CompendiumActivity("15320", 12.0),
        ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY to CompendiumActivity("15360", 8.0),
        ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS to CompendiumActivity("15425", 5.3),
        ExerciseSessionRecord.EXERCISE_TYPE_PARAGLIDING to CompendiumActivity("15503", 1.8),
        ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL to CompendiumActivity("15530", 7.0),
        ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING to CompendiumActivity("15533", 8.0),
        ExerciseSessionRecord.EXERCISE_TYPE_ROLLER_HOCKEY to CompendiumActivity("15590", 7.0),
        ExerciseSessionRecord.EXERCISE_TYPE_RUGBY to CompendiumActivity("15562", 6.3),
        ExerciseSessionRecord.EXERCISE_TYPE_SKATING to CompendiumActivity("15590", 7.0),
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER to CompendiumActivity("15610", 7.0),
        ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL to CompendiumActivity("15620", 5.0),
        ExerciseSessionRecord.EXERCISE_TYPE_SQUASH to CompendiumActivity("15652", 7.3),
        ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS to CompendiumActivity("15660", 4.0),
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS to CompendiumActivity("15675", 6.8),
        ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL to CompendiumActivity("15710", 4.0),
        // Water activities (18)
        ExerciseSessionRecord.EXERCISE_TYPE_PADDLING to CompendiumActivity("18100", 5.0),
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING to CompendiumActivity("18070", 3.5),
        ExerciseSessionRecord.EXERCISE_TYPE_SAILING to CompendiumActivity("18120", 3.0),
        ExerciseSessionRecord.EXERCISE_TYPE_SCUBA_DIVING to CompendiumActivity("18200", 7.0),
        ExerciseSessionRecord.EXERCISE_TYPE_SURFING to CompendiumActivity("18220", 3.0),
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to CompendiumActivity("18300", 6.0),
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to CompendiumActivity("18240", 5.8),
        ExerciseSessionRecord.EXERCISE_TYPE_WATER_POLO to CompendiumActivity("18360", 10.0),
        // Winter activities (19)
        ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING to CompendiumActivity("19030", 7.0),
        ExerciseSessionRecord.EXERCISE_TYPE_SKIING to CompendiumActivity("19075", 7.0),
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING to CompendiumActivity("19160", 6.3),
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING to CompendiumActivity("19190", 5.3),
    )
}
