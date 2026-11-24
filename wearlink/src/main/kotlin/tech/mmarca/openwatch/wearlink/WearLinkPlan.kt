package tech.mmarca.openwatch.wearlink

/** One set of a [WearPlanExercise], as the client holds it. A null target or result is "not asked" or "not known". */
data class WearPlanSet(
    val id: String,
    val repsMin: Int? = null,
    val repsMax: Int? = null,
    val targetSeconds: Int? = null,
    val targetMeters: Int? = null,
    /** In tenths of [unit]. */
    val weightTenths: Int? = null,
    val unit: WearPlanWeightUnit = WearPlanWeightUnit.KG,
    val done: Boolean = false,
    val repsDone: Int? = null,
    val secondsDone: Int? = null,
    val metersDone: Int? = null,
    val doneAtMillis: Long? = null,
    val updatedAtMillis: Long = 0L,
)

enum class WearPlanWeightUnit(val word: String) {
    KG("kg"),
    LB("lb"),
    ;

    companion object {
        fun fromWord(word: String): WearPlanWeightUnit? = entries.firstOrNull { it.word == word }
    }
}

enum class WearPlanKind(val word: String) {
    WEIGHTED("w"),
    CARDIO("c"),
    ;

    companion object {
        fun fromWord(word: String): WearPlanKind? = entries.firstOrNull { it.word == word }
    }
}

enum class WearPlanResistance(val word: String) {
    NONE("n"),
    EXTERNAL("e"),
    BODYWEIGHT("b"),
    ;

    companion object {
        fun fromWord(word: String): WearPlanResistance? = entries.firstOrNull { it.word == word }
    }
}

data class WearPlanExercise(
    val id: String,
    /** The exercise's name. The one text of a plan: shown on the watch, never logged, never stored past the workout. */
    val label: String,
    val kind: WearPlanKind = WearPlanKind.WEIGHTED,
    val resistance: WearPlanResistance = WearPlanResistance.NONE,
    val supersetWithNext: Boolean = false,
    /** The Health Connect exercise segment type, when the client gave one. */
    val segmentType: Int? = null,
    val restMinSeconds: Int = 0,
    val restMaxSeconds: Int = 0,
    val restAfterFailureSeconds: Int = 0,
    val sets: List<WearPlanSet> = emptyList(),
)

/**
 * The workout plan of one recorded session, always whole: [revision] orders two of the same
 * session, [currentSetId] is the set the client shows as next.
 */
data class WearPlan(
    val sessionId: String,
    val revision: Long,
    val currentSetId: String?,
    val exercises: List<WearPlanExercise>,
) {
    val setCount: Int get() = exercises.sumOf { it.sets.size }

    /** The set [currentSetId] names, or the first one not done, with its exercise and its place; null for none. */
    fun current(): WearPlanPosition? {
        val flat = exercises.flatMapIndexed { e, exercise -> exercise.sets.mapIndexed { s, set -> Triple(e, s, set) } }
        val at = flat.firstOrNull { it.third.id == currentSetId } ?: flat.firstOrNull { !it.third.done } ?: return null
        val exercise = exercises[at.first]
        return WearPlanPosition(exercise, at.third, at.second + 1, exercise.sets.size, flat.count { it.third.done }, flat.size)
    }
}

/** Where [WearPlan.current] stands: the exercise and set, the set's place in it, and how many of all sets are done. */
data class WearPlanPosition(
    val exercise: WearPlanExercise,
    val set: WearPlanSet,
    val setNumber: Int,
    val setsInExercise: Int,
    val doneSets: Int,
    val totalSets: Int,
)

/**
 * A plan on the wire, from the phone to the watch (`PLAN_*`, see [WearLinkProtocol]). A line holds
 * at most [WearLinkProtocol.MAX_LINE_LENGTH] bytes, a plan can hold 300 sets, so a plan is sent as
 * lines the watch collects and takes whole at `PLAN_END`; each is answered `PLAN_OK` or
 * `ERROR bad_request`:
 *
 * - `PLAN_BEGIN <session> <revision> <currentSetId|-> <exercises> <sets>`
 * - `PLAN_EX <id> <kind w|c> <resistance n|e|b> <superset 0|1> <segmentType|-> <restMin> <restMax> <restAfterFailure> <label…>`,
 *   the label is the rest of the line
 * - `PLAN_SET <exerciseId> <id> <repsMin|-> <repsMax|-> <seconds|-> <meters|-> <weightTenths|-> <kg|lb> <done 0|1>
 *   <repsDone|-> <secondsDone|-> <metersDone|-> <doneAt|-> <updatedAt>`, of the exercise last sent
 * - `PLAN_END <session> <revision>`
 * - `PLAN_CLEAR <session>`: the plan of the session is gone
 *
 * The limits are OpenLink's: 40 exercises, 30 sets in one, 300 in all, a label of 48 characters.
 * A plan that breaks one is dropped whole.
 */
object WearLinkPlan {

    const val BEGIN = "PLAN_BEGIN"
    const val EXERCISE = "PLAN_EX"
    const val SET = "PLAN_SET"
    const val END = "PLAN_END"
    const val CLEAR = "PLAN_CLEAR"
    const val OK = "PLAN_OK"

    const val MAX_EXERCISES = 40
    const val MAX_SETS_PER_EXERCISE = 30
    const val MAX_SETS = 300
    const val MAX_LABEL_LENGTH = 48
    private const val MAX_ID_LENGTH = 32
    private const val MAX_REPS = 1_000
    private const val MAX_WEIGHT_TENTHS = 10_000
    private const val MAX_SECONDS = 86_400
    private const val MAX_METERS = 1_000_000
    private const val NO_VALUE = "-"

    /** One line of a plan, parsed. */
    sealed class Line {
        data class Begin(val sessionId: String, val revision: Long, val currentSetId: String?, val exercises: Int, val sets: Int) : Line()
        data class Exercise(val exercise: WearPlanExercise) : Line()
        data class Set(val exerciseId: String, val set: WearPlanSet) : Line()
        data class End(val sessionId: String, val revision: Long) : Line()
        data class Clear(val sessionId: String) : Line()
    }

    /** The lines that carry [plan], in the order the watch needs them. */
    fun lineObjects(plan: WearPlan): List<Line> = buildList {
        add(Line.Begin(plan.sessionId, plan.revision, plan.currentSetId, plan.exercises.size, plan.setCount))
        for (exercise in plan.exercises) {
            add(Line.Exercise(exercise))
            for (set in exercise.sets) add(Line.Set(exercise.id, set))
        }
        add(Line.End(plan.sessionId, plan.revision))
    }

    /** [lineObjects] as text. The label's text is made printable. */
    fun lines(plan: WearPlan): List<String> = lineObjects(plan).map(::format)

    fun format(line: Line): String = when (line) {
        is Line.Begin -> "$BEGIN ${line.sessionId} ${line.revision} ${line.currentSetId ?: NO_VALUE} ${line.exercises} ${line.sets}"
        is Line.Exercise -> line.exercise.let { exercise ->
            listOf(
                EXERCISE, exercise.id, exercise.kind.word, exercise.resistance.word, if (exercise.supersetWithNext) "1" else "0",
                exercise.segmentType?.toString() ?: NO_VALUE, exercise.restMinSeconds, exercise.restMaxSeconds,
                exercise.restAfterFailureSeconds, label(exercise.label),
            ).joinToString(" ")
        }
        is Line.Set -> line.set.let { set ->
            listOf(
                SET, line.exerciseId, set.id, num(set.repsMin), num(set.repsMax), num(set.targetSeconds), num(set.targetMeters),
                num(set.weightTenths), set.unit.word, if (set.done) "1" else "0", num(set.repsDone), num(set.secondsDone),
                num(set.metersDone), num(set.doneAtMillis), set.updatedAtMillis,
            ).joinToString(" ")
        }
        is Line.End -> "$END ${line.sessionId} ${line.revision}"
        is Line.Clear -> "$CLEAR ${line.sessionId}"
    }

    fun clearLine(sessionId: String): String = format(Line.Clear(sessionId))

    /** Null unless [line] is a well-formed line of a plan. */
    fun parse(line: String): Line? {
        val head = line.trim().substringBefore(' ')
        return when (head) {
            BEGIN -> fields(line, 6)?.let { f ->
                val revision = f[2].toLongOrNull() ?: return null
                val exercises = f[4].toIntOrNull() ?: return null
                val sets = f[5].toIntOrNull() ?: return null
                if (!WearLinkRecordedWorkout.isSessionId(f[1]) || (f[3] != NO_VALUE && !isId(f[3])) || exercises !in 0..MAX_EXERCISES || sets !in 0..MAX_SETS) return null
                Line.Begin(f[1], revision, f[3].takeIf { it != NO_VALUE }, exercises, sets)
            }
            EXERCISE -> fields(line, 10, restIsLast = true)?.let { f ->
                val kind = WearPlanKind.fromWord(f[2]) ?: return null
                val resistance = WearPlanResistance.fromWord(f[3]) ?: return null
                val superset = flag(f[4]) ?: return null
                val segment = optional(f[5], 0..Int.MAX_VALUE) ?: return null
                val rests = (6..8).map { f[it].toIntOrNull()?.takeIf { rest -> rest in 0..MAX_SECONDS } ?: return null }
                val label = label(f[9])
                if (!isId(f[1]) || label.isEmpty()) return null
                Line.Exercise(
                    WearPlanExercise(f[1], label, kind, resistance, superset, segment.value, rests[0], rests[1], rests[2]),
                )
            }
            SET -> fields(line, 15)?.let { f ->
                val unit = WearPlanWeightUnit.fromWord(f[8]) ?: return null
                val done = flag(f[9]) ?: return null
                val repsMin = optional(f[3], 0..MAX_REPS) ?: return null
                val repsMax = optional(f[4], 0..MAX_REPS) ?: return null
                val seconds = optional(f[5], 0..MAX_SECONDS) ?: return null
                val meters = optional(f[6], 0..MAX_METERS) ?: return null
                val weight = optional(f[7], 0..MAX_WEIGHT_TENTHS) ?: return null
                val repsDone = optional(f[10], 0..MAX_REPS) ?: return null
                val secondsDone = optional(f[11], 0..MAX_SECONDS) ?: return null
                val metersDone = optional(f[12], 0..MAX_METERS) ?: return null
                val doneAt = if (f[13] == NO_VALUE) null else f[13].toLongOrNull() ?: return null
                val updatedAt = f[14].toLongOrNull() ?: return null
                if (!isId(f[1]) || !isId(f[2])) return null
                Line.Set(
                    f[1],
                    WearPlanSet(
                        f[2], repsMin.value, repsMax.value, seconds.value, meters.value, weight.value, unit, done,
                        repsDone.value, secondsDone.value, metersDone.value, doneAt, updatedAt,
                    ),
                )
            }
            END -> fields(line, 3)?.let { f ->
                if (!WearLinkRecordedWorkout.isSessionId(f[1])) return null
                Line.End(f[1], f[2].toLongOrNull() ?: return null)
            }
            CLEAR -> fields(line, 2)?.let { f -> if (WearLinkRecordedWorkout.isSessionId(f[1])) Line.Clear(f[1]) else null }
            else -> null
        }
    }

    /**
     * The watch's end: collects the lines of a plan and takes it whole at `PLAN_END`. Not thread safe;
     * one thread feeds it (the watch's link thread holds a lock around it).
     */
    class Assembler {
        private var staging: Staging? = null

        /** The plan the watch shows, or null. */
        var plan: WearPlan? = null
            private set

        private class Staging(
            val sessionId: String,
            val revision: Long,
            val currentSetId: String?,
            val exercises: Int,
            val sets: Int,
        ) {
            val collected = ArrayList<WearPlanExercise>()
            var setCount = 0
        }

        /**
         * Takes [line]. False for a line that does not follow the one before or breaks a limit: the plan
         * being collected is dropped, the shown one stays. A plan older than the shown one of its session,
         * or equal to it, is taken as done and changes nothing, so a replay is harmless.
         */
        fun accept(line: Line): Boolean {
            when (line) {
                is Line.Begin -> {
                    staging = Staging(line.sessionId, line.revision, line.currentSetId, line.exercises, line.sets)
                    return true
                }
                is Line.Exercise -> {
                    val s = staging ?: return false
                    val exercise = line.exercise
                    if (s.collected.size >= s.exercises || s.collected.any { it.id == exercise.id }) return drop()
                    s.collected += exercise
                    return true
                }
                is Line.Set -> {
                    val s = staging ?: return false
                    val last = s.collected.lastOrNull()
                    if (last == null || last.id != line.exerciseId || last.sets.size >= MAX_SETS_PER_EXERCISE || s.setCount >= s.sets) return drop()
                    if (s.collected.any { exercise -> exercise.sets.any { it.id == line.set.id } }) return drop()
                    s.collected[s.collected.lastIndex] = last.copy(sets = last.sets + line.set)
                    s.setCount++
                    return true
                }
                is Line.End -> {
                    val s = staging ?: return false
                    staging = null
                    if (s.sessionId != line.sessionId || s.revision != line.revision) return false
                    if (s.collected.size != s.exercises || s.setCount != s.sets) return false
                    if (s.currentSetId != null && s.collected.none { exercise -> exercise.sets.any { it.id == s.currentSetId } }) return false
                    val shown = plan
                    if (shown != null && shown.sessionId == s.sessionId && shown.revision >= s.revision) return true
                    plan = WearPlan(s.sessionId, s.revision, s.currentSetId, s.collected.toList())
                    return true
                }
                is Line.Clear -> {
                    staging = null
                    if (plan?.sessionId == line.sessionId) plan = null
                    return true
                }
            }
        }

        private fun drop(): Boolean {
            staging = null
            return false
        }
    }

    private class Optional(val value: Int?)

    /** `-` is no value; a number outside [range] or not a number is null (a bad line). */
    private fun optional(word: String, range: IntRange): Optional? =
        if (word == NO_VALUE) Optional(null) else word.toIntOrNull()?.takeIf { it in range }?.let { Optional(it) }

    private fun flag(word: String): Boolean? = when (word) {
        "1" -> true
        "0" -> false
        else -> null
    }

    private fun num(value: Number?): String = value?.toString() ?: NO_VALUE

    private fun label(text: String): String = WearLinkProtocol.cleanName(text).take(MAX_LABEL_LENGTH).trim()

    private fun isId(word: String): Boolean =
        word.length in 1..MAX_ID_LENGTH && word.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }

    /** [count] fields, or null for another number; with [restIsLast] the last one is the rest of the line, spaces kept. */
    private fun fields(line: String, count: Int, restIsLast: Boolean = false): List<String>? {
        val parts = line.trim().split(' ', limit = if (restIsLast) count else 0).let { all ->
            if (restIsLast) all else all.filter { it.isNotEmpty() }
        }
        if (parts.size != count || parts.any { it.isEmpty() }) return null
        return parts
    }
}
