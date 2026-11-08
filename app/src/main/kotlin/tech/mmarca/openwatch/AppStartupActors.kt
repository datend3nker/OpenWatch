package tech.mmarca.openwatch

import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openwatch.devices.wearos.WearOsPhoneActivity
import tech.mmarca.openwatch.features.watches.WatchAutoSyncScheduler

/**
 * What the app starts at every process start that the first screen does not wait for.
 *
 * The Application asks for this through `dagger.Lazy` and builds it on a background
 * thread, so the device graph is not pulled into `super.onCreate()` on the main thread.
 */
@Singleton
class AppStartupActors @Inject constructor(
    private val watchAutoSyncScheduler: WatchAutoSyncScheduler,
    private val phoneActivity: WearOsPhoneActivity,
) {
    fun start() {
        // Re-plans the sync schedules after what WorkManager does not cover.
        watchAutoSyncScheduler.restoreAll()
        // Tells the watches when the phone is unlocked, so they end a sleep hold they started.
        phoneActivity.start()
    }
}
