package tech.mmarca.openwatch.wear.workout

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import tech.mmarca.openwatch.wear.DebugProtocol
import tech.mmarca.openwatch.wear.MainActivity
import tech.mmarca.openwatch.wear.MetricStore
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.WatchPreferences
import tech.mmarca.openwatch.wear.WearAppService
import tech.mmarca.openwatch.wear.WearMetrics
import tech.mmarca.openwatch.wearlink.WorkoutSport
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/**
 * Offers to record a walk or a run the watch sees in its step minutes, or a
 * ride in those and the heart rate ([ActivityDetector]), while no workout runs: a notification ("Looks like a
 * walk") whose Record action starts the workout at the walk's start, so the
 * minutes already walked count ([WorkoutRecorder.start]). Nothing is
 * recorded by itself, and nothing runs for it but a read of the last three
 * hours of step minutes, on the IO dispatcher, each time the step recorder
 * writes some ([MetricStore.updates]) and at most once a minute: it runs no
 * timer and wakes nothing. Off with the `auto_detect` setting.
 *
 * An activity is offered once: [ignoreBefore] moves past it when it is
 * offered, and past every minute during which a workout or its recovery
 * ran, so a walk after a workout, or one the wearer left unanswered, does not
 * come back until a pause in it breaks it. A workout that starts takes the
 * offer away. Notification id 5, channel `detected_activity`. One per
 * process, held by `WearGraph`; the service starts and stops it.
 */
internal class ActivityDetectionMonitor(
    private val context: Context,
    private val store: MetricStore,
    private val debug: DebugProtocol,
    private val settings: () -> WatchPreferences,
    /** A workout runs, or its recovery: nothing is offered, and its minutes are no walk to offer later. */
    private val workoutRunning: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var scope: CoroutineScope? = null

    /** An activity that began at or before this has been offered, or was a workout. IO dispatcher. */
    @Volatile
    private var ignoreBefore = 0L

    /** Looks from now on, at each write of steps. Main thread. */
    fun start() {
        if (scope != null) return
        ignoreBefore = clock()
        val looking = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = looking
        looking.launch {
            // The count's present value is no write; each one after it is.
            store.updates(WearMetrics.STEPS).drop(1).conflate().collect {
                check()
                delay(CHECK_MILLIS)
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
    }

    /** One look at the step minutes; offers what it finds. Reads the store. */
    private fun check() {
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (workoutRunning()) notifications?.cancel(NOTIFICATION_ID)
        val found = look() ?: return
        debug.event("Workout", "detected", "sport" to found.sport.id, "minutes" to found.minutes)
        // Without the notification grant nothing can be offered; the activity counts as offered all the same.
        if (notifications == null || !notifications.areNotificationsEnabled()) return
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.detected_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
        )
        notifications.notify(NOTIFICATION_ID, offer(found))
    }

    /**
     * The walk or run to offer now, if there is one not offered yet: none while a workout runs,
     * whose minutes are then no walk to offer later either, and none with the setting off. Reads the store.
     */
    internal fun look(): ActivityDetector.Detection? {
        val now = clock()
        if (workoutRunning()) {
            ignoreBefore = now
            return null
        }
        if (!settings().autoDetect) return null
        val rows = store.since(WearMetrics.STEPS, now - LOOKBACK_MILLIS, LOOKBACK_ROWS)
        val found = ActivityDetector.detect(rows, now) ?: detectRide(rows, now) ?: return null
        if (found.startMillis <= ignoreBefore) return null
        ignoreBefore = now
        return found
    }

    /** A ride, only with a maximum heart rate from the phone: without it there are no zones to measure the effort by. */
    private fun detectRide(steps: List<WearLinkProtocol.StepMinute>, now: Long): ActivityDetector.Detection? {
        val moderate = settings().zoneBasis()?.boundsBpm?.getOrNull(MODERATE_ZONE) ?: return null
        val heartRate = store.since(WearMetrics.HEART_RATE, now - RIDE_LOOKBACK_MILLIS, RIDE_LOOKBACK_ROWS)
        return ActivityDetector.detectRide(steps, heartRate, now, moderate)
    }

    private fun offer(found: ActivityDetector.Detection): Notification {
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            // A notification's activity intent starts a task of its own; no flag (WearRecents).
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val record = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            Intent(context, ActivityDetectionReceiver::class.java)
                .setAction(ACTION_RECORD)
                .putExtra(EXTRA_SPORT, found.sport.id)
                .putExtra(EXTRA_START, found.startMillis),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // A ride is told by its heart rate; its steps are few by what it is.
        val text = found.heartRateBpm?.let { context.getString(R.string.detected_ride_body, found.minutes, it) } ?: context.getString(
            R.string.detected_body,
            found.minutes,
            context.resources.getQuantityString(R.plurals.detected_steps, found.steps, found.steps),
        )
        val title = when (found.sport) {
            WorkoutSport.RUNNING -> R.string.detected_run
            WorkoutSport.BIKING -> R.string.detected_ride
            else -> R.string.detected_walk
        }
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(title))
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_launcher_monochrome),
                    context.getString(R.string.detected_start),
                    record,
                ).build(),
            )
            .build()
    }

    companion object {
        const val ACTION_RECORD = "tech.mmarca.openwatch.wear.RECORD_DETECTED"
        const val EXTRA_SPORT = "sport"
        const val EXTRA_START = "start"
        const val CHANNEL_ID = "detected_activity"

        // 1 is the link's, 2 WearPhoneRequests', 3 the workout's, 4 SyncReminder's.
        const val NOTIFICATION_ID = 5

        /** At most one look a minute: the detector works in whole minutes. */
        private const val CHECK_MILLIS = 60_000L

        /** As far back as a workout may begin (`WorkoutRecorder`), and the rows that cover it. */
        private const val LOOKBACK_MILLIS = 3 * 60 * 60_000L
        private const val LOOKBACK_ROWS = 200

        /** An hour of heart rate at a sample every ten seconds; oldest first, so the limit must not cut it. */
        private const val RIDE_LOOKBACK_MILLIS = 60 * 60_000L
        private const val RIDE_LOOKBACK_ROWS = 400

        /** Zone 3, from index 0: its lower bound is the ACSM's moderate intensity. */
        private const val MODERATE_ZONE = 2
    }
}

/** The offer's Record action: the workout starts at the detected start, without a route, as the wearer walked so far without one. */
class ActivityDetectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ActivityDetectionMonitor.ACTION_RECORD) return
        val sport = WorkoutSport.fromId(intent.getStringExtra(ActivityDetectionMonitor.EXTRA_SPORT)) ?: return
        val start = intent.getLongExtra(ActivityDetectionMonitor.EXTRA_START, 0L).takeIf { it > 0L } ?: return
        context.getSystemService(NotificationManager::class.java)?.cancel(ActivityDetectionMonitor.NOTIFICATION_ID)
        WearAppService.startWorkout(context, sport, gps = false, startMillis = start)
    }
}
