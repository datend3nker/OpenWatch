package tech.mmarca.openwatch.wearlink

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The watch's end of one connection, from the hello to the close, run on
 * the caller's thread (the watch keeps one thread per accepted client).
 *
 * The phone speaks first with a hello; the reply decides everything: only
 * `OK` opens the request loop. The loop ends when the phone closes, so a
 * big page is never cut short by the watch closing first (a Bluetooth
 * socket closed by its writer drops whatever the reader has not taken),
 * and several requests can share one connection. Watchdogs close the
 * socket when the phone never says hello, goes quiet mid-connection, or
 * stays too long; a cap on concurrent clients answers `ERROR busy`.
 */
class WearLinkServer(
    private val trust: WearLinkTrustStore,
    private val handler: WearLinkRequestHandler,
    private val options: Options = Options(),
) {

    data class Options(
        /** The phone must say hello within this. */
        val helloTimeoutMillis: Long = 5_000L,
        /** Between requests on an open connection. */
        val idleTimeoutMillis: Long = 15_000L,
        /** One connection may not live longer than this. */
        val maxLifetimeMillis: Long = 600_000L,
        /** Beyond this many connections at once, the next gets `ERROR busy`. */
        val maxClients: Int = 2,
        /** After a refusal, how long to wait for the phone to close before closing. */
        val refusalCloseWaitMillis: Long = 2_000L,
        /** The versions the watch speaks; a test gives another range. */
        val versions: IntRange = WearLinkProtocol.MIN_VERSION..WearLinkProtocol.VERSION,
    )

    /** How a connection ended; [IDLE] is a served phone that went quiet past the idle timeout. */
    enum class Outcome { SERVED, IDLE, PENDING, UNAUTHORIZED, VERSION_MISMATCH, BAD_HELLO, BUSY, NO_HELLO, PROTOCOL_ERROR, INTERNAL_ERROR }

    private val active = AtomicInteger(0)

    /** Connections being served right now. */
    val activeClients: Int
        get() = active.get()

    fun serve(connection: WearLinkConnection): Outcome {
        if (active.incrementAndGet() > options.maxClients) {
            try {
                refuse(connection, WearLinkProtocol.formatError(WearLinkProtocol.ErrorCode.BUSY))
            } finally {
                active.decrementAndGet()
            }
            return Outcome.BUSY
        }
        val lifetime = WearLinkWatchdog(options.maxLifetimeMillis) { connection.close() }
        try {
            return serveInner(connection)
        } catch (_: LineTooLongException) {
            return Outcome.PROTOCOL_ERROR
        } catch (_: IOException) {
            return Outcome.PROTOCOL_ERROR
        } catch (_: RuntimeException) {
            runCatching { LineWriter(connection.output).apply { line(WearLinkProtocol.formatError(WearLinkProtocol.ErrorCode.INTERNAL)); flush() } }
            return Outcome.INTERNAL_ERROR
        } finally {
            lifetime.cancel()
            connection.close()
            active.decrementAndGet()
        }
    }

    private fun serveInner(connection: WearLinkConnection): Outcome {
        val reader = LineReader(connection.input)
        val writer = LineWriter(connection.output)
        val watchdog = WearLinkWatchdog(options.helloTimeoutMillis) { connection.close() }
        try {
            val line = reader.readLine() ?: return Outcome.NO_HELLO
            val hello = WearLinkProtocol.parseHello(line)
            if (hello == null) {
                watchdog.cancel()
                refuse(connection, WearLinkProtocol.formatError(WearLinkProtocol.ErrorCode.BAD_HELLO))
                return Outcome.BAD_HELLO
            }
            val reply = decide(connection.peerAddress, hello)
            if (reply !is WearLinkProtocol.HelloReply.Ok) {
                watchdog.cancel()
                refuse(connection, WearLinkProtocol.formatHelloReply(reply))
                return when (reply) {
                    WearLinkProtocol.HelloReply.Pending -> Outcome.PENDING
                    is WearLinkProtocol.HelloReply.Unauthorized -> Outcome.UNAUTHORIZED
                    is WearLinkProtocol.HelloReply.VersionMismatch -> Outcome.VERSION_MISMATCH
                    else -> Outcome.INTERNAL_ERROR
                }
            }
            writer.line(WearLinkProtocol.formatHelloReply(reply))
            writer.flush()
            watchdog.reset(options.idleTimeoutMillis)
            while (true) {
                val request = readRequest(reader) ?: return if (watchdog.hasFired) Outcome.IDLE else Outcome.SERVED
                watchdog.reset(options.idleTimeoutMillis)
                answer(writer, request)
                writer.flush()
            }
        } finally {
            watchdog.cancel()
        }
    }

    /**
     * The next request, or null once the connection is over. Android's
     * Bluetooth socket reports the phone's close as an exception rather than
     * an end of stream, so after the hello both mean the same: served.
     */
    private fun readRequest(reader: LineReader): String? =
        try {
            reader.readLine()
        } catch (e: LineTooLongException) {
            throw e
        } catch (_: IOException) {
            null
        }

    private fun decide(peerAddress: String, hello: WearLinkProtocol.Hello): WearLinkProtocol.HelloReply {
        // The phone offers its highest version; one this watch speaks is the highest both do.
        if (hello.version !in options.versions) {
            return WearLinkProtocol.HelloReply.VersionMismatch(options.versions.first, options.versions.last)
        }
        if (trust.isBlocked(peerAddress)) {
            return WearLinkProtocol.HelloReply.Unauthorized(WearLinkProtocol.UnauthorizedReason.BLOCKED)
        }
        val stored = trust.tokenFor(peerAddress)
        // Another token from a known phone is most often its app reinstalled or reset: ask the
        // wearer, as for a new phone. The stored token keeps working until the wearer allows.
        if (stored == null || !WearLinkToken.matches(stored, hello.token)) {
            trust.notePending(peerAddress, hello.token, hello.name)
            return WearLinkProtocol.HelloReply.Pending
        }
        // Only a phone that asked for the clock reads a field before the name.
        val clock = if (WearLinkProtocol.CAP_CLOCK in hello.capabilities) handler.nowMillis() else null
        return WearLinkProtocol.HelloReply.Ok(hello.version, handler.capabilities(), handler.localName(), clock)
    }

    private fun answer(writer: LineWriter, line: String) {
        when (val request = WearLinkProtocol.parseRequest(line)) {
            null -> writer.line(WearLinkProtocol.formatError(WearLinkProtocol.ErrorCode.BAD_REQUEST))
            WearLinkProtocol.Request.Ping -> writer.line(WearLinkProtocol.PONG)
            is WearLinkProtocol.Request.SeriesSince -> answerSeries(writer, request)
            WearLinkProtocol.Request.GetPreferences -> {
                val held = handler.heldPreferences()
                for ((key, value) in held) writer.line(WearLinkProtocol.formatPreference(key, value))
                writer.line(WearLinkProtocol.formatEnd(held.size, false))
            }
            is WearLinkProtocol.Request.SetPreference -> {
                val applied = handler.setPreference(request.key, request.value)
                writer.line(if (applied) WearLinkProtocol.PREFERENCE_OK else WearLinkProtocol.formatError(WearLinkProtocol.ErrorCode.BAD_REQUEST))
            }
            is WearLinkProtocol.Request.WorkoutsSince -> {
                val page = handler.workoutsSince(request.sinceEpochMillis, request.limit)
                for (workout in page.items) for (line in WearLinkWorkoutLines.lines(workout)) writer.line(line)
                writer.line(WearLinkProtocol.formatEnd(page.items.size, page.more))
            }
            is WearLinkProtocol.Request.PutDay -> {
                val kept = handler.putDay(request.day)
                writer.line(if (kept) WearLinkDaily.DAY_OK else WearLinkProtocol.formatError(WearLinkProtocol.ErrorCode.BAD_REQUEST))
            }
            is WearLinkProtocol.Request.PhoneActive -> {
                handler.phoneActive(request.epochMillis)
                writer.line(WearLinkProtocol.formatEnd(0, false))
            }
            is WearLinkProtocol.Request.WorkoutControl ->
                writer.line(WearLinkWorkoutControl.formatState(handler.workoutControl(request.control)))
            is WearLinkProtocol.Request.LiveHeartRate -> {
                // Each reading goes out at once; the phone shows it as it comes.
                val count = handler.liveHeartRate(request.seconds) { sample ->
                    writer.line(WearLinkProtocol.formatSample(sample.epochMillis, sample.bpm))
                    writer.flush()
                }
                writer.line(WearLinkProtocol.formatEnd(count, false))
            }
        }
    }

    /** The series' stored lines as they are; one that would not travel as one line is left out, the cursor past it. */
    private fun answerSeries(writer: LineWriter, request: WearLinkProtocol.Request.SeriesSince) {
        val page = handler.seriesSince(request.key, request.cursor, request.limit)
        if (page == null) {
            writer.line(WearLinkProtocol.formatError(WearLinkProtocol.ErrorCode.BAD_REQUEST))
            return
        }
        val lines = page.lines.filter(WearLinkSince::isSendable)
        for (line in lines) writer.line(line)
        writer.line(WearLinkSince.formatEnd(lines.size, page.more, page.cursor))
    }

    /** Sends one refusal line, then waits briefly for the phone to close so the line is not dropped with the socket. */
    private fun refuse(connection: WearLinkConnection, line: String) {
        val writer = LineWriter(connection.output)
        writer.line(line)
        writer.flush()
        val watchdog = WearLinkWatchdog(options.refusalCloseWaitMillis) { connection.close() }
        try {
            while (connection.input.read() >= 0) Unit
        } catch (_: IOException) {
            // The watchdog or the phone closed it.
        } finally {
            watchdog.cancel()
        }
    }
}
