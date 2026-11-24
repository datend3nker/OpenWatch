package tech.mmarca.openwatch.wearlink

import java.io.IOException

/** What one exchange with the watch came to. */
sealed class WearLinkOutcome<out T> {
    data class Ok<T>(val value: T) : WearLinkOutcome<T>()
    data object Pending : WearLinkOutcome<Nothing>()
    data class Unauthorized(val reason: WearLinkProtocol.UnauthorizedReason) : WearLinkOutcome<Nothing>()
    data class VersionMismatch(val min: Int, val max: Int) : WearLinkOutcome<Nothing>()
    data class Refused(val code: WearLinkProtocol.ErrorCode) : WearLinkOutcome<Nothing>()

    /** The watch answered something the protocol does not allow here. */
    data class ProtocolError(val detail: String) : WearLinkOutcome<Nothing>()

    /** The connection ended before the answer: closed by the watch, the watchdog, or the link. */
    data object Closed : WearLinkOutcome<Nothing>()
}

/** An open connection whose hello the watch answered `OK`; requests go through it until [close]. */
class WearLinkSession internal constructor(
    val connection: WearLinkConnection,
    val peer: WearLinkProtocol.HelloReply.Ok,
    private val reader: LineReader,
    private val writer: LineWriter,
) {
    fun ping(): WearLinkOutcome<Unit> = exchange(WearLinkProtocol.Request.Ping) { line ->
        if (line.trim() == WearLinkProtocol.PONG) Reply.Done(Unit) else Reply.Unexpected
    }

    /** One live window of up to [seconds]: hands each reading to [onSample] as it arrives; the count when it ends. */
    fun liveHeartRate(seconds: Int, onSample: (WearLinkProtocol.HeartRateSample) -> Unit): WearLinkOutcome<Int> =
        exchange(WearLinkProtocol.Request.LiveHeartRate(seconds)) { line ->
            WearLinkProtocol.parseSample(line)?.let { onSample(it); return@exchange Reply.More }
            WearLinkProtocol.parseEnd(line)?.let { return@exchange Reply.Done(it.count) }
            Reply.Unexpected
        }

    /** Every setting the watch holds. Only call when the peer advertised [WearLinkProtocol.CAP_PREFERENCES]. */
    fun pullPreferences(): WearLinkOutcome<Map<String, String>> {
        val held = LinkedHashMap<String, String>()
        return exchange(WearLinkProtocol.Request.GetPreferences) { line ->
            WearLinkProtocol.parsePreference(line)?.let { held[it.first] = it.second; return@exchange Reply.More }
            WearLinkProtocol.parseEnd(line)?.let { return@exchange Reply.Done(held.toMap()) }
            Reply.Unexpected
        }
    }

    /** Changes one setting on the watch. An unknown key or value comes back as a refusal. */
    fun setPreference(key: String, value: String): WearLinkOutcome<Unit> =
        exchange(WearLinkProtocol.Request.SetPreference(key, value)) { line ->
            if (line.trim() == WearLinkProtocol.PREFERENCE_OK) Reply.Done(Unit) else Reply.Unexpected
        }

    /**
     * The workouts that started after [sinceEpochMillis]. A header this side cannot read drops the
     * pauses and points that follow it, so they never attach to the workout before.
     */
    fun pullWorkouts(sinceEpochMillis: Long, limit: Int): WearLinkOutcome<Page<WearLinkWorkout>> {
        val items = ArrayList<WearLinkWorkout>()
        var current: WearLinkWorkout? = null
        val pauses = ArrayList<WearLinkWorkout.Pause>()
        val points = ArrayList<WearLinkWorkout.RoutePoint>()
        fun finish() {
            current?.let { items += it.copy(pauses = pauses.toList(), route = points.toList()) }
            current = null
            pauses.clear()
            points.clear()
        }
        return exchange(WearLinkProtocol.Request.WorkoutsSince(sinceEpochMillis, limit)) { line ->
            if (line.startsWith("${WearLinkWorkoutLines.WORKOUT} ")) {
                finish()
                current = WearLinkWorkoutLines.parseHeader(line)
                return@exchange Reply.More
            }
            if (current != null) {
                WearLinkWorkoutLines.parsePause(line)?.let { if (pauses.size < WearLinkWorkoutLines.MAX_PAUSES) pauses += it; return@exchange Reply.More }
                WearLinkWorkoutLines.parsePoint(line)?.let { if (points.size < WearLinkWorkoutLines.MAX_POINTS) points += it; return@exchange Reply.More }
            }
            WearLinkProtocol.parseEnd(line)?.let {
                finish()
                return@exchange Reply.Done(Page(items, it.more))
            }
            Reply.Unexpected
        }
    }


    /** Hands the watch the phone's figures for one day. Only call when the peer advertised [WearLinkProtocol.CAP_DAY_SUMMARY]. */
    fun putDay(day: DaySummary): WearLinkOutcome<Unit> =
        exchange(WearLinkProtocol.Request.PutDay(day)) { line ->
            if (line.trim() == WearLinkDaily.DAY_OK) Reply.Done(Unit) else Reply.Unexpected
        }

    /** Tells the watch the wearer used the phone at [epochMillis]. */
    fun notifyPhoneActive(epochMillis: Long): WearLinkOutcome<Unit> =
        exchange(WearLinkProtocol.Request.PhoneActive(epochMillis)) { line ->
            if (WearLinkProtocol.parseEnd(line) != null) Reply.Done(Unit) else Reply.Unexpected
        }

    /**
     * One page of the series [key] after [cursor] ([WearLinkSince]): every line up to `END`, as
     * the watch stored it, for the caller to parse, and the cursor to ask with next. Refused as
     * a protocol error when `END` counts other than the lines that arrived, so a reply cut short
     * is never taken for a complete one, or carries no cursor. A line the caller cannot parse
     * still counts: the watch sent it, and a row the phone finds implausible must not stall the
     * series. Only call when the peer announced [key].
     */
    fun pullSince(key: String, cursor: String, limit: Int): WearLinkOutcome<WearLinkSince.Page> {
        val lines = ArrayList<String>()
        return exchange(WearLinkProtocol.Request.SeriesSince(key, cursor, limit)) { line ->
            if (WearLinkProtocol.parseEnd(line) != null) {
                val end = WearLinkSince.parseEnd(line) ?: return@exchange Reply.Mismatch("END carried no cursor")
                return@exchange if (end.count == lines.size) {
                    Reply.Done(WearLinkSince.Page(lines, end.more, end.cursor))
                } else {
                    Reply.Mismatch("END counted ${end.count} lines, ${lines.size} arrived")
                }
            }
            lines += line
            Reply.More
        }
    }

    /** A workout the watch records for an app on the phone: the state after [control]. Only for a watch announcing `wc`. */
    fun workoutControl(control: WearLinkWorkoutControl.Control): WearLinkOutcome<WearLinkWorkoutControl.State> =
        exchange(WearLinkProtocol.Request.WorkoutControl(control)) { line ->
            WearLinkWorkoutControl.parseState(line)?.let { Reply.Done(it) } ?: Reply.Unexpected
        }

    /** Closes the connection; the watch is waiting for exactly this. */
    fun close() = connection.close()

    private sealed class Reply<out T> {
        data object More : Reply<Nothing>()
        data class Done<T>(val value: T) : Reply<T>()
        data object Unexpected : Reply<Nothing>()

        /** The reply contradicts itself; the page is not used. */
        data class Mismatch(val detail: String) : Reply<Nothing>()
    }

    private fun <T> exchange(request: WearLinkProtocol.Request, onLine: (String) -> Reply<T>): WearLinkOutcome<T> =
        try {
            exchangeOrThrow(request, onLine)
        } catch (e: LineTooLongException) {
            WearLinkOutcome.ProtocolError(e.message ?: "line too long")
        } catch (_: IOException) {
            WearLinkOutcome.Closed
        }

    private fun <T> exchangeOrThrow(request: WearLinkProtocol.Request, onLine: (String) -> Reply<T>): WearLinkOutcome<T> {
        writer.line(WearLinkProtocol.formatRequest(request))
        writer.flush()
        while (true) {
            val line = reader.readLine() ?: return WearLinkOutcome.Closed
            val asReply = WearLinkProtocol.parseHelloReply(line)
            if (asReply is WearLinkProtocol.HelloReply.Error) return WearLinkOutcome.Refused(asReply.code)
            when (val reply = onLine(line)) {
                Reply.More -> continue
                is Reply.Done -> return WearLinkOutcome.Ok(reply.value)
                // An older or newer watch may say something extra; a line that is not ours is skipped.
                Reply.Unexpected -> continue
                is Reply.Mismatch -> return WearLinkOutcome.ProtocolError(reply.detail)
            }
        }
    }
}

/**
 * The phone's end: says hello over a fresh connection and, when the watch
 * answers `OK`, hands back a session for requests. Blocking; the caller
 * owns the socket's timeout (closing it unblocks every read here).
 *
 * The hello offers the highest version this side speaks ([versions]). A watch
 * that speaks an older one too refuses with `VERSION <min> <max>`, and the
 * phone says hello once more, on a new connection from [reconnect], with the
 * highest version both speak. With none in common the refusal stands: the
 * caller tells whose app is behind from the watch's range.
 */
object WearLinkClient {

    /** The versions this build speaks, oldest to newest. */
    val VERSIONS: IntRange = WearLinkProtocol.MIN_VERSION..WearLinkProtocol.VERSION

    /**
     * Says hello on [connection] with the highest of [versions], and, when the watch's
     * refusal leaves a lower one both speak, once more on [reconnect]'s connection with it.
     * [connection] is closed before that, since the watch waits for the close; a connection
     * this call opened is closed by it unless it hands back a session on it.
     */
    fun hello(
        connection: WearLinkConnection,
        hello: WearLinkProtocol.Hello,
        versions: IntRange = VERSIONS,
        reconnect: (() -> WearLinkConnection)? = null,
    ): WearLinkOutcome<WearLinkSession> {
        val offered = hello.copy(version = versions.last)
        val first = say(connection, offered)
        val lower = (first as? WearLinkOutcome.VersionMismatch)?.let { commonVersion(versions, it.min..it.max) }
        if (lower == null || lower >= offered.version || reconnect == null) return accepted(first, versions)
        connection.close()
        val again = reconnect()
        val second = accepted(say(again, offered.copy(version = lower)), versions)
        if (second !is WearLinkOutcome.Ok) again.close()
        return second
    }

    /** The highest version in both ranges, or null when they do not meet. */
    fun commonVersion(ours: IntRange, theirs: IntRange): Int? =
        minOf(ours.last, theirs.last).takeIf { it >= maxOf(ours.first, theirs.first) }

    /** A session on a version outside [versions] is refused here, as the watch would have. */
    private fun accepted(outcome: WearLinkOutcome<WearLinkSession>, versions: IntRange): WearLinkOutcome<WearLinkSession> {
        if (outcome !is WearLinkOutcome.Ok || outcome.value.peer.version in versions) return outcome
        val spoken = outcome.value.peer.version
        outcome.value.close()
        return WearLinkOutcome.VersionMismatch(spoken, spoken)
    }

    private fun say(connection: WearLinkConnection, hello: WearLinkProtocol.Hello): WearLinkOutcome<WearLinkSession> {
        val reader = LineReader(connection.input)
        val writer = LineWriter(connection.output)
        return try {
            writer.line(WearLinkProtocol.formatHello(hello))
            writer.flush()
            val line = reader.readLine() ?: return WearLinkOutcome.Closed
            when (val reply = WearLinkProtocol.parseHelloReply(line)) {
                null -> WearLinkOutcome.ProtocolError("unexpected reply to hello: ${line.take(40)}")
                is WearLinkProtocol.HelloReply.Ok -> WearLinkOutcome.Ok(WearLinkSession(connection, reply, reader, writer))
                WearLinkProtocol.HelloReply.Pending -> WearLinkOutcome.Pending
                is WearLinkProtocol.HelloReply.Unauthorized -> WearLinkOutcome.Unauthorized(reply.reason)
                is WearLinkProtocol.HelloReply.VersionMismatch -> WearLinkOutcome.VersionMismatch(reply.min, reply.max)
                is WearLinkProtocol.HelloReply.Error -> WearLinkOutcome.Refused(reply.code)
            }
        } catch (e: LineTooLongException) {
            WearLinkOutcome.ProtocolError(e.message ?: "line too long")
        } catch (_: IOException) {
            WearLinkOutcome.Closed
        }
    }
}
