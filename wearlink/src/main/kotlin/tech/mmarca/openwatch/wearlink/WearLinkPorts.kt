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

    fun heartRateSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.HeartRateSample>

    fun sleepMinutesSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.SleepMinute>

    fun stepsSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.StepMinute>

    fun ascentSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.AscentMinute>

    /** The night-time HRV windows newer than [sinceEpochMillis], oldest first. Only called when the watch advertised [WearLinkProtocol.CAP_HRV]. */
    fun hrvSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.HrvWindow>

    /** The wearer used the phone at [epochMillis]. Only called when the watch advertised [WearLinkProtocol.CAP_PHONE_ACTIVE]. */
    fun phoneActive(epochMillis: Long)

    /**
     * The finished workouts that started after [sinceEpochMillis], oldest first, at most [limit], each with
     * its pauses and route. Only called when the watch advertised [WearLinkProtocol.CAP_WORKOUTS].
     */
    fun workoutsSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkWorkout>

    /** Every setting the watch holds, by key ([WearLinkProtocol.Preferences]). Only called when the watch advertised [WearLinkProtocol.CAP_PREFERENCES]. */
    fun heldPreferences(): Map<String, String>

    /** Applies a setting the phone pushed. False for a key or value the watch does not take, which the phone sees as `ERROR bad_request`. */
    fun setPreference(key: String, value: String): Boolean

    /** The night windows' breathing rates newer than [sinceEpochMillis]. Only called when the watch advertised [WearLinkProtocol.CAP_RESPIRATORY_RATE]. */
    fun respiratorySince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.RespiratoryWindow>

    /** The skin temperature minutes newer than [sinceEpochMillis]. Only called when the watch advertised [WearLinkProtocol.CAP_SKIN_TEMPERATURE]. */
    /** The SpO2 spot measurements newer than [sinceEpochMillis]. Only called when the watch advertised [WearLinkProtocol.CAP_SPO2]. */
    fun spo2Since(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.Spo2Measurement>

    fun skinSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.SkinMinute>

    /**
     * Blocks for up to [seconds], handing each heart rate reading to [emit]
     * on the caller's thread as it arrives; returns how many. Returns 0 at
     * once when the watch is off the wrist or cannot measure.
     */
    fun liveHeartRate(seconds: Int, emit: (WearLinkProtocol.HeartRateSample) -> Unit): Int
}
