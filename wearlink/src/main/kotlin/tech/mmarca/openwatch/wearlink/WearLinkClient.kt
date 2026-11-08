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

    fun pullHeartRate(sinceEpochMillis: Long, limit: Int): WearLinkOutcome<Page<WearLinkProtocol.HeartRateSample>> {
        val items = ArrayList<WearLinkProtocol.HeartRateSample>()
        return exchange(WearLinkProtocol.Request.HeartRateSince(sinceEpochMillis, limit)) { line ->
            WearLinkProtocol.parseSample(line)?.let { items += it; return@exchange Reply.More }
            WearLinkProtocol.parseEnd(line)?.let { return@exchange Reply.Done(Page(items, it.more)) }
            Reply.Unexpected
        }
    }

    fun pullSleepMinutes(sinceEpochMillis: Long, limit: Int): WearLinkOutcome<Page<WearLinkProtocol.SleepMinute>> {
        val items = ArrayList<WearLinkProtocol.SleepMinute>()
        return exchange(WearLinkProtocol.Request.SleepMinutesSince(sinceEpochMillis, limit)) { line ->
            WearLinkProtocol.parseSleepMinute(line)?.let { items += it; return@exchange Reply.More }
            WearLinkProtocol.parseEnd(line)?.let { return@exchange Reply.Done(Page(items, it.more)) }
            Reply.Unexpected
        }
    }

    fun pullSteps(sinceEpochMillis: Long, limit: Int): WearLinkOutcome<Page<WearLinkProtocol.StepMinute>> {
        val items = ArrayList<WearLinkProtocol.StepMinute>()
        return exchange(WearLinkProtocol.Request.StepsSince(sinceEpochMillis, limit)) { line ->
            WearLinkProtocol.parseStepMinute(line)?.let { items += it; return@exchange Reply.More }
            WearLinkProtocol.parseEnd(line)?.let { return@exchange Reply.Done(Page(items, it.more)) }
            Reply.Unexpected
        }
    }

    fun pullAscent(sinceEpochMillis: Long, limit: Int): WearLinkOutcome<Page<WearLinkProtocol.AscentMinute>> {
        val items = ArrayList<WearLinkProtocol.AscentMinute>()
        return exchange(WearLinkProtocol.Request.AscentSince(sinceEpochMillis, limit)) { line ->
            WearLinkProtocol.parseAscentMinute(line)?.let { items += it; return@exchange Reply.More }
            WearLinkProtocol.parseEnd(line)?.let { return@exchange Reply.Done(Page(items, it.more)) }
            Reply.Unexpected
        }
    }

    /** One live window of up to [seconds]: hands each reading to [onSample] as it arrives; the count when it ends. */
    fun liveHeartRate(seconds: Int, onSample: (WearLinkProtocol.HeartRateSample) -> Unit): WearLinkOutcome<Int> =
        exchange(WearLinkProtocol.Request.LiveHeartRate(seconds)) { line ->
            WearLinkProtocol.parseSample(line)?.let { onSample(it); return@exchange Reply.More }
            WearLinkProtocol.parseEnd(line)?.let { return@exchange Reply.Done(it.count) }
            Reply.Unexpected
        }

    fun pullRespiratory(sinceEpochMillis: Long, limit: Int): WearLinkOutcome<Page<WearLinkProtocol.RespiratoryWindow>> {
        val items = ArrayList<WearLinkProtocol.RespiratoryWindow>()
        return exchange(WearLinkProtocol.Request.RespiratorySince(sinceEpochMillis, limit)) { line ->
            WearLinkProtocol.parseRespiratoryWindow(line)?.let { items += it; return@exchange Reply.More }
            WearLinkProtocol.parseEnd(line)?.let { return@exchange Reply.Done(Page(items, it.more)) }
            Reply.Unexpected
        }
    }

    fun pullSpo2(sinceEpochMillis: Long, limit: Int): WearLinkOutcome<Page<WearLinkProtocol.Spo2Measurement>> {
        val items = ArrayList<WearLinkProtocol.Spo2Measurement>()
        return exchange(WearLinkProtocol.Request.Spo2Since(sinceEpochMillis, limit)) { line ->
            WearLinkProtocol.parseSpo2(line)?.let { items += it; return@exchange Reply.More }
            WearLinkProtocol.parseEnd(line)?.let { return@exchange Reply.Done(Page(items, it.more)) }
            Reply.Unexpected
        }
    }

    fun pullSkin(sinceEpochMillis: Long, limit: Int): WearLinkOutcome<Page<WearLinkProtocol.SkinMinute>> {
        val items = ArrayList<WearLinkProtocol.SkinMinute>()
        return exchange(WearLinkProtocol.Request.SkinSince(sinceEpochMillis, limit)) { line ->
            WearLinkProtocol.parseSkinMinute(line)?.let { items += it; return@exchange Reply.More }
            WearLinkProtocol.parseEnd(line)?.let { return@exchange Reply.Done(Page(items, it.more)) }
            Reply.Unexpected
        }
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

    /** Tells the watch the wearer used the phone at [epochMillis]. */
    fun notifyPhoneActive(epochMillis: Long): WearLinkOutcome<Unit> =
        exchange(WearLinkProtocol.Request.PhoneActive(epochMillis)) { line ->
            if (WearLinkProtocol.parseEnd(line) != null) Reply.Done(Unit) else Reply.Unexpected
        }

    fun pullHrv(sinceEpochMillis: Long, limit: Int): WearLinkOutcome<Page<WearLinkProtocol.HrvWindow>> {
        val items = ArrayList<WearLinkProtocol.HrvWindow>()
        return exchange(WearLinkProtocol.Request.HrvSince(sinceEpochMillis, limit)) { line ->
            WearLinkProtocol.parseHrvWindow(line)?.let { items += it; return@exchange Reply.More }
            WearLinkProtocol.parseEnd(line)?.let { return@exchange Reply.Done(Page(items, it.more)) }
            Reply.Unexpected
        }
    }

    /** Closes the connection; the watch is waiting for exactly this. */
    fun close() = connection.close()

    private sealed class Reply<out T> {
        data object More : Reply<Nothing>()
        data class Done<T>(val value: T) : Reply<T>()
        data object Unexpected : Reply<Nothing>()
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
            }
        }
    }
}

/**
 * The phone's end: says hello over a fresh connection and, when the watch
 * answers `OK`, hands back a session for requests. Blocking; the caller
 * owns the socket's timeout (closing it unblocks every read here).
 */
object WearLinkClient {

    fun hello(connection: WearLinkConnection, hello: WearLinkProtocol.Hello): WearLinkOutcome<WearLinkSession> {
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
