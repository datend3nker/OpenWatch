package tech.mmarca.openwatch.devices.core

import tech.mmarca.openwatch.domain.model.BleSensorDevice

/** What an app connected over OpenLink asks of a workout a device records for it (OpenLink's `RECORD`). */
enum class RecordedWorkoutCommand { START, PAUSE, RESUME, STOP, STATE }

/** Where a recorded workout stands on the device; [NONE]: the device holds none for the session. */
enum class RecordedWorkoutPhase { RECORDING, PAUSED, STOPPED, FAILED, NONE }

/** A recorded workout's phase, since when by the device's clock, and why it stopped or failed (null for no reason). */
data class RecordedWorkoutState(val phase: RecordedWorkoutPhase, val atMillis: Long, val reason: String? = null)

/**
 * A device family whose devices record a workout themselves for an app on the phone, steered from
 * the phone; the link host takes every one bound into a set and hands each device to the one that
 * claims it, so it names no family. Its reasons are OpenLink's words (`busy`, `unsupported`,
 * `stopped_on_device`, `stopped_by_client`).
 */
interface RecordedWorkoutPort {

    fun canRecord(device: BleSensorDevice): Boolean

    /** The sports a device it claims can record, as Health Connect's exercise types. */
    val exerciseTypes: Set<Int>

    /**
     * Sends [command] for [sessionId] ([exerciseType], Health Connect's, for a start) and returns
     * the state after it; null when the device could not be reached. A [background] ask (a poll of
     * the state) lets every other request on the device's link go first.
     */
    suspend fun control(
        device: BleSensorDevice,
        command: RecordedWorkoutCommand,
        sessionId: String,
        exerciseType: Int = 0,
        background: Boolean = false,
    ): RecordedWorkoutState?
}
