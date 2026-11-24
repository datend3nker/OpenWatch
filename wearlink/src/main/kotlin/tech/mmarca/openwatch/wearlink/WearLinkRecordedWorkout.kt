package tech.mmarca.openwatch.wearlink

/**
 * The lines that start, steer and follow a workout a phone's client recorded on the watch (see [WearLinkProtocol];
 * a plan is [WearLinkPlan]). The session is the client's own id, which is often a UUID:
 *
 * - `WO_START <session> <sportId>`, `WO_STOP <session>`, `WO_PAUSE <session>`, `WO_RESUME <session>` → `WO_OK`, or
 *   `ERROR bad_request` when the watch cannot (one runs already, none runs for the session).
 * - `WO_STATE <session>` → `WO_STATE run|pause|none` ([WearWorkoutPhase]): the phone asks every few seconds, so a
 *   pause, resume or stop made on the watch reaches the client.
 */
object WearLinkRecordedWorkout {

    /** `WO_START <session> <sportId>` starts a workout on the watch; `WO_STOP <session>` ends it. Both answered `WO_OK`. */
    const val START: String = "WO_START"
    const val STOP: String = "WO_STOP"
    const val OK: String = "WO_OK"

    /** `WO_PAUSE <session>` and `WO_RESUME <session>`, answered `WO_OK`; `WO_STATE <session>` is answered `WO_STATE run|pause|none`. */
    const val PAUSE: String = "WO_PAUSE"
    const val RESUME: String = "WO_RESUME"
    const val STATE: String = "WO_STATE"

    /** A session id on the wire, as OpenLink's: at most this long. A client's is often a UUID, 36 characters. */
    const val MAX_SESSION_LENGTH: Int = 128

    /** A session id as a field of a line: printable ASCII with no space, at most [MAX_SESSION_LENGTH]. */
    fun isSessionId(word: String): Boolean = word.length in 1..MAX_SESSION_LENGTH && word.all { it in '!'..'~' }

    internal fun format(request: WearLinkProtocol.Request): String = when (request) {
        is WearLinkProtocol.Request.WorkoutStart -> "$START ${request.sessionId} ${request.sportId}"
        is WearLinkProtocol.Request.WorkoutStop -> "$STOP ${request.sessionId}"
        is WearLinkProtocol.Request.WorkoutPause -> "$PAUSE ${request.sessionId}"
        is WearLinkProtocol.Request.WorkoutResume -> "$RESUME ${request.sessionId}"
        is WearLinkProtocol.Request.WorkoutStatus -> "$STATE ${request.sessionId}"
        else -> throw IllegalArgumentException("Not a workout request")
    }

    /** Null unless [line] is one of the requests above. */
    internal fun parse(line: String): WearLinkProtocol.Request? {
        val fields = line.trim().split(' ').filter { it.isNotEmpty() }
        if (fields.size !in 2..3 || !isSessionId(fields[1])) return null
        return when {
            fields.size == 3 && fields[0] == START && isSessionId(fields[2]) -> WearLinkProtocol.Request.WorkoutStart(fields[1], fields[2])
            fields.size == 2 && fields[0] == STOP -> WearLinkProtocol.Request.WorkoutStop(fields[1])
            fields.size == 2 && fields[0] == PAUSE -> WearLinkProtocol.Request.WorkoutPause(fields[1])
            fields.size == 2 && fields[0] == RESUME -> WearLinkProtocol.Request.WorkoutResume(fields[1])
            fields.size == 2 && fields[0] == STATE -> WearLinkProtocol.Request.WorkoutStatus(fields[1])
            else -> null
        }
    }
}
