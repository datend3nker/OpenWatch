package tech.mmarca.openwatch.wearlink

import java.util.Base64

/**
 * A workout the phone has the watch record for an app (OpenLink's `RECORD` mode): the phone
 * starts, pauses, resumes and stops it, and asks its state, which is how it learns of a press on
 * the watch, since the watch never speaks first. Only a watch announcing [CAPABILITY] serves it.
 *
 * - `WO_CTL <command> <session> [<exerciseType>]` → one `WS` line. The command is `start` (with the
 *   Health Connect exercise type), `pause`, `resume`, `stop` or `state`; the session is the app's id,
 *   as base64url of its UTF-8, so it holds no space.
 * - `WS <session|-> <phase> <atMillis> <reason|->`: the phase of that session's workout on the
 *   watch, and since when by the watch's clock. `none`: the watch holds no workout for it.
 *
 * The watch records one workout at a time: a start while another runs is `failed busy`.
 */
object WearLinkWorkoutControl {

    const val CAPABILITY: String = "wc"
    const val REQUEST: String = "WO_CTL"
    const val STATE: String = "WS"

    /** The app's id is OpenLink's: at most 128 characters. */
    const val MAX_SESSION_LENGTH: Int = 128

    const val REASON_BUSY: String = "busy"
    const val REASON_UNSUPPORTED: String = "unsupported"
    const val REASON_STOPPED_ON_DEVICE: String = "stopped_on_device"
    const val REASON_STOPPED_BY_CLIENT: String = "stopped_by_client"

    enum class Command(val word: String) {
        START("start"), PAUSE("pause"), RESUME("resume"), STOP("stop"), STATE("state");

        companion object {
            fun fromWord(word: String): Command? = entries.firstOrNull { it.word == word }
        }
    }

    enum class Phase(val word: String) {
        RECORDING("recording"), PAUSED("paused"), STOPPED("stopped"), FAILED("failed"), NONE("none");

        val isOver: Boolean get() = this == STOPPED || this == FAILED || this == NONE

        companion object {
            fun fromWord(word: String): Phase? = entries.firstOrNull { it.word == word }
        }
    }

    /** [exerciseType] is read for [Command.START] only. */
    data class Control(val command: Command, val sessionId: String, val exerciseType: Int = 0)

    data class State(val sessionId: String?, val phase: Phase, val atMillis: Long, val reason: String? = null)

    fun formatControl(control: Control): String {
        val head = "$REQUEST ${control.command.word} ${encode(control.sessionId)}"
        return if (control.command == Command.START) "$head ${control.exerciseType}" else head
    }

    /** Null unless [line] is a well-formed control with a session of at most [MAX_SESSION_LENGTH]. */
    fun parseControl(line: String): Control? {
        val fields = line.trim().split(' ')
        if (fields.size !in 3..4 || fields[0] != REQUEST) return null
        val command = Command.fromWord(fields[1]) ?: return null
        val session = decode(fields[2]) ?: return null
        if (command == Command.START) {
            val type = fields.getOrNull(3)?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
            return Control(command, session, type)
        }
        return if (fields.size == 3) Control(command, session) else null
    }

    fun formatState(state: State): String =
        "$STATE ${state.sessionId?.let(::encode) ?: "-"} ${state.phase.word} ${state.atMillis} ${state.reason ?: "-"}"

    fun parseState(line: String): State? {
        val fields = line.trim().split(' ')
        if (fields.size != 5 || fields[0] != STATE) return null
        val session = if (fields[1] == "-") null else decode(fields[1]) ?: return null
        val phase = Phase.fromWord(fields[2]) ?: return null
        val at = fields[3].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val reason = fields[4].takeIf { it != "-" }?.takeIf { word -> word.all { it.isLetterOrDigit() || it == '_' } }
        if (fields[4] != "-" && reason == null) return null
        return State(session, phase, at, reason)
    }

    private fun encode(session: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(session.toByteArray(Charsets.UTF_8))

    private fun decode(field: String): String? {
        val bytes = runCatching { Base64.getUrlDecoder().decode(field) }.getOrNull() ?: return null
        val session = bytes.toString(Charsets.UTF_8)
        return session.takeIf { it.isNotBlank() && it.length <= MAX_SESSION_LENGTH && encode(it) == field }
    }
}
