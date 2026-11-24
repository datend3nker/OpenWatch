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
 * - `WS <session|-> <phase> <atMillis> <reason|-> [<zoneSeconds> <zoneBounds> <trimp> <recovery1> <recovery2>]`:
 *   the phase of that session's workout on the watch, and since when by the watch's clock. `none`:
 *   the watch holds no workout for it. A `stopped` one carries its [Summary]: the seconds in each
 *   heart rate zone and each zone's lowest bpm, zone 1 first, comma-separated, Banister's TRIMP in
 *   whole thousandths, and the falls one and two minutes after the stop; `-` for what it lacks.
 *
 * The watch records one workout at a time: a start while another runs is `failed busy`.
 */
object WearLinkWorkoutControl {

    const val CAPABILITY: String = "wc"

    /** Announced by a watch with a GPS receiver: a workout it records can carry its route. */
    const val CAPABILITY_GPS: String = "gp"
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

    data class State(val sessionId: String?, val phase: Phase, val atMillis: Long, val reason: String? = null, val summary: Summary? = null)

    /** What a stopped workout gives beyond its Health Connect session, as OpenLink's workout summary takes it. Every part optional. */
    data class Summary(
        val zoneSeconds: List<Int> = emptyList(),
        val zoneLowerBoundsBpm: List<Int> = emptyList(),
        val trimp: Double? = null,
        val recovery1MinBpm: Int? = null,
        val recovery2MinBpm: Int? = null,
    )

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

    fun formatState(state: State): String {
        val head = "$STATE ${state.sessionId?.let(::encode) ?: NONE} ${state.phase.word} ${state.atMillis} ${state.reason ?: NONE}"
        val summary = state.summary ?: return head
        return "$head ${list(summary.zoneSeconds)} ${list(summary.zoneLowerBoundsBpm)} " +
            "${summary.trimp?.let { Math.round(it * THOUSAND) } ?: NONE} ${summary.recovery1MinBpm ?: NONE} ${summary.recovery2MinBpm ?: NONE}"
    }

    fun parseState(line: String): State? {
        val fields = line.trim().split(' ')
        if ((fields.size != 5 && fields.size != 10) || fields[0] != STATE) return null
        val session = if (fields[1] == "-") null else decode(fields[1]) ?: return null
        val phase = Phase.fromWord(fields[2]) ?: return null
        val at = fields[3].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val reason = fields[4].takeIf { it != "-" }?.takeIf { word -> word.all { it.isLetterOrDigit() || it == '_' } }
        if (fields[4] != "-" && reason == null) return null
        val summary = if (fields.size == 10) parseSummary(fields.subList(5, 10)) ?: return null else null
        return State(session, phase, at, reason, summary)
    }

    private fun parseSummary(fields: List<String>): Summary? {
        val zones = numbers(fields[0]) ?: return null
        val bounds = numbers(fields[1]) ?: return null
        val trimp = optional(fields[2]) { it.toLongOrNull()?.takeIf { v -> v >= 0 }?.let { v -> v / THOUSAND } } ?: return null
        val one = optional(fields[3]) { it.toIntOrNull() } ?: return null
        val two = optional(fields[4]) { it.toIntOrNull() } ?: return null
        return Summary(zones, bounds, trimp.value, one.value, two.value)
    }

    /** A present value, or [NONE]'s absence; null for a field that is neither. */
    private class Optional<T>(val value: T?)

    private fun <T> optional(field: String, parse: (String) -> T?): Optional<T>? =
        if (field == NONE) Optional(null) else parse(field)?.let { Optional(it) }

    private fun numbers(field: String): List<Int>? =
        if (field == NONE) emptyList() else field.split(',').map { it.toIntOrNull()?.takeIf { v -> v >= 0 } ?: return null }

    private fun list(values: List<Int>): String = if (values.isEmpty()) NONE else values.joinToString(",")

    private const val NONE = "-"
    private const val THOUSAND = 1000.0

    private fun encode(session: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(session.toByteArray(Charsets.UTF_8))

    private fun decode(field: String): String? {
        val bytes = runCatching { Base64.getUrlDecoder().decode(field) }.getOrNull() ?: return null
        val session = bytes.toString(Charsets.UTF_8)
        return session.takeIf { it.isNotBlank() && it.length <= MAX_SESSION_LENGTH && encode(it) == field }
    }
}
