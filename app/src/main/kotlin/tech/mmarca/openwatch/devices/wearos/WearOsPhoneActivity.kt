package tech.mmarca.openwatch.devices.wearos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tech.mmarca.openwatch.data.repository.BleDeviceRepository

/**
 * Tells the paired watches when the wearer unlocks the phone, so a watch
 * ends a sleep hold it started by itself: someone unlocking a phone is awake.
 *
 * Only an unlock counts (`ACTION_USER_PRESENT`): a screen that lights up for
 * a notification is not use. The receiver lives as long as the app's process,
 * which the companion association keeps alive while a watch is in range; no
 * foreground service. One message per [MIN_GAP] at most, so reading the phone
 * for an hour costs one Bluetooth connection every few minutes.
 */
@Singleton
class WearOsPhoneActivity @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val deviceRepository: BleDeviceRepository,
    private val nodePort: WearOsNodePort,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastSentAt: Instant = Instant.EPOCH

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) onUnlocked(Instant.now())
        }
    }

    fun start() {
        // A system broadcast; a receiver registered at run time needs no export flag for it.
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT))
    }

    private fun onUnlocked(at: Instant) {
        if (at.isBefore(lastSentAt.plus(MIN_GAP))) return
        lastSentAt = at
        val watches = deviceRepository.devices.filter { it.isWearosWatch && it.enabled }
        if (watches.isEmpty()) return
        scope.launch {
            for (watch in watches) {
                try {
                    nodePort.notifyPhoneActive(watch.address, watch.bluetoothName, at)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: WearOsLinkFailure) {
                    // Out of range or asleep: the hold ends by itself after five waking minutes.
                    Log.d(TAG, "Phone activity not delivered", error)
                }
            }
        }
    }

    private companion object {
        const val TAG = "WearOsPhoneActivity"
        val MIN_GAP: java.time.Duration = java.time.Duration.ofMinutes(5)
    }
}
