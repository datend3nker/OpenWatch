package tech.mmarca.openwatch.wearlink

import java.io.InputStream
import java.io.OutputStream

/** One accepted or opened connection: the streams, who the peer is, and how to close it. */
class WearLinkConnection(
    val input: InputStream,
    val output: OutputStream,
    /** The peer's Bluetooth address, the key of its trust. */
    val peerAddress: String,
    private val closer: () -> Unit,
) {
    @Volatile
    var isClosed: Boolean = false
        private set

    fun close() {
        isClosed = true
        runCatching { closer() }
    }
}

/**
 * What the watch knows about phones. A token is stored only once the wearer
 * has allowed the phone; until then the phone is pending.
 */
interface WearLinkTrustStore {
    /** The token stored for [peerAddress], or null when the phone is unknown. */
    fun tokenFor(peerAddress: String): String?

    fun isBlocked(peerAddress: String): Boolean

    /**
     * A phone presented [token], which the watch does not hold for it: an
     * unknown phone, or a known one with another token. Remember it for the
     * wearer to allow or block; allowing replaces a stored token.
     */
    fun notePending(peerAddress: String, token: String, peerName: String)
}

/** One page of a pull. [more] means the limit cut it short. */
data class Page<T>(val items: List<T>, val more: Boolean)

/** What the watch serves once a phone is trusted. */
interface WearLinkRequestHandler {
    /** The watch's name, sent in the `OK` reply. */
    fun localName(): String

    fun capabilities(): Set<String>

    /** The watch's clock, sent in `OK` to a phone that announced [WearLinkProtocol.CAP_CLOCK]. */
    fun nowMillis(): Long = System.currentTimeMillis()

    /**
     * Up to [limit] stored lines of the series [key] after [cursor], in the order they were
     * written, and the cursor that follows them ([WearLinkSince]). Null when the watch does not
     * serve [key], which the phone sees as `ERROR bad_request`.
     */
    fun seriesSince(key: String, cursor: String, limit: Int): WearLinkSince.Page?

    /** The wearer used the phone at [epochMillis]. Only called when the watch advertised [WearLinkProtocol.CAP_PHONE_ACTIVE]. */
    fun phoneActive(epochMillis: Long)

    /**
     * The finished workouts that started after [sinceEpochMillis], oldest first, at most [limit], each with
     * its pauses and route. Only called when the watch advertised [WearLinkProtocol.CAP_WORKOUTS].
     */
    fun workoutsSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkWorkout>

    /** Every setting the watch holds, by key ([WearLinkPreferences]). Only called when the watch advertised [WearLinkProtocol.CAP_PREFERENCES]. */
    fun heldPreferences(): Map<String, String>

    /** Applies a setting the phone pushed. False for a key or value the watch does not take, which the phone sees as `ERROR bad_request`. */
    fun setPreference(key: String, value: String): Boolean

    /**
     * Keeps the phone's figures for one day ([WearLinkDaily]). False when the watch cannot keep
     * them, which the phone sees as `ERROR bad_request`. Only called when the watch advertised
     * [WearLinkProtocol.CAP_DAY_SUMMARY].
     */
    fun putDay(day: DaySummary): Boolean

    /**
     * Starts, pauses, resumes or stops the workout the phone has the watch record for an app, or tells its
     * state. Only called when the watch advertised [WearLinkWorkoutControl.CAPABILITY].
     */
    fun workoutControl(control: WearLinkWorkoutControl.Control): WearLinkWorkoutControl.State =
        WearLinkWorkoutControl.State(control.sessionId, WearLinkWorkoutControl.Phase.FAILED, 0L, WearLinkWorkoutControl.REASON_UNSUPPORTED)

    /**
     * Blocks for up to [seconds], handing each heart rate reading to [emit]
     * on the caller's thread as it arrives; returns how many. Returns 0 at
     * once when the watch is off the wrist or cannot measure.
     */
    fun liveHeartRate(seconds: Int, emit: (WearLinkProtocol.HeartRateSample) -> Unit): Int
}
