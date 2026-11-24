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
import tech.mmarca.openwatch.wear.workout.RecoveryAtStop
import tech.mmarca.openwatch.wear.workout.WorkoutPillKey
import tech.mmarca.openwatch.wear.workout.pillKey
import tech.mmarca.openwatch.wear.workout.sportName
import tech.mmarca.openwatch.wear.workout.stopwatchBaseMillis
import tech.mmarca.openwatch.wearlink.WorkoutSport

/**
 * The watch's end of the phone link, and the host of the recorders. The
 * heart rate rests while the watch is off the wrist ([WornMonitor]).
 *
 * The link itself is [WearLinkServerHost] over `:wearlink`; the recorders,
 * the link and the trust store live in the process's [WearGraph]. This
 * service keeps them running as a foreground service (a plain background
 * service is stopped about a minute after the activity closes), decides what
 * runs ([RecorderPlan]) and hands that to every [Recorder]. Its type is
 * `connectedDevice`, plus `health` once the heart rate or the step counter is
 * granted. Bluetooth off closes the listener; on again reopens it; a bond
 * change refreshes which trusted phones are still paired.
 *
 * The phone side is `app/.../devices/wearos/BluetoothWearOsNodePort`.
 */
class WearAppService : Service(), WearGraph.Host {

    /** False when the system refused the foreground start: nothing may run then. */
    private var isForeground = false

    private val graph: WearGraph by lazy { wearGraph }
    private lateinit var bedtimeMonitor: BedtimeMonitor
    private lateinit var debugSnapshots: DebugSnapshots
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** False while [WornMonitor] says the watch is off the wrist. Main thread only. */
    private var heartRateWanted = true

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> {
                        graph.linkState.update { it.copy(bluetoothOn = true) }
                        graph.link.start()
                        refreshBondLost()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        graph.linkState.update { it.copy(bluetoothOn = false) }
                        graph.link.stop()
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> refreshBondLost()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val graph = graph
        // Built here, on the main thread, before any of their threads runs.
        graph.recorders
        graph.worn
        // A fall-back from an earlier run of the service does not outlive it.
        graph.heartRate.resetFallBack()
        graph.nightHrv.resetFallBack()
        // What the Samsung platform offers is asked again at each start; the measure list reads the last answer.
        graph.refreshSamsungTrackerTypes()
        Thread({ graph.workout.recoverInterrupted() }, "WorkoutRecover").start()
        // A start, pause, resume or end changes the pill; the ticking of the clock does not.
        serviceScope.launch {
            graph.workoutState.state.map { it.pillKey() }.distinctUntilChanged().collect { mainHandler.post(::refreshNotification) }
        }
        // Off the main thread: a week of heart rate is tens of thousands of rows.
        Thread({ LegacyStoreImport.run(this, graph.store) }, "LegacyStoreImport").start()
        bedtimeMonitor = BedtimeMonitor(this, mainHandler) { reconcileRecorders() }
        debugSnapshots = DebugSnapshots(this, mainHandler, graph.debug, ::debugPlan)
        isForeground = startInForeground()
        if (!isForeground) {
            stopSelf()
            return
        }
        graph.host = this
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }
        graph.linkState.update { it.copy(bluetoothOn = getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true) }
        graph.link.start()
        refreshBondLost()
        // Before the first plan: the plan reads whether bedtime mode is on.
        bedtimeMonitor.start()
        reconcileRecorders()
        graph.worn.start()
        debugSnapshots.start()
        graph.syncReminder.start()
        graph.detection.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // onCreate already stopped a service the system refused; do not restart it.
        if (!isForeground) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_TOGGLE_PPG_LOG -> {
                val logger = graph.ppgLogger
                if (logger.isRunning) logger.stop() else logger.start()
                graph.linkState.update { it.copy(ppgLogging = logger.isRunning) }
            }
            ACTION_HEART_RATE_ALARM -> {
                graph.debug.count("hr.alarms")
                graph.heartRate.onAlarmFired()
                return START_STICKY
            }
            ACTION_WORKOUT_START -> {
                val sport = WorkoutSport.fromId(intent.getStringExtra(EXTRA_SPORT)) ?: return START_STICKY
                val gps = intent.getBooleanExtra(EXTRA_GPS, false) && WearPermissions.hasLocation(this)
                graph.workout.start(sport, gps, startMillis = intent.getLongExtra(EXTRA_START, 0L).takeIf { it > 0L })
                return START_STICKY
            }
            ACTION_WORKOUT_PAUSE -> { graph.workout.pause(); return START_STICKY }
            ACTION_WORKOUT_RESUME -> { graph.workout.resume(); return START_STICKY }
            ACTION_WORKOUT_STOP -> {
                val recovery = RecoveryAtStop.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_RECOVERY) } ?: RecoveryAtStop.NONE
                graph.workout.stop(recovery)
                return START_STICKY
            }
            ACTION_SETTINGS_CHANGED -> onSettingsChanged()
        }
        graph.link.start()
        // A grant made after the start: the type set grows to include health.
        val newHeartRate = !graph.heartRate.isRunning && heartRateWanted && WearPermissions.hasHeartRate(this)
        val newSteps = !graph.steps.isRunning && graph.steps.hasSensor && WearPermissions.hasActivityRecognition(this)
        // A service that fell back to the link alone takes health back once a start allows it.
        if (newHeartRate || newSteps || ForegroundStart.healthMissing(foregroundTypes, sensing(), healthTypeAvailable)) {
            startInForeground()
            reconcileRecorders()
        }
        return START_STICKY
    }

    override fun replan() = reconcileRecorders()

    override fun onWornChanged(wanted: Boolean) {
        if (!isForeground || wanted == heartRateWanted) return
        heartRateWanted = wanted
        reconcileRecorders()
    }

    override fun onWorkoutChanged() {
        // The location type is held only while a workout reads the GPS; the heart rate runs at the full rate.
        if (isForeground) startInForeground()
        reconcileRecorders()
    }

    override fun onSettingsChanged() {
        graph.heartRate.resetFallBack()
        graph.nightHrv.resetFallBack()
        reconcileRecorders()
        debugSnapshots.write("settings")
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
        var types = ForegroundStart.LINK
        if (healthTypeAvailable && sensing()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        }
        if (graph.workout.usesGps && WearPermissions.hasLocation(this)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        var refusal: Exception? = null
        for (attempt in ForegroundStart.attempts(types)) {
            try {
                startForeground(NOTIFICATION_ID, notification, attempt)
                foregroundTypes = attempt
                if (attempt != types) Log.w(TAG, "Foreground start refused; running the phone link alone", refusal)
                return true
            } catch (e: Exception) {
                // SecurityException for the missing grant, or a start from the background.
                refusal = e
            }
        }
        Log.e(TAG, "Cannot run the phone link in the foreground", refusal)
        return false
    }

    /** The foreground types the service runs with; 0 before it is in the foreground. */
    private var foregroundTypes = 0

    private val healthTypeAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    private fun sensing(): Boolean =
        WearPermissions.hasHeartRate(this) || WearPermissions.hasActivityRecognition(this)

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
        val builder = NotificationCompat.Builder(this, WORKOUT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(sportName(key.sport)))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
        // The time also as the notification's own chronometer, for a surface that shows it rather than the ongoing status.
        if (key.paused || key.recovering) {
            builder.setContentText(getString(pillState(key)))
        } else {
            builder.setUsesChronometer(true).setShowWhen(true).setWhen(System.currentTimeMillis() - activeSeconds * MILLIS_PER_SECOND)
        }
        applyWorkoutPill(builder, key, activeSeconds)
        return builder.build()
    }

    /** Adds the ongoing activity: sport and stopwatch, or sport and "Paused". A tap opens the workout screen. */
    private fun applyWorkoutPill(builder: NotificationCompat.Builder, key: WorkoutPillKey, activeSeconds: Long) {
        val base = stopwatchBaseMillis(key, activeSeconds, SystemClock.elapsedRealtime())
        val status = Status.Builder().addPart("sport", Status.TextPart(getString(sportName(key.sport))))
        if (base == null) {
            status.addTemplate("#sport# · #state#").addPart("state", Status.TextPart(getString(pillState(key))))
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
            .setStatus(status.build())
            .setTouchIntent(open)
            .build()
            .apply(applicationContext)
    }

    /** What the pill says instead of the time: the workout is paused, or its heart rate recovery runs. */
    private fun pillState(key: WorkoutPillKey): Int = if (key.recovering) R.string.recovery_title else R.string.recording_paused

    /** Shows, updates or removes the pill; on the main thread, when a workout starts, pauses, resumes or ends. */
    private fun refreshNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        val state = graph.workoutState.state.value
        val key = state.pillKey()
        if (key == null) {
            manager.cancel(WORKOUT_NOTIFICATION_ID)
        } else {
            manager.notify(WORKOUT_NOTIFICATION_ID, buildWorkoutNotification(key, state.snapshot?.activeSeconds ?: 0))
        }
    }

    /**
     * The settings, bedtime mode, the wrist, a grant or the sleep hold changed:
     * the plan is decided again ([RecorderPlan]) and every recorder follows it,
     * the heart rate first. Main thread.
     */
    private fun reconcileRecorders() {
        if (!isForeground) return
        val inputs = planInputs()
        when {
            !inputs.heartRateGranted -> Log.i(TAG, "Heart rate not granted")
            !inputs.worn -> Log.i(TAG, "Heart rate resting: not worn")
        }
        when {
            !inputs.hasStepCounter -> Log.i(TAG, "No step counter")
            !inputs.stepsGranted -> Log.i(TAG, "Steps not granted")
        }
        val heartRate = graph.heartRate
        val before = heartRate.interval to heartRate.source
        val plan = RecorderPlan.decide(inputs)
        for (recorder in graph.recorders) recorder.apply(plan)
        if (before != (heartRate.interval to heartRate.source)) debugSnapshots.write("plan")
    }

    private fun planInputs() = RecorderPlan.Inputs(
        heartRateGranted = WearPermissions.hasHeartRate(this),
        stepsGranted = WearPermissions.hasActivityRecognition(this),
        skinTemperatureGranted = WearPermissions.hasSkinTemperature(this),
        oxygenSaturationGranted = WearPermissions.hasOxygenSaturation(this),
        settings = WearSettings.read(this),
        hasStepCounter = graph.steps.hasSensor,
        hasAccelerometer = graph.sleepMinutes.hasSensor,
        hasBarometer = graph.ascent.hasSensor,
        worn = heartRateWanted,
        bedtime = bedtimeMonitor.isBedtime,
        sleepHolding = graph.sleepMinutes.isSleepHolding,
        workout = graph.workout.isActive,
        recovering = graph.workout.isRecovering,
        interactive = graph.interactive,
    )

    /** What the debug snapshots record as the plan in force, and today's held minutes against their budget. */
    private fun debugPlan(): Map<String, Any?> {
        val held = graph.sleepMinutes.holdBudget.today(System.currentTimeMillis())
        return planFields() + mapOf(
            "holdLockMin" to held.lockMinutes,
            "holdSyncedMin" to held.syncedMinutes,
            "holdBudgetMin" to SleepHoldBudget.LOCK_BUDGET_MINUTES,
        )
    }

    private fun planFields(): Map<String, Any?> = mapOf(
        "storeWriteFailures" to graph.store.writeFailures,
        "storeLastFailure" to graph.store.lastWriteFailure,
        "hrSec" to graph.heartRate.interval.seconds,
        "hrSource" to graph.heartRate.source.name,
        "hrLive" to graph.heartRate.isLive,
        "hrvSource" to WearSettings.hrvSource(this).name,
        "hrvOn" to graph.nightHrv.isRunning,
        "bedtime" to bedtimeMonitor.isBedtime,
        "hold" to graph.linkState.state.value.bedtimeHold.name,
        "sleepHold" to graph.sleepMinutes.isSleepHolding,
        "syncedHold" to WearSettings.syncedSleepHold(this),
        "lock" to graph.sleepMinutes.holdsLastingLock,
        "skin" to graph.skinTemperature.isRunning,
        "spo2" to graph.spo2.isRunning,
        "workout" to graph.workout.isActive,
        "recovering" to graph.workout.isRecovering,
        "worn" to heartRateWanted,
        "steps" to graph.steps.isRunning,
        "interactive" to graph.interactive,
    )

    /** Trusted phones the watch is no longer bonded with: a bond the phone or the watch dropped. */
    @SuppressLint("MissingPermission")
    private fun refreshBondLost() {
        if (!WearPermissions.hasBluetooth(this)) return
        val bonded = runCatching {
            getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty().map { it.address.uppercase() }.toSet()
        }.getOrDefault(emptySet())
        val lost = graph.trust.trusted().filter { it.address.uppercase() !in bonded }
        graph.linkState.update { it.copy(bondLost = lost) }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        // A pill must not outlive the service that records the workout.
        getSystemService(NotificationManager::class.java).cancel(WORKOUT_NOTIFICATION_ID)
        val graph = graph
        if (graph.host === this) graph.host = null
        if (isForeground) {
            unregisterReceiver(bluetoothReceiver)
            graph.worn.stop()
            graph.syncReminder.stop()
            graph.detection.stop()
            bedtimeMonitor.stop()
            debugSnapshots.stop()
            mainHandler.removeCallbacksAndMessages(null)
            for (recorder in graph.recorders) recorder.stop()
            graph.workout.release()
            graph.ppgLogger.stop()
            graph.linkState.update { it.copy(ppgLogging = false) }
        }
        graph.link.stop()
        graph.wakeSync.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WearAppService"
        private const val CHANNEL_ID = "phone_link"
        private const val WORKOUT_CHANNEL_ID = "workout"
        private const val NOTIFICATION_ID = 1
        // 2 is WearPhoneRequests.NOTIFICATION_ID, 4 SyncReminder's, 5 ActivityDetectionMonitor's.
        private const val WORKOUT_NOTIFICATION_ID = 3
        private const val MILLIS_PER_SECOND = 1_000L
        private const val ACTION_TOGGLE_PPG_LOG = "tech.mmarca.openwatch.wear.TOGGLE_PPG_LOG"
        const val ACTION_HEART_RATE_ALARM = "tech.mmarca.openwatch.wear.HEART_RATE_ALARM"
        private const val ACTION_SETTINGS_CHANGED = "tech.mmarca.openwatch.wear.SETTINGS_CHANGED"
        private const val ACTION_WORKOUT_START = "tech.mmarca.openwatch.wear.WORKOUT_START"
        private const val ACTION_WORKOUT_PAUSE = "tech.mmarca.openwatch.wear.WORKOUT_PAUSE"
        private const val ACTION_WORKOUT_RESUME = "tech.mmarca.openwatch.wear.WORKOUT_RESUME"
        private const val ACTION_WORKOUT_STOP = "tech.mmarca.openwatch.wear.WORKOUT_STOP"
        private const val EXTRA_SPORT = "sport"
        private const val EXTRA_GPS = "gps"
        private const val EXTRA_START = "start"
        private const val EXTRA_RECOVERY = "recovery"

        /**
         * Starts a workout; [gps] only takes effect with the location grant. Called from the
         * foreground app, or from the offer of a walk or run the watch saw, which begins it at
         * [startMillis], in the past, and credits it with what the watch kept since.
         */
        fun startWorkout(context: Context, sport: WorkoutSport, gps: Boolean, startMillis: Long? = null) = sendWorkout(
            context,
            Intent(context, WearAppService::class.java).setAction(ACTION_WORKOUT_START)
                .putExtra(EXTRA_SPORT, sport.id)
                .putExtra(EXTRA_GPS, gps)
                .apply { startMillis?.let { putExtra(EXTRA_START, it) } },
        )

        fun pauseWorkout(context: Context) = sendWorkout(context, Intent(context, WearAppService::class.java).setAction(ACTION_WORKOUT_PAUSE))

        fun resumeWorkout(context: Context) = sendWorkout(context, Intent(context, WearAppService::class.java).setAction(ACTION_WORKOUT_RESUME))

        /**
         * Ends the workout. [recovery] says what becomes of the heart rate recovery: nothing, the
         * falls read from a rest before the stop, or the heart rate kept live for two minutes.
         */
        fun stopWorkout(context: Context, recovery: RecoveryAtStop = RecoveryAtStop.NONE) = sendWorkout(
            context,
            Intent(context, WearAppService::class.java).setAction(ACTION_WORKOUT_STOP).putExtra(EXTRA_RECOVERY, recovery.name),
        )

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
