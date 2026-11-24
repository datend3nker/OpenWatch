package tech.mmarca.openwatch.devices.wearos

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import tech.mmarca.openwatch.devices.core.RecordedWorkoutCommand
import tech.mmarca.openwatch.devices.core.RecordedWorkoutPhase
import tech.mmarca.openwatch.devices.core.RecordedWorkoutPort
import tech.mmarca.openwatch.devices.core.RecordedWorkoutState
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl.Command
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl.Phase
import tech.mmarca.openwatch.wearlink.WorkoutSport

/**
 * A Wear OS watch records a workout for an app on the phone: the watch app's own workout,
 * steered over `WO_CTL` ([WearLinkWorkoutControl]). A link that fails is no answer (null); a
 * watch app too old for it answers `failed unsupported`.
 */
@Singleton
class WearOsRecordedWorkouts @Inject constructor(private val companionManager: WearOsCompanionManager) : RecordedWorkoutPort {

    override fun canRecord(device: BleSensorDevice): Boolean = device.integration == DeviceIntegration.WEAROS

    override val exerciseTypes: Set<Int> = WorkoutSport.entries.mapTo(mutableSetOf()) { it.exerciseType }

    override suspend fun control(
        device: BleSensorDevice,
        command: RecordedWorkoutCommand,
        sessionId: String,
        exerciseType: Int,
        background: Boolean,
    ): RecordedWorkoutState? {
        val control = WearLinkWorkoutControl.Control(commandOf(command), sessionId, exerciseType)
        val state = try {
            companionManager.controlWorkout(device.address, device.bluetoothName, control, background)
        } catch (error: CancellationException) {
            throw error
        } catch (_: WearOsLinkFailure) {
            null
        } catch (_: SecurityException) {
            // The nearby-devices grant is gone: no link, so no answer.
            null
        } ?: return null
        return RecordedWorkoutState(phaseOf(state.phase), state.atMillis, state.reason)
    }

    private fun commandOf(command: RecordedWorkoutCommand): Command = when (command) {
        RecordedWorkoutCommand.START -> Command.START
        RecordedWorkoutCommand.PAUSE -> Command.PAUSE
        RecordedWorkoutCommand.RESUME -> Command.RESUME
        RecordedWorkoutCommand.STOP -> Command.STOP
        RecordedWorkoutCommand.STATE -> Command.STATE
    }

    private fun phaseOf(phase: Phase): RecordedWorkoutPhase = when (phase) {
        Phase.RECORDING -> RecordedWorkoutPhase.RECORDING
        Phase.PAUSED -> RecordedWorkoutPhase.PAUSED
        Phase.STOPPED -> RecordedWorkoutPhase.STOPPED
        Phase.FAILED -> RecordedWorkoutPhase.FAILED
        Phase.NONE -> RecordedWorkoutPhase.NONE
    }
}
