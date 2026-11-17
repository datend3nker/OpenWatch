package tech.mmarca.openwatch.wear.features.measure

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import java.time.Year
import tech.mmarca.openwatch.wear.DebugProtocol
import tech.mmarca.openwatch.wear.MetricStore
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.WearMetrics
import tech.mmarca.openwatch.wear.WearSettings
import tech.mmarca.openwatch.wear.samsung.BiaPoint
import tech.mmarca.openwatch.wear.samsung.RawPoint
import tech.mmarca.openwatch.wear.samsung.SamsungHealthPlatform
import tech.mmarca.openwatch.wear.samsung.SamsungHeartRateFeed
import tech.mmarca.openwatch.wear.samsung.toBiaPoint
import tech.mmarca.openwatch.wear.ui.components.MetricValueRow
import tech.mmarca.openwatch.wearlink.WearLinkBodyLines

/**
 * One body composition measurement from the Samsung Health Platform's
 * tracker: two fingers of the other hand on the two buttons, about fifteen
 * seconds. The platform computes the values itself, from the impedance and
 * the wearer's height, weight, age and sex, which it is handed for the
 * measurement ([WearSettings.BodyProfile]). The result is shown and kept as
 * a [WearMetrics.BODY_COMPOSITION] row for the phone's next sync.
 *
 * The first point of every run reports "no calibration" ([BiaPoint.STATUS_NO_CALIBRATION]):
 * the platform's library handles it before it sets the calibration the sensor sends with
 * it. That is not a failure. The platform's library loads on its first use in the process,
 * which can take longer than the sensor needs to send its calibration once; that run then
 * reports it for every point (seen on a Galaxy Watch7). When [NO_CALIBRATION_POINTS] points
 * in a row do, the tracker is started again, up to [MAX_RESTARTS] times: the library is
 * loaded by then.
 *
 * Every method and callback is on the main thread.
 */
internal class BiaMeasurement(
    private val feed: SamsungHeartRateFeed,
    /** Keeps a finished measurement. Main thread, once per result. */
    private val onResult: (BiaPoint) -> Unit,
) : SamsungHeartRateFeed.RawSubscriber {

    var progress by mutableStateOf(0f)
        private set

    /** What the platform says is wrong with the contact now ([BiaPoint] status), 0 for nothing. */
    var status by mutableStateOf(BiaPoint.STATUS_OK)
        private set
    var result by mutableStateOf<BiaPoint?>(null)
        private set
    var failed by mutableStateOf(false)
        private set

    var denied by mutableStateOf(false)
        private set

    private val handler = Handler(Looper.getMainLooper())
    var running by mutableStateOf(false)
        private set
    private val timeout = Runnable { end(failed = true) }

    private var profile: WearSettings.BodyProfile? = null
    private var restarts = 0

    /** Whether a point without that status has come since the tracker was subscribed, and how many without calibration came before it. */
    private var calibrated = false
    private var noCalibrationPoints = 0

    /** Set between giving up a tracker without calibration and starting the next; points that arrive then are old. */
    private var restarting = false
    private var subscribedAt = 0L

    fun start(profile: WearSettings.BodyProfile) {
        if (running) return
        this.profile = profile
        restarts = 0
        progress = 0f
        status = BiaPoint.STATUS_OK
        result = null
        denied = false
        running = subscribe()
        failed = !running
        if (!running) return
        handler.postDelayed(timeout, TIMEOUT_MILLIS)
        DebugProtocol.event("Bia", "started")
    }

    private fun subscribe(): Boolean {
        val wearer = profile ?: return false
        subscribedAt = System.currentTimeMillis()
        calibrated = false
        noCalibrationPoints = 0
        return feed.subscribeRaw(
            SamsungHealthPlatform.TRACKER_BIA, this, handler,
            profile = SamsungHealthPlatform.UserProfile(
                heightCm = wearer.heightCm.toFloat(),
                weightKg = wearer.weightKg.toFloat(),
                male = wearer.male,
                ageYears = wearer.ageYears,
            ),
        )
    }

    /** Starts the tracker again, so that its library is loaded when the sensor sends its calibration. */
    private fun restart() {
        restarts++
        restarting = true
        feed.unsubscribeRaw(SamsungHealthPlatform.TRACKER_BIA, this)
        DebugProtocol.event("Bia", "no_calibration_restart", "n" to restarts)
        handler.postDelayed({
            restarting = false
            if (!running) return@postDelayed
            if (!subscribe()) end(failed = true)
        }, RESTART_DELAY_MILLIS)
    }

    fun stop() = end(failed = false)

    /** The wearer refused the permission the platform checks. */
    fun deny() {
        denied = true
    }

    override fun onRawPoints(points: List<RawPoint>) {
        if (!running || restarting) return
        for (raw in points) {
            val point = raw.toBiaPoint()
            // After a restart, a point the run before left behind; the first run takes every point.
            if (restarts > 0 && point.epochMillis in 1 until subscribedAt) continue
            if (point.status == BiaPoint.STATUS_NO_CALIBRATION) {
                // The first point always; only a run that never gets past it has lost its calibration.
                if (calibrated || ++noCalibrationPoints < NO_CALIBRATION_POINTS) continue
                if (restarts < MAX_RESTARTS) return restart()
                status = point.status
                return end(failed = true)
            }
            calibrated = true
            status = point.status
            if (point.status != BiaPoint.STATUS_OK) continue
            if (!point.progress.isNaN()) progress = point.progress.coerceIn(0f, 1f)
            if (point.isResult) {
                result = point
                onResult(point)
                return end(failed = false)
            }
        }
    }

    override fun onError() = end(failed = true)

    private fun end(failed: Boolean) {
        if (!running) return
        running = false
        restarting = false
        handler.removeCallbacksAndMessages(null)
        feed.unsubscribeRaw(SamsungHealthPlatform.TRACKER_BIA, this)
        DebugProtocol.event("Bia", "ended", "result" to (result != null), "failed" to failed, "restarts" to restarts)
        this.failed = failed
    }

    private companion object {
        /** A measurement takes about fifteen seconds once the fingers are on; this leaves time to place them. */
        const val TIMEOUT_MILLIS = 90_000L

        /** Once the library is loaded the first restart works; a second is a margin. */
        const val MAX_RESTARTS = 2

        /** About a second of points: the first one is always without calibration, a lost one stays so. */
        const val NO_CALIBRATION_POINTS = 8
        const val RESTART_DELAY_MILLIS = 400L
    }
}

/**
 * The body composition as a measurement: the wearer's details first where
 * none are known, then ready with its instruction and a start button, the
 * progress along the edge with what the contact lacks, and the result.
 */
@Composable
internal fun BiaScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val year = remember { Year.now().value }
    var profile by remember { mutableStateOf(WearSettings.bodyProfile(context, year)) }
    var editing by remember { mutableStateOf(profile == null) }
    val measurement = remember {
        val store = MetricStore(context.applicationContext)
        BiaMeasurement(SamsungHeartRateFeed.shared(context)) { point ->
            // One small row, written once: not worth a thread of its own.
            Thread {
                val now = System.currentTimeMillis()
                store.put(WearMetrics.BODY_COMPOSITION, point.toLine(now))
                store.prune(WearMetrics.BODY_COMPOSITION, now)
            }.start()
        }
    }
    DisposableEffect(measurement) { onDispose { measurement.stop() } }
    KeepScreenOn(measurement.running)

    val current = profile
    if (editing || current == null) {
        BodyProfileEditor(current ?: DEFAULT_PROFILE) { saved ->
            WearSettings.setBodyProfile(context, saved, year)
            profile = saved
            editing = false
        }
        return
    }
    val start = rememberPermittedStart(
        Measurement.BODY_COMPOSITION,
        onGranted = { measurement.start(current) },
        onDenied = measurement::deny,
    )
    val result = measurement.result
    val failed = measurement.failed || measurement.denied

    val edgeButton: (@Composable () -> Unit)? = when {
        measurement.running -> null
        result != null -> {
            { EdgeButton(onClick = onClose) { Text(stringResource(R.string.action_done)) } }
        }
        measurement.denied -> {
            { EdgeButton(onClick = start) { Text(stringResource(R.string.measure_grant)) } }
        }
        measurement.failed -> {
            { EdgeButton(onClick = start) { Text(stringResource(R.string.action_retry)) } }
        }
        else -> {
            { EdgeButton(onClick = start) { Text(stringResource(R.string.measure_start)) } }
        }
    }
    val kind = Measurement.BODY_COMPOSITION
    OwnMeasurementFrame(kind, progress = measurement.progress.takeIf { measurement.running }, edgeButton = edgeButton) {
        when {
            measurement.running -> {
                Icon(kind.icon, null, tint = kind.accentColor, modifier = Modifier.size(28.dp))
                FrameBody(
                    stringResource(
                        when (measurement.status) {
                            BiaPoint.STATUS_OK -> R.string.bia_touch
                            BiaPoint.STATUS_WRIST_DETACHED, BiaPoint.STATUS_WRIST_LOOSE -> R.string.bia_wrist
                            BiaPoint.STATUS_FINGERS_DRY -> R.string.bia_dry
                            BiaPoint.STATUS_HANDS_TOUCH, BiaPoint.STATUS_FRAME_TOUCHED -> R.string.bia_apart
                            else -> R.string.bia_fingers
                        },
                    ),
                )
            }
            result != null -> {
                Icon(kind.icon, null, tint = kind.accentColor, modifier = Modifier.size(24.dp))
                MetricValueRow(
                    value = stringResource(R.string.bia_one_decimal, result.bodyFatPercent),
                    unit = stringResource(R.string.unit_percent),
                    valueStyle = MaterialTheme.typography.displayMedium,
                )
                FrameTitle(stringResource(R.string.bia_body_fat_label))
                FrameBody(stringResource(R.string.bia_muscle, result.skeletalMuscleMassKg))
                FrameBody(stringResource(R.string.bia_water, result.totalBodyWaterKg))
                FrameBody(stringResource(R.string.bia_basal_rate, result.basalMetabolicRateKcal.toInt()))
                FrameBody(stringResource(R.string.bia_estimate))
            }
            failed -> {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp),
                )
                FrameTitle(stringResource(kind.label))
                FrameBody(
                    stringResource(
                        when {
                            measurement.denied -> R.string.measure_error_additional_permission
                            measurement.status == BiaPoint.STATUS_NO_CALIBRATION -> R.string.bia_no_calibration
                            else -> R.string.measure_failed
                        },
                    ),
                )
            }
            else -> {
                Icon(kind.icon, null, tint = kind.accentColor, modifier = Modifier.size(28.dp))
                FrameTitle(stringResource(kind.label))
                FrameBody(stringResource(kind.instruction))
                Button(
                    onClick = { editing = true },
                    label = { Text(stringResource(R.string.bia_profile)) },
                    secondaryLabel = {
                        Text(stringResource(R.string.bia_profile_summary, current.heightCm, current.weightKg, current.ageYears))
                    },
                    colors = ButtonDefaults.outlinedButtonColors(),
                )
            }
        }
    }
}

/** Height, weight, age and sex, each stepped with two buttons; the platform takes whole numbers well enough. */
@Composable
private fun BodyProfileEditor(initial: WearSettings.BodyProfile, onSave: (WearSettings.BodyProfile) -> Unit) {
    var profile by remember { mutableStateOf(initial) }
    OwnMeasurementFrame(
        measurement = Measurement.BODY_COMPOSITION,
        progress = null,
        edgeButton = { EdgeButton(onClick = { onSave(profile) }) { Text(stringResource(R.string.action_done)) } },
    ) {
        FrameTitle(stringResource(R.string.bia_profile))
        Stepper(stringResource(R.string.bia_height, profile.heightCm)) {
            profile = profile.copy(heightCm = (profile.heightCm + it).coerceIn(100, 230))
        }
        Stepper(stringResource(R.string.bia_weight, profile.weightKg)) {
            profile = profile.copy(weightKg = (profile.weightKg + it).coerceIn(30, 250))
        }
        Stepper(stringResource(R.string.bia_age, profile.ageYears)) {
            profile = profile.copy(ageYears = (profile.ageYears + it).coerceIn(10, 110))
        }
        Button(
            onClick = { profile = profile.copy(male = !profile.male) },
            label = { Text(stringResource(if (profile.male) R.string.bia_sex_male else R.string.bia_sex_female)) },
            colors = ButtonDefaults.filledTonalButtonColors(),
        )
        FrameBody(stringResource(R.string.bia_profile_note))
    }
}

@Composable
private fun Stepper(label: String, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { onStep(-1) }, colors = ButtonDefaults.filledTonalButtonColors()) { Text("−") }
        Text(label, style = MaterialTheme.typography.titleMedium)
        Button(onClick = { onStep(1) }, colors = ButtonDefaults.filledTonalButtonColors()) { Text("+") }
    }
}

/** The result as the link carries it: tenths, and the time it was shown. */
internal fun BiaPoint.toLine(epochMillis: Long) = WearLinkBodyLines.BodyComposition(
    epochMillis = epochMillis,
    bodyFatPermille = Math.round(bodyFatPercent * 10),
    fatFreeMassHectograms = Math.round(fatFreeMassKg * 10),
    bodyWaterHectograms = Math.round(totalBodyWaterKg * 10),
)

/** Where the editor starts; the wearer sets their own before the first measurement. */
private val DEFAULT_PROFILE = WearSettings.BodyProfile(heightCm = 170, weightKg = 70, ageYears = 35, male = false)
