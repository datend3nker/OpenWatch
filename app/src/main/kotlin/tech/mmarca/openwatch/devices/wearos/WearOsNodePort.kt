package tech.mmarca.openwatch.devices.wearos

import java.time.Instant
import tech.mmarca.openwatch.domain.model.WearSleepMinute

/**
 * What the phone can tell about the OpenWatch Wear OS app on a watch.
 *
 * A bond says nothing about reach: a paired watch can be off or out of range.
 * Only an answer to the ping proves the link, so a paired watch that stays
 * silent is [NO_ANSWER], never "connected".
 */
enum class WearOsAppStatus {
    /** The phone's Bluetooth is off. */
    BLUETOOTH_OFF,

    /** No bonded Wear OS watch matches the registered one. */
    NOT_PAIRED,

    /** Paired, but no answer: the watch is off or out of range, or the app on it is not installed or not running. */
    NO_ANSWER,

    /** The watch is asking the wearer whether to allow this phone. */
    PENDING_CONFIRMATION,

    /** The watch holds another token for this phone. */
    UNAUTHORIZED_MISMATCH,

    /** The wearer blocked this phone on the watch. */
    UNAUTHORIZED_BLOCKED,

    /** The watch app speaks an older protocol than this phone. */
    UPDATE_WATCH,

    /** The watch app speaks a newer protocol than this phone. */
    UPDATE_PHONE,

    /** The watch answered something the protocol does not allow. */
    PROTOCOL_ERROR,

    /** The OpenWatch Wear OS app answered the hello. */
    APP_RUNNING,
}

/**
 * Pairing and app responsiveness of a Wear OS watch.
 */
data class WearOsCompanionStatus(
    val isPaired: Boolean = false,
    val connectedNodeName: String? = null,
    val connectedNodeAddress: String? = null,
    val appStatus: WearOsAppStatus = WearOsAppStatus.NOT_PAIRED,
    val lastCheckedAt: Instant? = null,
    /** The name the watch gave in its hello, once it has. */
    val watchName: String? = null,
    /** The bond this watch once had with the phone is gone. */
    val bondLost: Boolean = false,
) {
    val isAppRunning: Boolean
        get() = appStatus == WearOsAppStatus.APP_RUNNING
}

/** One heart rate sample the watch recorded. */
data class WearOsHeartRateSample(val time: Instant, val beatsPerMinute: Int)

/** One page of a heart rate pull. [hasMore] means the watch cut the reply at its limit. */
data class WearOsHeartRatePage(
    val samples: List<WearOsHeartRateSample>,
    val hasMore: Boolean,
)

/** One page of a sleep minute pull. [hasMore] means the watch cut the reply at its limit. */
data class WearOsSleepMinutePage(
    val minutes: List<WearSleepMinute>,
    val hasMore: Boolean,
)

/** The steps the watch counted in the clock minute starting at [start]. */
data class WearOsStepMinute(val start: Instant, val steps: Int)

/** One page of a step pull. [hasMore] means the watch cut the reply at its limit. */
data class WearOsStepPage(
    val minutes: List<WearOsStepMinute>,
    val hasMore: Boolean,
)

/** The height the watch's barometer climbed in the clock minute starting at [start], elevators included. */
data class WearOsAscentMinute(val start: Instant, val metres: Double)

/** One page of an ascent pull. [hasMore] means the watch cut the reply at its limit. */
data class WearOsAscentPage(
    val minutes: List<WearOsAscentMinute>,
    val hasMore: Boolean,
)

/** One night-time HRV window the watch measured: its start, the RMSSD and the clean beats it rests on. */
data class WearOsHrvWindow(val start: Instant, val rmssdMillis: Double, val beats: Int)

/** One GPS fix of a watch workout. Altitude and accuracy in metres, null when the fix had none. */
data class WearOsWorkoutPoint(
    val time: Instant,
    val latitude: Double,
    val longitude: Double,
    val altitudeMetres: Double?,
    val accuracyMetres: Double?,
)

/**
 * One finished workout the watch recorded. [exerciseType] is the Health Connect type; the totals are
 * null where the watch measured none.
 */
data class WearOsWorkout(
    val start: Instant,
    val end: Instant,
    val exerciseType: Int,
    val steps: Int?,
    val distanceMetres: Int?,
    val ascentMetres: Double?,
    val pauses: List<ClosedRange<Instant>>,
    val route: List<WearOsWorkoutPoint>,
)

/** One page of a workout pull. [hasMore] means the watch cut the reply at its limit. */
data class WearOsWorkoutPage(
    val workouts: List<WearOsWorkout>,
    val hasMore: Boolean,
)

/** One minute of skin temperature the watch recorded with the Samsung platform. */
data class WearOsSkinMinute(val time: Instant, val celsius: Double)

/** One page of a skin temperature pull. [hasMore] means the watch cut the reply at its limit. */
data class WearOsSkinPage(
    val minutes: List<WearOsSkinMinute>,
    val hasMore: Boolean,
)

/** One SpO2 spot measurement the watch took with the Samsung platform. */
data class WearOsSpo2Measurement(val time: Instant, val percent: Int)

/** One page of an SpO2 pull. [hasMore] means the watch cut the reply at its limit. */
data class WearOsSpo2Page(
    val measurements: List<WearOsSpo2Measurement>,
    val hasMore: Boolean,
)

/** The breathing rate of one night-time window, and how many routes agreed on it. */
data class WearOsRespiratoryWindow(val start: Instant, val breathsPerMinute: Double, val routes: Int)

/** One page of a breathing rate pull. [hasMore] means the watch cut the reply at its limit. */
data class WearOsRespiratoryPage(
    val windows: List<WearOsRespiratoryWindow>,
    val hasMore: Boolean,
)

/** One page of an HRV pull. [hasMore] means the watch cut the reply at its limit. */
data class WearOsHrvPage(
    val windows: List<WearOsHrvWindow>,
    val hasMore: Boolean,
)

/**
 * Port for talking to the OpenWatch app on a Wear OS watch.
 *
 * A missing Bluetooth permission surfaces as a [SecurityException], so the
 * screen can turn it into a grant affordance.
 */
interface WearOsNodePort {
    /**
     * [targetAddress] is the registered address, [targetName] the advertised
     * name. Both are hints: the address stored at onboarding is the BLE scan
     * address, which on most watches differs from the bonded Classic one.
     */
    suspend fun checkStatus(
        targetAddress: String? = null,
        targetName: String? = null,
    ): WearOsCompanionStatus

    /**
     * Heart rate samples newer than [since], oldest first, up to the watch's
     * page limit. Null when no bonded watch matches. Throws a
     * [WearOsLinkFailure] for everything else the watch could not give.
     */
    suspend fun pullHeartRate(
        targetAddress: String?,
        targetName: String?,
        since: Instant,
    ): WearOsHeartRatePage?

    /**
     * Sleep minutes newer than [since], oldest first, up to the watch's page
     * limit. Null when no bonded watch matches. Throws a [WearOsLinkFailure]
     * for everything else the watch could not give.
     */
    suspend fun pullSleepMinutes(
        targetAddress: String?,
        targetName: String?,
        since: Instant,
    ): WearOsSleepMinutePage?

    /**
     * Step minutes newer than [since], oldest first, up to the watch's page
     * limit. Empty page: the watch app is too old to count steps. Null: no
     * bonded watch matches. Any other failure throws [WearOsLinkFailure].
     */
    suspend fun pullSteps(
        targetAddress: String?,
        targetName: String?,
        since: Instant,
    ): WearOsStepPage?

    /**
     * Ascent minutes newer than [since], oldest first, up to the watch's page
     * limit. Empty page: the watch app is too old to send ascent, or the
     * watch has no barometer. Null: no bonded watch matches. Any other
     * failure throws [WearOsLinkFailure].
     */
    suspend fun pullAscent(
        targetAddress: String?,
        targetName: String?,
        since: Instant,
    ): WearOsAscentPage?

    /**
     * Night-time HRV windows newer than [since], oldest first, up to the
     * watch's page limit. Empty page: the watch app is too old or has no raw
     * PPG sensor. Null: no bonded watch matches. Any other failure throws
     * [WearOsLinkFailure].
     */
    suspend fun pullHrv(
        targetAddress: String?,
        targetName: String?,
        since: Instant,
    ): WearOsHrvPage?

    /**
     * Holds one connection and hands each heart rate reading to [onSample],
     * on an I/O thread, as the watch takes it. Returns true once the watch
     * reports none (it is off the wrist) or the connection nears the watch's
     * age limit; the caller connects again later. False when no bonded watch
     * matches or the watch app cannot stream. Any other failure throws
     * [WearOsLinkFailure].
     */
    suspend fun liveHeartRate(
        targetAddress: String?,
        targetName: String?,
        onSample: (WearOsHeartRateSample) -> Unit,
    ): Boolean

    /**
     * Breathing rates of the night-time windows newer than [since], oldest
     * first. Empty page: the watch app is too old or measures none. Null: no
     * bonded watch matches. Any other failure throws [WearOsLinkFailure].
     */
    suspend fun pullRespiratory(
        targetAddress: String?,
        targetName: String?,
        since: Instant,
    ): WearOsRespiratoryPage?

    /**
     * SpO2 spot measurements newer than [since], oldest first. Empty page:
     * the watch app is too old or has no Samsung platform. Null: no bonded
     * watch matches. Any other failure throws [WearOsLinkFailure].
     */
    suspend fun pullSpo2(
        targetAddress: String?,
        targetName: String?,
        since: Instant,
    ): WearOsSpo2Page?

    /**
     * Skin temperature minutes newer than [since], oldest first. Empty page:
     * the watch app is too old or has no Samsung platform. Null: no bonded
     * watch matches. Any other failure throws [WearOsLinkFailure].
     */
    suspend fun pullSkin(
        targetAddress: String?,
        targetName: String?,
        since: Instant,
    ): WearOsSkinPage?

    /**
     * The watch's own settings (`WearLinkProtocol.Preferences`). Null when
     * no bonded watch matches or the watch app is too old to serve them. Any
     * other failure throws [WearOsLinkFailure].
     */
    suspend fun pullWatchSettings(targetAddress: String?, targetName: String?): Map<String, String>?

    /** Changes one setting on the watch. False when no bonded watch matches or the watch refused it. */
    suspend fun setWatchSetting(targetAddress: String?, targetName: String?, key: String, value: String): Boolean

    /**
     * Workouts that started after [since], oldest first, at most a few per page. Empty page: the watch
     * app is too old to record workouts. Null: no bonded watch matches. Any other failure throws
     * [WearOsLinkFailure].
     */
    suspend fun pullWorkouts(
        targetAddress: String?,
        targetName: String?,
        since: Instant,
    ): WearOsWorkoutPage?

    /**
     * Tells the watch the wearer used the phone at [at], so it ends a sleep
     * hold it started by itself. False when no bonded watch matches or the
     * watch app does not take it. Any other failure throws [WearOsLinkFailure].
     */
    suspend fun notifyPhoneActive(
        targetAddress: String?,
        targetName: String?,
        at: Instant,
    ): Boolean
}
