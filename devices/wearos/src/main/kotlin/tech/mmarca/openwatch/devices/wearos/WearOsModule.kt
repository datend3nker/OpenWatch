package tech.mmarca.openwatch.devices.wearos

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet
import dagger.multibindings.StringKey
import javax.inject.Singleton
import tech.mmarca.openwatch.StartupActor
import tech.mmarca.openwatch.devices.core.DeviceFamily
import tech.mmarca.openwatch.devices.core.RecordedWorkoutPort
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncPort

/**
 * The Wear OS watch's bindings, installed by the app's graph from this module: its family by
 * its persisted name, its sync port, the link to the watch app, and what it starts with the
 * process. Its classifier is ranked against the other families' in the app's `DevicesModule`,
 * since the order is a rule across families.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WearOsModule {

    // DeviceFamilies checks the key against the family's id.
    @Binds
    @IntoMap
    @StringKey("WEAROS")
    abstract fun bindWearOsFamily(impl: WearOsFamily): DeviceFamily

    // One port per integration; the composite hands each device to the one that claims it.
    @Binds
    @IntoSet
    abstract fun bindWearOsSyncPort(impl: WearOsWatchSyncService): DeviceSyncPort

    @Binds
    @Singleton
    abstract fun bindWearOsNodePort(impl: BluetoothWearOsNodePort): WearOsNodePort

    // The watch app records a workout for an app on the phone; the link host asks each port bound here.
    @Binds
    @IntoSet
    abstract fun bindWearOsRecordedWorkouts(impl: WearOsRecordedWorkouts): RecordedWorkoutPort

    companion object {
        @Provides
        @IntoSet
        fun provideWearOsPhoneActivityAtStart(activity: WearOsPhoneActivity) =
            // Tells the watches when the phone is unlocked, so they end a sleep hold they started.
            StartupActor("phone activity", activity::start)
    }
}
