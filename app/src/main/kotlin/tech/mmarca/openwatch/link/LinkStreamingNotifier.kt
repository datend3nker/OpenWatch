package tech.mmarca.openwatch.link

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import tech.mmarca.openlink.host.OpenLinkHost
import tech.mmarca.openwatch.MainActivity
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.features.connectedapps.appLabel

/**
 * The notification while another app keeps a watch streaming: "Streaming heart rate to
 * OpenVitals". A session holds the watch's radio and battery on the user's behalf, so it is
 * never invisible. Silent and ongoing; it goes when the last session ends.
 */
@Singleton
class LinkStreamingNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val host: OpenLinkHost,
    private val sessions: LinkLiveSessions,
    private val recorded: LinkRecordedWorkouts,
) {
    /** Runs for the app's life, from [OpenWatchLinkPublisher]. */
    suspend fun run() {
        combine(host.activeSessions, sessions.activity, recorded.activity) { active, live, workouts ->
            // A recorded workout and its live subscription share a session: the workout is what the line says.
            linkSessionStatuses(active, live + workouts)
        }
            .distinctUntilChanged()
            .collect(::show)
    }

    private fun show(statuses: List<LinkSessionStatus>) {
        val manager = NotificationManagerCompat.from(context)
        val first = statuses.firstOrNull()
        if (first == null) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        ensureChannel()
        val app = appLabel(context, first.packageName)
        val devices = first.deviceNames.joinToString(", ")
        val title = when (first.kind) {
            LinkSessionStatus.Kind.HEART_RATE -> context.getString(R.string.link_streaming_heart_rate, app)
            LinkSessionStatus.Kind.HEART_RATE_AND_STEPS -> context.getString(R.string.link_streaming_heart_rate_steps, app)
            LinkSessionStatus.Kind.KEEPING_STREAMING -> context.getString(R.string.link_streaming_keeping, app)
            LinkSessionStatus.Kind.RECORDING_WORKOUT -> context.getString(R.string.link_recording_workout, app)
            LinkSessionStatus.Kind.WAITING -> context.getString(R.string.link_streaming_waiting, app)
        }
        val text = if (first.kind == LinkSessionStatus.Kind.WAITING) {
            context.getString(R.string.link_streaming_waiting_for, devices)
        } else {
            context.getString(R.string.link_streaming_from, devices)
        }
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPENLINK_CONSENT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_activity_recording)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        // Without the notification grant there is nothing to show; the session runs all the same.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.link_streaming_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private companion object {
        const val CHANNEL_ID = "openlink_streaming"
        const val NOTIFICATION_ID = 2061
    }
}
