package tech.mmarca.openwatch.wearlink

/**
 * The sports the watch records, with the facts both apps need about each:
 * the phone's entry form reads the same table, so a sport is described once.
 * Names and icons stay in the apps' string resources.
 *
 * [gps], [distance] and [elevation] are what the phone's form offers for the
 * sport. [steps] and [floors] are what the watch counts while it records.
 * The watch measures a distance only with GPS; the phone derives an indoor
 * one afterwards.
 */
enum class WorkoutSport(
    /** The phone's entry type id. Stored in its preferences, so never renamed. */
    val id: String,
    /** The Health Connect `EXERCISE_TYPE_*` value, which is also what the wire carries. */
    val exerciseType: Int,
    val gps: Boolean,
    val distance: Boolean,
    val elevation: Boolean,
    val steps: Boolean,
    val floors: Boolean = false,
) {
    WALKING("79", 79, gps = true, distance = true, elevation = true, steps = true),
    RUNNING("56", 56, gps = true, distance = true, elevation = true, steps = true),
    HIKING("37", 37, gps = true, distance = true, elevation = true, steps = true),
    BIKING("8", 8, gps = true, distance = true, elevation = true, steps = false),

    TREADMILL("treadmill", 57, gps = false, distance = true, elevation = false, steps = true),
    STAIR_CLIMBING("68", 68, gps = false, distance = true, elevation = true, steps = true, floors = true),
    STAIR_CLIMBING_MACHINE("69", 69, gps = false, distance = true, elevation = false, steps = false),
    STATIONARY_BIKE("stationary_bike", 9, gps = false, distance = true, elevation = false, steps = false),
    ELLIPTICAL("25", 25, gps = false, distance = true, elevation = false, steps = false),
    ROWING_MACHINE("54", 54, gps = false, distance = true, elevation = false, steps = false),
    STRENGTH_TRAINING("70", 70, gps = false, distance = false, elevation = false, steps = false),
    HIIT("36", 36, gps = false, distance = false, elevation = false, steps = false),
    YOGA("83", 83, gps = false, distance = false, elevation = false, steps = false),
    PILATES("48", 48, gps = false, distance = false, elevation = false, steps = false),
    STRETCHING("71", 71, gps = false, distance = false, elevation = false, steps = false),
    DANCING("16", 16, gps = false, distance = false, elevation = false, steps = false),
    VOLLEYBALL("78", 78, gps = false, distance = false, elevation = false, steps = false),
    OTHER_WORKOUT("0", 0, gps = false, distance = false, elevation = false, steps = false),
    ;

    /** True for a sport where speed reads as pace (on foot), false where it reads as km/h. */
    val pace: Boolean get() = gps && steps

    companion object {
        /** The sport for a wire or Health Connect type, or null for a type the watch does not record. */
        fun fromExerciseType(exerciseType: Int): WorkoutSport? = entries.firstOrNull { it.exerciseType == exerciseType }

        fun fromId(id: String?): WorkoutSport? = entries.firstOrNull { it.id == id }
    }
}
