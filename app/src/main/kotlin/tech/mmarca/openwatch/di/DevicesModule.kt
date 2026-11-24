package tech.mmarca.openwatch.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tech.mmarca.openwatch.StartupActor
import tech.mmarca.openwatch.core.performance.ApplicationScope
import tech.mmarca.openwatch.data.repository.RegisteredFamilies
import tech.mmarca.openwatch.devices.core.DeviceClassifier
import tech.mmarca.openwatch.devices.core.DeviceFamily
import tech.mmarca.openwatch.devices.core.DeviceScanClassifier
import tech.mmarca.openwatch.devices.core.RecordedWorkoutPort
import tech.mmarca.openwatch.devices.core.measure.DeviceMeasurementSink
import tech.mmarca.openwatch.devices.core.measure.MeasurementRecords
import tech.mmarca.openwatch.devices.core.measure.MeasurementSink
import tech.mmarca.openwatch.devices.core.pairing.BleWatchPairing
import tech.mmarca.openwatch.devices.core.pairing.WatchPairingPort
import tech.mmarca.openwatch.devices.core.sync.AndroidSyncWakeLock
import tech.mmarca.openwatch.devices.core.sync.AutoSyncIntervalStore
import tech.mmarca.openwatch.devices.core.sync.CompositeDeviceSyncPort
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncPort
import tech.mmarca.openwatch.devices.core.sync.SyncWakeLock
import tech.mmarca.openwatch.devices.core.sync.WatchAutoSyncScheduler
import tech.mmarca.openwatch.devices.garmin.GarminDeviceClassifier
import tech.mmarca.openwatch.devices.garmin.GarminDeviceStateStore
import tech.mmarca.openwatch.devices.garmin.GarminScanClassifier
import tech.mmarca.openwatch.devices.media.AndroidPhoneMediaSource
import tech.mmarca.openwatch.devices.media.PhoneMediaSource
import tech.mmarca.openwatch.devices.wearos.WearOsDeviceClassifier

/**
 * Wiring for the device layer, kept apart so [AppModule] carries no watch knowledge: what every
 * family shares (`:devices:core`'s pieces, the composite sync port, the measurement sink) and
 * the rules across families (the classifiers' order, where every watch's sync interval is
 * kept). Each family binds itself in its own module: `:devices:garmin`'s `GarminModule`,
 * `:devices:wearos`'s `WearOsModule`, `:devices:xiaomi`'s `XiaomiScaleModule`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DevicesModule {

    @Binds
    @Singleton
    abstract fun bindWatchPairingPort(impl: BleWatchPairing): WatchPairingPort

    @Binds
    @Singleton
    abstract fun bindPhoneMediaSource(impl: AndroidPhoneMediaSource): PhoneMediaSource

    // Every watch's sync interval, Wear OS included, lives in the Garmin store under its old key.
    @Binds
    abstract fun bindAutoSyncIntervalStore(impl: GarminDeviceStateStore): AutoSyncIntervalStore

    @Binds
    abstract fun bindSyncWakeLock(impl: AndroidSyncWakeLock): SyncWakeLock

    // Where pushed measurements go: kept per device, then written to Health Connect.
    @Binds
    abstract fun bindMeasurementSink(impl: DeviceMeasurementSink): MeasurementSink

    /** Each family that pushes measurements binds its mapping by its persisted name; none may. */
    @Multibinds
    abstract fun measurementRecords(): Map<String, MeasurementRecords>

    /** Each family whose devices record a workout for an app (OpenLink's `RECORD`) binds its port; none may. */
    @Multibinds
    abstract fun recordedWorkoutPorts(): Set<RecordedWorkoutPort>

    companion object {
        @Provides
        @IntoSet
        fun provideSyncSchedulesAtStart(scheduler: WatchAutoSyncScheduler) =
            // Re-plans the sync schedules after what WorkManager does not cover.
            StartupActor("sync schedules", scheduler::restoreAll)

        /**
         * The families' persisted names, which the registry reads its rows against. Providers: a
         * family takes the registry, so only the keys are read, never a family built.
         */
        @Provides
        fun provideRegisteredFamilies(families: Map<String, @JvmSuppressWildcards Provider<DeviceFamily>>) =
            RegisteredFamilies(families.keys)

        @Provides
        @IntoSet
        fun providePendingMeasurementsAtStart(sink: MeasurementSink, @ApplicationScope scope: CoroutineScope) =
            // A measurement that could not be written before the process ended is tried again.
            StartupActor("pending measurements") { scope.launch { sink.retryPending() } }

        /**
         * Each integration's verdict on a scanned device. A list, not a set: the first that
         * claims a device wins, so the order is part of the rule, across families, and stays
         * here though each family binds the rest of itself in its own module. A new
         * integration adds its classifier here, and nothing in `sensors/` changes.
         */
        @Provides
        fun provideDeviceClassifiers(): List<@JvmSuppressWildcards DeviceClassifier> =
            listOf(GarminDeviceClassifier(), WearOsDeviceClassifier())

        @Provides
        fun provideDeviceScanClassifiers(): List<@JvmSuppressWildcards DeviceScanClassifier> =
            listOf(GarminScanClassifier())

        @Provides
        @Singleton
        fun provideDeviceSyncPort(ports: Set<@JvmSuppressWildcards DeviceSyncPort>): DeviceSyncPort =
            CompositeDeviceSyncPort(ports)
    }
}
