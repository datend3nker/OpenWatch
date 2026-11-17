package tech.mmarca.openwatch.devices.core.sync

import kotlin.time.Duration
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.domain.model.BleSensorDevice

/** How far a sync has got. Device-agnostic. */
enum class DeviceSyncPhase { HANDSHAKE, LISTING, DOWNLOADING, COMPLETE, FAILED }

/** A progress tick: the [phase] and, while downloading, [filesDone] of [filesTotal]. */
data class DeviceSyncProgress(
    val phase: DeviceSyncPhase,
    val filesTotal: Int = 0,
    val filesDone: Int = 0,
)

/** A series a sync left out for want of a Health Connect grant, so the screen can say which. */
enum class UnwrittenSeries { STEPS, WALKING_ENERGY, RESTING_HEART_RATE, HEART_RATE_VARIABILITY, RESPIRATORY_RATE, OXYGEN_SATURATION, SKIN_TEMPERATURE, WORKOUTS, BODY_COMPOSITION }

/**
 * How a sync ended, as an app connected over OpenLink learns it. [INTERRUPTED]: the link dropped
 * after part was written; [DISCONNECTED]: the device was not reachable; [PERMISSION_MISSING]: a
 * grant (Bluetooth or a Health Connect write) was missing, also for a series left out.
 */
enum class DeviceSyncOutcome { SUCCEEDED, INTERRUPTED, PERMISSION_MISSING, DISCONNECTED, FAILED }

/** The outcome of a whole sync-and-persist run. */
sealed class DeviceSyncResult {

    /** How the run ended, for OpenLink's `lastSyncResult`. */
    abstract val outcome: DeviceSyncOutcome

    /**
     * The sync finished with [fileCount] files, or samples for an
     * integration that counts those, [nightCount] estimated nights of sleep
     * and [stepMinuteCount] minutes of steps written. 0 is a success.
     * [missingBodyWeight] says an estimate that needs the body weight was
     * skipped because none is known, and [weightReadWithheld] that Health
     * Connect's weight was out of reach for want of the read grant. [unwritten] are the series the run left
     * out for want of a Health Connect grant, and [withheldPermissions] the
     * grants (a write, or a read the write depends on) the screen asks for.
     */
    data class Succeeded(
        val fileCount: Int,
        val nightCount: Int = 0,
        val stepMinuteCount: Int = 0,
        val missingBodyWeight: Boolean = false,
        val weightReadWithheld: Boolean = false,
        val unwritten: Set<UnwrittenSeries> = emptySet(),
        val withheldPermissions: Set<String> = emptySet(),
    ) : DeviceSyncResult() {
        override val outcome: DeviceSyncOutcome
            get() = if (unwritten.isEmpty() && withheldPermissions.isEmpty()) DeviceSyncOutcome.SUCCEEDED else DeviceSyncOutcome.PERMISSION_MISSING
    }

    /**
     * The sync failed. [error] is what the screen shows; an integration with
     * a sentence already in hand passes it as a [ScreenError.Message], one
     * with a typed cause passes a [ScreenError.Text] or [ScreenError.PermissionDenied].
     */
    data class Failed(
        val error: ScreenError,
        /** What kind of failure, which only the port can tell; [DeviceSyncOutcome.FAILED] when it cannot. */
        override val outcome: DeviceSyncOutcome = DeviceSyncOutcome.FAILED,
    ) : DeviceSyncResult() {
        constructor(message: String, outcome: DeviceSyncOutcome = DeviceSyncOutcome.FAILED) : this(ScreenError.Message(message), outcome)

        /** The rendered sentence, for a message-shaped error; the resource id's name otherwise. */
        val message: String
            get() = (error as? ScreenError.Message)?.text ?: error.toString()
    }
}

/**
 * The seam between generic sync orchestration and one integration. Owns
 * the whole operation: pull, import, persist, stamp. A port: only DI knows
 * which integration satisfies it.
 */
interface DeviceSyncPort {

    /** Whether this integration owns the sync-and-persist for [device]. */
    fun canSync(device: BleSensorDevice): Boolean

    /**
     * Runs the whole sequence for [device]. Never throws. [listenAfter] is
     * a diagnostic window held open after the sync.
     */
    suspend fun sync(
        device: BleSensorDevice,
        listenAfter: Duration = Duration.ZERO,
        onProgress: ((DeviceSyncProgress) -> Unit)? = null,
    ): DeviceSyncResult
}
