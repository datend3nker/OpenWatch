package tech.mmarca.openwatch.wear

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import java.time.Year
import kotlinx.coroutines.flow.StateFlow
import tech.mmarca.healthcalculations.preferences.BiologicalSex
import tech.mmarca.healthcalculations.preferences.BodyProfile
import tech.mmarca.openwatch.wear.samsung.SamsungHealthPlatform
import tech.mmarca.openwatch.wear.samsung.SamsungHeartRateFeed
import tech.mmarca.openwatch.wear.samsung.SamsungHeartRateSource
import tech.mmarca.openwatch.wear.samsung.SamsungIbiSource
import tech.mmarca.openwatch.wear.samsung.SamsungSpotSource
import tech.mmarca.openwatch.wear.workout.ActivityDetectionMonitor
import tech.mmarca.openwatch.wear.workout.MetricStoreWorkoutRows
import tech.mmarca.openwatch.wear.workout.RecoveryAtStop
import tech.mmarca.openwatch.wear.workout.RemoteWorkout
import tech.mmarca.openwatch.wear.workout.WorkoutFigures
import tech.mmarca.openwatch.wear.workout.WorkoutRecorder
import tech.mmarca.openwatch.wear.workout.WorkoutState
import tech.mmarca.openwatch.wear.workout.WorkoutStore
import tech.mmarca.openwatch.wearlink.Page
import tech.mmarca.openwatch.wearlink.WearLinkEntry
import tech.mmarca.openwatch.wearlink.WearLinkWorkout
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl
import tech.mmarca.openwatch.wearlink.WorkoutSport

/**
 * Everything the watch app runs on, built by hand once per process
 * ([OpenWatchWearApplication]): the store and the entry log over it, the
 * link's state and trust, the recorders and the sources they share, the link
 * server and the debug protocol. The watch has no DI framework; this file is its wiring, and
 * nothing else builds one of these parts.
 *
 * A part is built on first use, so a tile that only reads the store builds
 * nothing else. The recorders live as long as the process: [WearAppService]
 * starts, changes and stops them, and is the [host] they tell, on the main
 * thread, when they change what the plan rests on.
 */
internal class WearGraph(private val app: Application) {

    /** What the running service does when a part of the graph changes an input of its plan. Main thread. */
    interface Host {
        /** The sleep hold or the heart rate's source changed: the plan is decided again. */
        fun replan()

        /** [WornMonitor] says the heart rate should run, or rest off the wrist. */
        fun onWornChanged(wanted: Boolean)

        /** A workout started or ended: the foreground types and the plan follow. */
        fun onWorkoutChanged()

        /** The phone changed a setting: the recorders follow as for one changed on the watch. */
        fun onSettingsChanged()
    }

    /** The service while it runs; null otherwise, and then nothing is told. */
    @Volatile
    var host: Host? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun tellHost(action: Host.() -> Unit) {
        mainHandler.post { host?.action() }
    }

    /** True while the watch app is on screen: an input of the plan ([RecorderPlan.Inputs.interactive]). */
    @Volatile
    var interactive: Boolean = false
        private set

    /** `MainActivity` came to the front or left it: the running service plans again, so the steps come live or batched. */
    fun setInteractive(on: Boolean) {
        if (on == interactive) return
        interactive = on
        tellHost { replan() }
    }

    val debug: DebugProtocol by lazy { DebugProtocol.of(app) }
    val linkState = WearLinkState()
    val workoutState = WorkoutState()
    val secretKey = WearSecretKey()
    val phoneRequests: WearPhoneRequests by lazy { WearPhoneRequests(app) }
    val store: MetricStore by lazy { MetricStore(app) }

    /**
     * The entries made on the watch, by hand, on the quick log tile or by a
     * spot measurement: their one writer. A glass of water, wherever it was
     * logged, asks the quick log tile to show the day's new total.
     */
    val entries: EntryLog by lazy { EntryLog(store, onLogged = ::entryLogged) }

    /** The dashboard's tiles the wearer chose, in their order. The first read loads their file: off the main thread. */
    val tiles: WearTilePreferences by lazy { WearTilePreferences(app) }

    /** The quick log's entries the wearer chose, in their order. The first read loads their file: off the main thread. */
    val quickLog: WearQuickLogPreferences by lazy { WearQuickLogPreferences(app) }

    /** The settings as they change, on the watch or from the phone. The first read loads their file: off the main thread. */
    val settings: StateFlow<WatchPreferences>
        get() = settingsState.state

    // Held for the process: SharedPreferences keeps its change listener only weakly, so a
    // collected WearSettingsState would leave the flow at the value it had then.
    private val settingsState: WearSettingsState by lazy { WearSettingsState(app) }

    val trust: WearTrustStore by lazy {
        WearTrustStore(app, linkState, secretKey).also { it.onPending = { pending -> phoneRequests.notify(pending) } }
    }

    val samsungFeed: SamsungHeartRateFeed by lazy { SamsungHeartRateFeed(app, debug) }
    val wakeSync: SensorWakeSync by lazy { SensorWakeSync(app, debug) }

    /*
     * The sources behind their interfaces: the raw sensors everywhere, the
     * Samsung platform where the watch has it. The recorders see only the
     * interfaces.
     */
    private val sensorHeartRate: HeartRateSource by lazy { SensorHeartRateSource(app, debug, wakeSync) }
    private val samsungHeartRate: HeartRateSource? by lazy { SamsungHeartRateSource(samsungFeed).takeIf { it.isAvailable } }
    private val ppgWindows: HrvWindowSource by lazy { PpgWindowSource(app, debug) }

    /** The night's HRV windows and a spot HRV take turns with the sensor: one window at a time. */
    private val hrvClaim = SensorClaim()

    /** The night's SpO2 and a spot one take turns with the platform's tracker, which it starts for the first reader only. */
    private val spo2Claim = SensorClaim()
    private val samsungWindows: HrvWindowSource by lazy { SamsungIbiSource(samsungFeed, debug) }
    private val spo2Source: SpotSource<Spo2Reading> by lazy { SamsungSpotSource.spo2(samsungFeed) }
    private val skinSource: SpotSource<SkinReading> by lazy { SamsungSpotSource.skinTemperature(samsungFeed) }

    val heartRate: HeartRateRecorder by lazy {
        HeartRateRecorder(
            store,
            sensor = sensorHeartRate,
            platform = samsungHeartRate,
            debug = debug,
            onSourceChanged = { tellHost { replan() } },
            onSample = { at, bpm ->
                sleepMinutes.noteHeartRate(at, bpm)
                if (workout.isActive || workout.isRecovering) workout.noteHeartRate(at, bpm)
            },
            onContact = { at, contact -> sleepMinutes.noteHeartRateContact(at, contact) },
        )
    }

    val sleepMinutes: SleepMinuteRecorder by lazy {
        SleepMinuteRecorder(
            app,
            store,
            linkState,
            debug,
            isHeartRateRecording = { heartRate.isDense },
            stepsInMinute = ::stepsInMinute,
            // The heart rate runs at full rate while sleep is held; the plan reads it.
            onSleepHoldChange = { tellHost { replan() } },
            wakeSync = wakeSync,
            syncedHold = { heartRate.isDense && WearSettings.syncedSleepHold(app) },
        )
    }

    val steps: StepRecorder by lazy { StepRecorder(app, store, debug, wakeSync, heartRateDense = { heartRate.isDense }) }
    val ascent: AscentRecorder by lazy { AscentRecorder(app, store, debug, wakeSync) }

    val nightHrv: NightHrvRecorder by lazy {
        NightHrvRecorder(
            app,
            store,
            debug,
            isHolding = { linkState.state.value.sleepHoldActive },
            ppg = ppgWindows,
            samsung = samsungWindows,
            claim = hrvClaim,
        )
    }

    val skinTemperature: SkinTemperatureRecorder by lazy { SkinTemperatureRecorder(store, skinSource, debug) }

    val spo2: Spo2Recorder by lazy {
        Spo2Recorder(app, store, spo2Source, debug, isHolding = { linkState.state.value.sleepHoldActive }, claim = spo2Claim)
    }

    /**
     * Every recorder, in the order a plan is applied: the heart rate first,
     * because the step counter's variant follows the heart rate in force.
     */
    val recorders: List<Recorder> by lazy { listOf(heartRate, steps, sleepMinutes, ascent, nightHrv, skinTemperature, spo2) }

    val worn: WornMonitor by lazy {
        WornMonitor(
            app,
            debug,
            onWorn = { at, worn -> sleepMinutes.noteWorn(at, worn) },
            onHeartRateWanted = { wanted -> tellHost { onWornChanged(wanted) } },
        )
    }

    /** Its callback already runs on the main thread. */
    val workout: WorkoutRecorder by lazy {
        WorkoutRecorder(app, store, workoutState, debug, settings = { settings.value }) { host?.onWorkoutChanged() }
    }

    /** The offer of a walk or a run seen in the step minutes, while no workout or recovery runs. The service starts it. */
    val detection: ActivityDetectionMonitor by lazy {
        ActivityDetectionMonitor(app, store, debug, settings = { settings.value }, workoutRunning = { workout.isActive || workout.isRecovering })
    }

    val ppgLogger: PpgRawLogger by lazy { PpgRawLogger(app) }

    /** The heart rate as it happens: the phone's `HR_LIVE` windows. */
    val liveHeartRate: LiveHeartRate by lazy { LiveHeartRate(app, isRecording = { heartRate.isRunning }) }

    /**
     * What the watch has to measure with, read once: the facts the recorders,
     * the link and the spot measurements go by (the heart rate, the
     * heart-beat sensor, the step counter, the barometer) and the GPS
     * receiver. The screens offer a metric, a workout's field or a spot
     * measurement by it. Reads the sensor list and the package manager: not
     * on the main thread.
     */
    val sensors: WatchSensors by lazy {
        WatchSensors(
            heartRate = liveHeartRate.hasSensor,
            heartBeat = HeartBeatSensor.hasSensor(app),
            rawPpg = ppgWindows.isAvailable,
            stepCounter = StepRecorder.hasStepCounter(app),
            barometer = AscentRecorder.hasBarometer(app),
            gps = app.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS),
            spo2 = spotSpo2Source.isAvailable,
        )
    }

    /** A spot SpO2's own source of the platform's tracker, over the same feed: the night's keeps its own sink. */
    private val spotSpo2Source: SpotSource<Spo2Reading> by lazy { SamsungSpotSource.spo2(samsungFeed) }

    private val heartBeats: HeartBeatSensor by lazy { HeartBeatSensor(app) }
    private val spotPressure: PressureSensor by lazy { PressureSensor(app) }
    private val spotMotion: MotionSensor by lazy { MotionSensor(app) }

    /** A spot HRV's own raw PPG source: the night's holds its window's samples, so the two never share one. */
    private val spotPpgWindows: HrvWindowSource by lazy {
        PpgWindowSource(app, debug, minCleanBeats = SpotRules.HRV_MIN_CLEAN_BEATS, windowEvent = "spot_ppg_window")
    }

    /** A spot HRV's own source of the Samsung platform's intervals, over the same feed, waiting briefly for its last batch. */
    private val spotSamsungWindows: HrvWindowSource by lazy {
        SamsungIbiSource(
            samsungFeed,
            debug,
            batchWaitMillis = SPOT_BATCH_WAIT_MILLIS,
            minCleanBeats = SpotRules.HRV_MIN_CLEAN_BEATS,
            windowEvent = "spot_samsung_window",
        )
    }

    /**
     * The window a spot HRV takes its beats from, chosen when it starts: the Samsung platform's
     * intervals where the wearer chose them as the HRV source and the watch has the platform, else
     * the single beats where the watch reports them, else the raw pulse signal; null with none.
     */
    private fun spotHrvWindow(): HrvWindowSource? = when {
        WearSettings.hrvSource(app) == WearSettings.Source.SAMSUNG_PLATFORM && samsungFeed.isAvailable -> spotSamsungWindows
        sensors.heartBeat -> HeartBeatWindowSource(heartBeats)
        sensors.rawPpg -> spotPpgWindows
        else -> null
    }

    /**
     * The spot measurements the wearer starts by hand, each over a source of
     * its own and only where [sensors] has its sensor (read when one starts,
     * off the main thread): the screens run them through it and never open a
     * sensor.
     */
    val spot: SpotMeasurements by lazy {
        SpotMeasurements(
            app,
            heartRate = { liveHeartRate.takeIf { sensors.heartRate }?.let { live -> SpotSensor(live::listen) } },
            hrvWindows = ::spotHrvWindow,
            motion = { spotMotion },
            hrvClaim = hrvClaim,
            pressure = { spotPressure.takeIf { sensors.barometer } },
            spo2 = { spotSpo2Source.takeIf { sensors.spo2 } },
            spo2Claim = spo2Claim,
            spo2Granted = { WearPermissions.hasOxygenSaturation(app) },
            heartRateGranted = { WearPermissions.hasHeartRate(app) },
            entries = entries,
            debug = debug,
        )
    }

    /** The phone's figures for the last days (`PUT_DAY`): the night, the resting heart rate, energy and distance. The first read loads their file. */
    val phoneDays: PhoneDays by lazy { PhoneDays(app) }

    /** When a phone last pulled, and the reminder to sync before unpulled rows are pruned. */
    val pulls: PhonePulls by lazy { PhonePulls(app) }
    val syncReminder: SyncReminder by lazy {
        SyncReminder(app, pulls, firstTrustedMillis = { linkState.state.value.trusted.minOfOrNull { it.trustedAtMillis } })
    }

    val link: WearLinkServerHost by lazy {
        WearLinkServerHost(
            adapter = { app.getSystemService(BluetoothManager::class.java)?.adapter },
            trust = trust,
            store = store,
            live = liveHeartRate,
            hrvAvailable = { nightHrv.hasSensor },
            skinAvailable = { skinTemperature.isAvailable },
            onPhoneActive = { at -> sleepMinutes.notePhoneActive(at) },
            heldPreferences = { WearPreferenceCodec.encode(WearSettings.read(app), abilities()) },
            setPreference = ::applyPhonePreference,
            workouts = ::workoutsPulled,
            putDay = { day -> phoneDays.put(day) },
            workoutControl = remoteWorkout::control,
            hasGps = { sensors.gps },
            localName = ::localName,
            state = linkState,
        )
    }

    /**
     * The workout an app on the phone has the watch record (OpenLink's `RECORD`): the ordinary
     * workout, steered with the workout screen's own commands, its session kept in the store.
     */
    private val remoteWorkout: RemoteWorkout by lazy {
        RemoteWorkout(
            RemoteWorkout.Stored(store),
            object : RemoteWorkout.Controls {
                override fun start(sport: WorkoutSport) = WearAppService.startWorkout(app, sport, gps = sport.gps)
                override fun pause() = WearAppService.pauseWorkout(app)
                override fun resume() = WearAppService.resumeWorkout(app)
                override fun stop() = WearAppService.stopWorkout(app, RecoveryAtStop.NONE)
            },
            workoutState.state,
            summaryOf = ::remoteWorkoutSummary,
        )
    }

    /**
     * A recorded workout's summary for the app, from the figures the watch's own summary shows
     * ([WorkoutFigures]): the time in each zone and the zones' bounds, the TRIMP, and the recovery
     * once the two minutes after the stop are worked out. Link thread.
     */
    private fun remoteWorkoutSummary(workoutStartMillis: Long): WearLinkWorkoutControl.Summary? {
        val figures = WorkoutFigures(store, { settings.value }).of(workoutStartMillis) ?: return null
        val state = workoutState.state.value
        val recovery = state.recovery?.falls?.takeIf { state.startMillis == workoutStartMillis }
        return WearLinkWorkoutControl.Summary(
            zoneSeconds = figures.zoneSeconds.map { it.toInt() },
            zoneLowerBoundsBpm = settings.value.zoneBasis()?.boundsBpm.orEmpty().takeIf { figures.zoneSeconds.isNotEmpty() }.orEmpty(),
            trimp = figures.trimp,
            recovery1MinBpm = recovery?.oneMinuteBpm,
            recovery2MinBpm = recovery?.twoMinutesBpm,
        )
    }

    private val workoutStore by lazy { WorkoutStore(MetricStoreWorkoutRows(store)) }

    /**
     * The workouts a phone pulls (`WO_SINCE`), the last series of its sync:
     * the pull is recorded here, for the status screen and the reminder to
     * sync. Link thread.
     */
    private fun workoutsPulled(sinceEpochMillis: Long, limit: Int): Page<WearLinkWorkout> =
        workoutStore.workoutsSince(sinceEpochMillis, limit).also { syncReminder.onPulled(System.currentTimeMillis()) }

    /** What the watch can offer the phone's settings screen, which the phone cannot change. */
    fun abilities() = WatchAbilities(
        samsung = samsungFeed.isAvailable,
        skinGranted = WearPermissions.hasSkinTemperature(app),
        spo2Granted = WearPermissions.hasOxygenSaturation(app),
    )

    /** A setting the phone changed: stored, and the running service follows. Link thread. */
    private fun applyPhonePreference(key: String, value: String): Boolean {
        val held = WearSettings.read(app)
        val next = WearPreferenceCodec.apply(held, abilities(), key, value) ?: return false
        // The phone sends its shared settings, the goals, with every sync: one that changes nothing resets nothing.
        if (next == held) return true
        WearSettings.write(app, next)
        if (next.stepGoal != held.stepGoal) {
            ComplicationDataSourceUpdateRequester.create(app, ComponentName(app, StepsComplicationService::class.java)).requestUpdateAll()
        }
        if (next.waterGoalMl != held.waterGoalMl) TileService.getUpdater(app).requestUpdate(QuickLogTileService::class.java)
        tellHost { onSettingsChanged() }
        return true
    }

    /** An entry was written: the quick log tile's ring follows the water. Any thread. */
    private fun entryLogged(type: WearLinkEntry.Type) {
        if (type == WearLinkEntry.Type.WATER) TileService.getUpdater(app).requestUpdate(QuickLogTileService::class.java)
    }

    /** The steps stored for the minute that starts at [minuteStartMillis]. Any thread. */
    private fun stepsInMinute(minuteStartMillis: Long): Int =
        store.since(WearMetrics.STEPS, minuteStartMillis - 1, 2)
            .filter { it.epochMillis == minuteStartMillis }.sumOf { it.steps }

    /** The watch's Bluetooth name, as the phone will show it. */
    @SuppressLint("MissingPermission")
    private fun localName(): String {
        // The service only runs with the Bluetooth grant (startIfPermitted); the catch covers a revocation since.
        if (!WearPermissions.hasBluetooth(app)) return Build.MODEL
        return runCatching { app.getSystemService(BluetoothManager::class.java)?.adapter?.name }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }

    private companion object {
        /** With the screen on the platform hands its points over soon; past this a spot HRV uses those in hand. */
        const val SPOT_BATCH_WAIT_MILLIS = 20_000L
    }
}
