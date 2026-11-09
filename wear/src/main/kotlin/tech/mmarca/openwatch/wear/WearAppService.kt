package tech.mmarca.openwatch.wear

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tech.mmarca.openwatch.wear.workout.WorkoutPillKey
import tech.mmarca.openwatch.wear.workout.WorkoutState
import tech.mmarca.openwatch.wear.workout.pillKey
import tech.mmarca.openwatch.wear.workout.sportName
import tech.mmarca.openwatch.wear.workout.stopwatchBaseMillis
import tech.mmarca.openwatch.wear.features.quicklog.EntryLog
import tech.mmarca.openwatch.wear.samsung.SamsungHeartRateFeed
import tech.mmarca.openwatch.wear.workout.MetricStoreWorkoutRows
import tech.mmarca.openwatch.wear.workout.WorkoutRecorder
import tech.mmarca.openwatch.wear.workout.WorkoutStore
import tech.mmarca.openwatch.wearlink.WorkoutSport
import tech.mmarca.openwatch.wearlink.HeartRateInterval

/**
 * The watch's end of the phone link, and the host of the heart rate, step,
 * ascent and sleep minute recorders. The heart rate rests while the watch
 * is off the wrist ([WornMonitor]).
 *
 * The link itself is [WearLinkServerHost] over `:wearlink`; this service
 * keeps it, the recorders and the trust store alive as a foreground
 * service (a plain background service is stopped about a minute after the
 * activity closes). Its type is `connectedDevice`, plus `health` once the
 * heart rate or the step counter is granted. Bluetooth off closes the listener; on again
 * reopens it; a bond change refreshes which trusted phones are still paired.
 *
 * The phone side is `app/.../devices/wearos/BluetoothWearOsNodePort`.
 */
class WearAppService : Service() {

    /** False when the system refused the foreground start: nothing may run then. */
    private var isForeground = false

    private lateinit var store: MetricStore
    private lateinit var recorder: HeartRateRecorder
    private lateinit var stepRecorder: StepRecorder
    private lateinit var ascentRecorder: AscentRecorder
    private lateinit var minuteRecorder: SleepMinuteRecorder
    private lateinit var nightHrv: NightHrvRecorder
    private lateinit var skinRecorder: SkinTemperatureRecorder
    private lateinit var spo2Recorder: Spo2Recorder
    private lateinit var wornMonitor: WornMonitor
    private lateinit var bedtimeMonitor: BedtimeMonitor
    private lateinit var samsungFeed: SamsungHeartRateFeed
    private lateinit var debugSnapshots: DebugSnapshots
    private lateinit var wakeSync: SensorWakeSync
    private lateinit var workout: WorkoutRecorder
    private lateinit var ppgLogger: PpgRawLogger
    private lateinit var trust: WearTrustStore
    private lateinit var link: WearLinkServerHost
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** False while [WornMonitor] says the watch is off the wrist. Main thread only. */
    private var heartRateWanted = true

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> {
                        WearLinkState.update { it.copy(bluetoothOn = true) }
                        link.start()
                        refreshBondLost()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        WearLinkState.update { it.copy(bluetoothOn = false) }
                        link.stop()
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> refreshBondLost()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DebugProtocol.init(this)
        store = MetricStore(this)
        samsungFeed = SamsungHeartRateFeed.shared(this)
        wakeSync = SensorWakeSync(this)
        workout = WorkoutRecorder(this, store) {
            // The location type is held only while a workout reads the GPS; the heart rate runs at the full rate.
            if (isForeground) startInForeground()
            reconcileRecorders()
        }
        Thread({ workout.recoverInterrupted() }, "WorkoutRecover").start()
        // A start, pause, resume or end changes the pill; the ticking of the clock does not.
        serviceScope.launch {
            WorkoutState.state.map { it.pillKey() }.distinctUntilChanged().collect { mainHandler.post(::refreshNotification) }
        }
        // Off the main thread: a week of heart rate is tens of thousands of rows.
        Thread({ LegacyStoreImport.run(this, store) }, "LegacyStoreImport").start()
        ppgLogger = PpgRawLogger(this)
        minuteRecorder = SleepMinuteRecorder(
            this,
            store,
            isHeartRateRecording = ::isHeartRateDense,
            stepsInMinute = ::stepsInMinute,
            // The heart rate runs at full rate while sleep is held; the plan reads it.
            onSleepHoldChange = { mainHandler.post { reconcileRecorders() } },
            wakeSync = wakeSync,
            syncedHold = { isHeartRateDense() && WearSettings.syncedSleepHold(this) },
        )
        recorder = HeartRateRecorder(
            this,
            store,
            samsungFeed,
            wakeSync,
            onSourceChanged = { mainHandler.post { reconcileRecorders() } },
            onSample = { at, bpm ->
                minuteRecorder.noteHeartRate(at, bpm)
                if (workout.isActive) workout.noteHeartRate(at, bpm)
            },
            onContact = minuteRecorder::noteHeartRateContact,
        )
        wornMonitor = WornMonitor(
            this,
            onWorn = minuteRecorder::noteWorn,
            onHeartRateWanted = { wanted -> mainHandler.post { setHeartRateWanted(wanted) } },
        )
        nightHrv = NightHrvRecorder(
            this,
            store,
            isHolding = { WearLinkState.state.value.sleepHoldActive },
            samsungFeed = samsungFeed,
        )
        skinRecorder = SkinTemperatureRecorder(store, samsungFeed)
        spo2Recorder = Spo2Recorder(this, store, samsungFeed, isHolding = { WearLinkState.state.value.sleepHoldActive })
        bedtimeMonitor = BedtimeMonitor(this, mainHandler) { reconcileRecorders() }
        debugSnapshots = DebugSnapshots(this, mainHandler, ::debugPlan)
        stepRecorder = StepRecorder(this, store, wakeSync)
        ascentRecorder = AscentRecorder(this, store, wakeSync)
        trust = WearTrustStore(this).also { it.onPending = { pending -> WearPhoneRequests.notify(this, pending) } }
        link = WearLinkServerHost(
            adapter = { getSystemService(BluetoothManager::class.java)?.adapter },
            trust = trust,
            store = store,
            live = LiveHeartRate(this, isRecording = { recorder.isRunning }),
            hrvAvailable = { nightHrv.hasSensor },
            skinAvailable = { skinRecorder.isAvailable },
            onPhoneActive = { at -> minuteRecorder.notePhoneActive(at) },
            heldPreferences = { WearPreferenceCodec.encode(WearSettings.read(this), abilities()) },
            setPreference = ::applyPhonePreference,
            workouts = WorkoutStore(MetricStoreWorkoutRows(store))::workoutsSince,
            putDay = DaySummaryStore(this)::put,
            entries = EntryLog(this)::entriesSince,
            localName = ::localName,
        )
        isForeground = startInForeground()
        if (!isForeground) {
            stopSelf()
            return
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }
        WearLinkState.update { it.copy(bluetoothOn = getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true) }
        link.start()
        refreshBondLost()
        // Before the first plan: the plan reads whether bedtime mode is on.
        bedtimeMonitor.start()
        startRecordingIfPermitted()
        // Need no grant: the accelerometer and the barometer are open to every app.
        minuteRecorder.start()
        ascentRecorder.start()
        wornMonitor.start()
        debugSnapshots.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // onCreate already stopped a service the system refused; do not restart it.
        if (!isForeground) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_TOGGLE_PPG_LOG -> {
                if (ppgLogger.isRunning) ppgLogger.stop() else ppgLogger.start()
                WearLinkState.update { it.copy(ppgLogging = ppgLogger.isRunning) }
            }
            ACTION_HEART_RATE_ALARM -> {
                DebugProtocol.count("hr.alarms")
                recorder.onAlarmFired()
                return START_STICKY
            }
            ACTION_WORKOUT_START -> {
                val sport = WorkoutSport.fromId(intent.getStringExtra(EXTRA_SPORT)) ?: return START_STICKY
                val gps = intent.getBooleanExtra(EXTRA_GPS, false) && WearPermissions.hasLocation(this)
                workout.start(sport, gps)
                return START_STICKY
            }
            ACTION_WORKOUT_PAUSE -> { workout.pause(); return START_STICKY }
            ACTION_WORKOUT_RESUME -> { workout.resume(); return START_STICKY }
            ACTION_WORKOUT_STOP -> { workout.stop(); return START_STICKY }
            ACTION_SETTINGS_CHANGED -> {
                recorder.resetFallBack()
                nightHrv.resetFallBack()
                reconcileRecorders()
                debugSnapshots.write("settings")
            }
        }
        link.start()
        // A grant made after the start: the type set grows to include health.
        val newHeartRate = !recorder.isRunning && heartRateWanted && WearPermissions.hasHeartRate(this)
        val newSteps = !stepRecorder.isRunning && stepRecorder.hasSensor && WearPermissions.hasActivityRecognition(this)
        if (newHeartRate || newSteps) {
            startInForeground()
            startRecordingIfPermitted()
        }
        return START_STICKY
    }

    /**
     * False when the system refuses, e.g. BLUETOOTH_CONNECT not granted on
     * API 34+. Calling it again with the heart rate or step grant in place
     * adds the `health` type to the running service.
     */
    private fun startInForeground(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.link_channel_name), NotificationManager.IMPORTANCE_MIN),
        )
        // The watch face pill needs a channel that is not MIN; it is used only while a workout runs.
        manager.createNotificationChannel(
            NotificationChannel(WORKOUT_CHANNEL_ID, getString(R.string.workout_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = buildNotification()
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val sensing = WearPermissions.hasHeartRate(this) || WearPermissions.hasActivityRecognition(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && sensing) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        }
        if (::workout.isInitialized && workout.usesGps && WearPermissions.hasLocation(this)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return try {
            startForeground(NOTIFICATION_ID, notification, types)
            true
        } catch (e: Exception) {
            // SecurityException for the missing grant, or a start from the background.
            Log.e(TAG, "Cannot run the phone link in the foreground", e)
            false
        }
    }

    /** The foreground service's notification: the phone link. It never carries the pill. */
    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.link_notification_text))
            .setOngoing(true)
            .build()

    /**
     * The workout's own notification, which carries the ongoing activity: the
     * pill on the watch face. Its own id, so cancelling it at the end takes the
     * pill away while the link's notification stays.
     */
    private fun buildWorkoutNotification(key: WorkoutPillKey, activeSeconds: Long): Notification {
        val base = stopwatchBaseMillis(key, activeSeconds, SystemClock.elapsedRealtime())
        val builder = NotificationCompat.Builder(this, WORKOUT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(sportName(key.sport)))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
        // The time also as the notification's own chronometer, for a surface that shows that rather than the status.
        if (base == null) {
            builder.setContentText(getString(R.string.recording_paused))
        } else {
            builder.setUsesChronometer(true).setShowWhen(true).setWhen(System.currentTimeMillis() - activeSeconds * 1000)
        }
        applyWorkoutPill(builder, key, activeSeconds)
        return builder.build()
    }

    /** Adds the ongoing activity: sport and stopwatch, or sport and "Paused". A tap opens the workout screen. */
    private fun applyWorkoutPill(builder: NotificationCompat.Builder, key: WorkoutPillKey, activeSeconds: Long) {
        val base = stopwatchBaseMillis(key, activeSeconds, SystemClock.elapsedRealtime())
        val status = Status.Builder().addPart("sport", Status.TextPart(getString(sportName(key.sport))))
        if (base == null) {
            status.addTemplate("#sport# · #state#").addPart("state", Status.TextPart(getString(R.string.recording_paused)))
        } else {
            status.addTemplate("#sport# · #time#").addPart("time", Status.StopwatchPart(base))
        }
        // MainActivity shows the workout screen by itself while a workout runs.
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        builder.setContentIntent(open)
        OngoingActivity.Builder(applicationContext, WORKOUT_NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_launcher_monochrome)
            .setTitle(getString(sportName(key.sport)))
            .setStatus(status.build())
            .setTouchIntent(open)
            .build()
            .apply(applicationContext)
    }

    /** Shows, updates or removes the pill; on the main thread, when a workout starts, pauses, resumes or ends. */
    private fun refreshNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        val state = WorkoutState.state.value
        val key = state.pillKey()
        if (key == null) {
            manager.cancel(WORKOUT_NOTIFICATION_ID)
        } else {
            manager.notify(WORKOUT_NOTIFICATION_ID, buildWorkoutNotification(key, state.snapshot?.activeSeconds ?: 0))
        }
    }

    /** Starts each recorder whose grant is in and whose sensor the watch has; the rest wait for a later call. */
    private fun startRecordingIfPermitted() {
        reconcileHeartRate()
        // The raw PPG sits under the heart rate grant; the windows also need the minute rows that show a still arm.
        nightHrv.setEnabled(WearPermissions.hasHeartRate(this))
        when {
            !stepRecorder.hasSensor -> Log.i(TAG, "No step counter")
            WearPermissions.hasActivityRecognition(this) -> stepRecorder.start()
            else -> Log.i(TAG, "Steps not granted")
        }
    }

    private fun setHeartRateWanted(wanted: Boolean) {
        if (!isForeground || wanted == heartRateWanted) return
        heartRateWanted = wanted
        reconcileHeartRate()
    }

    /** The settings, bedtime mode or a grant changed: every recorder follows. Main thread. */
    private fun reconcileRecorders() {
        if (!isForeground) return
        startRecordingIfPermitted()
    }

    /**
     * The heart rate plan in force ([HeartRateSchedule]): off when not granted
     * or off the wrist, the full rate in bedtime mode (the sleep minutes read
     * every sample), the day setting otherwise. Main thread.
     */
    private fun reconcileHeartRate() {
        val granted = WearPermissions.hasHeartRate(this)
        when {
            !granted -> Log.i(TAG, "Heart rate not granted")
            !heartRateWanted -> Log.i(TAG, "Heart rate resting: not worn")
        }
        val day = WearSettings.heartRateDay(this)
        val active = ::workout.isInitialized && workout.isActive
        val wanted = if (granted && (heartRateWanted || active)) {
            HeartRateSchedule.interval(
                heartRate = true,
                sleepRecording = true,
                bedtime = bedtimeMonitor.isBedtime || minuteRecorder.isSleepHolding,
                day = day,
                night = day,
                workout = active,
            )
        } else {
            HeartRateInterval.OFF
        }
        val before = recorder.interval to recorder.source
        recorder.setPlan(wanted, WearSettings.heartRateSource(this), workout = active)
        // Without the heart rate's wake-ups the step counter must wake the watch itself.
        stepRecorder.setHeartRateRunning(isHeartRateDense())
        // Skin temperature and SpO2 come only from the Samsung platform, at night, each switched on by itself.
        val night = granted && heartRateWanted && minuteRecorder.isSleepHolding
        skinRecorder.setEnabled(night && WearSettings.skinTemperature(this) && WearPermissions.hasSkinTemperature(this))
        spo2Recorder.setEnabled(granted && WearSettings.spo2AtNight(this) && WearPermissions.hasOxygenSaturation(this))
        if (before != (recorder.interval to recorder.source)) debugSnapshots.write("plan")
    }

    private fun abilities() = WatchAbilities(
        samsung = samsungFeed.isAvailable,
        skinGranted = WearPermissions.hasSkinTemperature(this),
        spo2Granted = WearPermissions.hasOxygenSaturation(this),
    )

    /** A setting the phone changed: stored, and the recorders follow as for one changed on the watch. Link thread. */
    private fun applyPhonePreference(key: String, value: String): Boolean {
        val next = WearPreferenceCodec.apply(WearSettings.read(this), abilities(), key, value) ?: return false
        WearSettings.write(this, next)
        mainHandler.post {
            recorder.resetFallBack()
            nightHrv.resetFallBack()
            reconcileRecorders()
            debugSnapshots.write("settings")
        }
        return true
    }

    /** The steps stored for the minute that starts at [minuteStartMillis]. Any thread. */
    private fun stepsInMinute(minuteStartMillis: Long): Int =
        store.since(WearMetrics.STEPS, minuteStartMillis - 1, 2)
            .filter { it.epochMillis == minuteStartMillis }.sumOf { it.steps }

    /** Whether the heart rate arrives every few seconds and wakes the processor: the full rate from the sensor. */
    private fun isHeartRateDense(): Boolean =
        recorder.interval == HeartRateSchedule.FULL_RATE && recorder.source == WearSettings.Source.RAW_SENSOR

    /** What the debug snapshots record as the plan in force. */
    private fun debugPlan(): Map<String, Any?> = mapOf(
        "hrSec" to recorder.interval.seconds,
        "hrSource" to recorder.source.name,
        "hrvSource" to WearSettings.hrvSource(this).name,
        "hrvOn" to nightHrv.isRunning,
        "bedtime" to bedtimeMonitor.isBedtime,
        "hold" to WearLinkState.state.value.bedtimeHold.name,
        "sleepHold" to minuteRecorder.isSleepHolding,
        "syncedHold" to WearSettings.syncedSleepHold(this),
        "lock" to minuteRecorder.holdsLastingLock,
        "skin" to skinRecorder.isRunning,
        "spo2" to spo2Recorder.isRunning,
        "workout" to workout.isActive,
        "worn" to heartRateWanted,
        "steps" to stepRecorder.isRunning,
    )

    /** The watch's Bluetooth name, as the phone will show it. */
    @SuppressLint("MissingPermission")
    private fun localName(): String {
        // The service only runs with the Bluetooth grant (startIfPermitted); the catch covers a revocation since.
        if (!WearPermissions.hasBluetooth(this)) return Build.MODEL
        return runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.name }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }

    /** Trusted phones the watch is no longer bonded with: a bond the phone or the watch dropped. */
    @SuppressLint("MissingPermission")
    private fun refreshBondLost() {
        if (!WearPermissions.hasBluetooth(this)) return
        val bonded = runCatching {
            getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty().map { it.address.uppercase() }.toSet()
        }.getOrDefault(emptySet())
        val lost = trust.trusted().filter { it.address.uppercase() !in bonded }
        WearLinkState.update { it.copy(bondLost = lost) }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        // A pill must not outlive the service that records the workout.
        getSystemService(NotificationManager::class.java).cancel(WORKOUT_NOTIFICATION_ID)
        if (isForeground) {
            unregisterReceiver(bluetoothReceiver)
            wornMonitor.stop()
            bedtimeMonitor.stop()
            debugSnapshots.stop()
            mainHandler.removeCallbacksAndMessages(null)
            recorder.stop()
            stepRecorder.stop()
            ascentRecorder.stop()
            minuteRecorder.stop()
            nightHrv.stop()
            skinRecorder.stop()
            spo2Recorder.stop()
            workout.release()
            ppgLogger.stop()
            WearLinkState.update { it.copy(ppgLogging = false) }
        }
        link.stop()
        wakeSync.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WearAppService"
        private const val CHANNEL_ID = "phone_link"
        private const val WORKOUT_CHANNEL_ID = "workout"
        private const val NOTIFICATION_ID = 1
        // 2 is WearPhoneRequests.NOTIFICATION_ID.
        private const val WORKOUT_NOTIFICATION_ID = 3
        private const val ACTION_TOGGLE_PPG_LOG = "tech.mmarca.openwatch.wear.TOGGLE_PPG_LOG"
        const val ACTION_HEART_RATE_ALARM = "tech.mmarca.openwatch.wear.HEART_RATE_ALARM"
        private const val ACTION_SETTINGS_CHANGED = "tech.mmarca.openwatch.wear.SETTINGS_CHANGED"
        private const val ACTION_WORKOUT_START = "tech.mmarca.openwatch.wear.WORKOUT_START"
        private const val ACTION_WORKOUT_PAUSE = "tech.mmarca.openwatch.wear.WORKOUT_PAUSE"
        private const val ACTION_WORKOUT_RESUME = "tech.mmarca.openwatch.wear.WORKOUT_RESUME"
        private const val ACTION_WORKOUT_STOP = "tech.mmarca.openwatch.wear.WORKOUT_STOP"
        private const val EXTRA_SPORT = "sport"
        private const val EXTRA_GPS = "gps"

        /** Starts a workout; [gps] only takes effect with the location grant. Called from the foreground app. */
        fun startWorkout(context: Context, sport: WorkoutSport, gps: Boolean) =
            sendWorkout(context, Intent(context, WearAppService::class.java).setAction(ACTION_WORKOUT_START).putExtra(EXTRA_SPORT, sport.id).putExtra(EXTRA_GPS, gps))

        fun pauseWorkout(context: Context) = sendWorkout(context, Intent(context, WearAppService::class.java).setAction(ACTION_WORKOUT_PAUSE))

        fun resumeWorkout(context: Context) = sendWorkout(context, Intent(context, WearAppService::class.java).setAction(ACTION_WORKOUT_RESUME))

        fun stopWorkout(context: Context) = sendWorkout(context, Intent(context, WearAppService::class.java).setAction(ACTION_WORKOUT_STOP))

        private fun sendWorkout(context: Context, intent: Intent) {
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "Cannot reach the service for a workout", it) }
        }

        /** A setting on the status screen changed: the running service applies it. */
        fun settingsChanged(context: Context) {
            if (!WearPermissions.hasBluetooth(context)) return
            runCatching {
                context.startForegroundService(Intent(context, WearAppService::class.java).setAction(ACTION_SETTINGS_CHANGED))
            }.onFailure { Log.w(TAG, "Cannot reach the service for a settings change", it) }
        }

        /** Starts or stops the raw PPG log (debuggable builds with the sensor only). */
        fun togglePpgLog(context: Context) {
            if (!WearPermissions.hasBluetooth(context)) return
            runCatching {
                context.startForegroundService(Intent(context, WearAppService::class.java).setAction(ACTION_TOGGLE_PPG_LOG))
            }.onFailure { Log.e(TAG, "Cannot toggle the PPG log", it) }
        }

        /** Starts the link once the Bluetooth grant is there; a no-op without it. */
        fun startIfPermitted(context: Context) {
            if (!WearPermissions.hasBluetooth(context)) return
            runCatching { context.startForegroundService(Intent(context, WearAppService::class.java)) }
                .onFailure { Log.e(TAG, "Cannot start the phone link", it) }
        }
    }
}
