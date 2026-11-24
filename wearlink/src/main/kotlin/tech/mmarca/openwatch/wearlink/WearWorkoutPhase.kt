package tech.mmarca.openwatch.wearlink

/**
 * What the watch says of the workout of one recorded session (`WO_STATE`): [RUNNING], [PAUSED], or [NONE] when
 * none runs for it, as after the wearer ended it there. The phone polls this, so a pause, resume or stop made
 * on the watch reaches the client that started the workout.
 */
enum class WearWorkoutPhase(val word: String) {
    RUNNING("run"),
    PAUSED("pause"),
    NONE("none"),
    ;

    companion object {
        fun fromWord(word: String): WearWorkoutPhase? = entries.firstOrNull { it.word == word }
    }
}
