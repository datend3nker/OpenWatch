package tech.mmarca.openwatch.devices.wearos

import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.devices.core.pairing.CompanionFilter
import tech.mmarca.openwatch.devices.core.pairing.WatchPairingPort
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleDiscoveredDevice
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration

/** The one platform step WearOS onboarding shows. No bond, no probe. */
enum class WearOsOnboardStep { ASSOCIATING }

/**
 * How a WearOS onboarding ended. The companion association is required: OpenWatch may
 * start a foreground service from the background only for an associated device, and a
 * workout another app starts on the watch needs that.
 */
sealed interface WearOsOnboardOutcome {
    data class Succeeded(val device: BleSensorDevice) : WearOsOnboardOutcome

    /** The user declined Android's association dialog, or it failed. Nothing was registered. */
    data object NotAssociated : WearOsOnboardOutcome
}

/**
 * Turns a scanned WearOS watch into a registered `(WATCH, WEAROS)` device.
 * Associate (required), then register with no capabilities, off the Garmin
 * sync path. Recorded and live data come over the RFCOMM link (`BluetoothWearOsNodePort`).
 */
@Singleton
class OnboardWearOsWatchUseCase @Inject constructor(
    private val pairing: WatchPairingPort,
    private val bleDeviceRepository: BleDeviceRepository,
) {

    /** [onStep] fires as the companion dialog is about to be shown. */
    suspend operator fun invoke(
        device: BleDiscoveredDevice,
        displayName: String,
        onStep: ((WearOsOnboardStep) -> Unit)? = null,
    ): WearOsOnboardOutcome {
        onStep?.invoke(WearOsOnboardStep.ASSOCIATING)
        val associated = try {
            // The address may be the Classic bond's, found in the bonded list.
            pairing.associateCompanion(device.address, displayName, CompanionFilter.BLE_OR_CLASSIC)
        } catch (_: Exception) {
            false
        }
        if (!associated) return WearOsOnboardOutcome.NotAssociated

        val registered = bleDeviceRepository.addDevice(
            displayName = displayName,
            address = device.address,
            bluetoothName = device.name,
            capabilities = emptySet(),
            kind = BleDeviceKind.WATCH,
            integration = DeviceIntegration.WEAROS,
        )
        return WearOsOnboardOutcome.Succeeded(registered)
    }

    /** Whether the registered watch at [address] still has its association. */
    fun isAssociated(address: String): Boolean = pairing.isCompanionAssociated(address)

    /**
     * Asks for the association again, for a watch registered before it was required, or one
     * whose association the user removed in Android's settings.
     */
    suspend fun associate(address: String, displayName: String): Boolean = try {
        pairing.associateCompanion(address, displayName, CompanionFilter.BLE_OR_CLASSIC)
    } catch (_: Exception) {
        false
    }

    /** Undoes [invoke] at the OS level: drops the association. */
    suspend fun forget(address: String) {
        pairing.disassociateCompanion(address)
    }
}
