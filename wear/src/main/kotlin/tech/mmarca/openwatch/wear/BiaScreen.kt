package tech.mmarca.openwatch.wear

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import tech.mmarca.openwatch.wear.samsung.BiaPoint
import tech.mmarca.openwatch.wear.samsung.RawPoint
import tech.mmarca.openwatch.wear.samsung.SamsungHealthPlatform
import tech.mmarca.openwatch.wear.samsung.SamsungHeartRateFeed
import tech.mmarca.openwatch.wear.samsung.toBiaPoint
import tech.mmarca.openwatch.wearlink.WearLinkBodyLines
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/**
 * One body composition measurement from the Samsung Health Platform's
 * tracker: two fingers of the other hand on the two buttons, about fifteen
 * seconds. The platform computes the values itself, from the impedance and
 * the wearer's height, weight, age and sex, which it is handed for the
 * measurement ([WearSettings.BodyProfile]). The result is shown and kept as
 * a [WearMetrics.BODY_COMPOSITION] row for the phone's next sync.
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

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val timeout = Runnable { end(failed = true) }

    fun start(profile: WearSettings.BodyProfile) {
        if (running) return
        progress = 0f
        status = BiaPoint.STATUS_OK
        result = null
        running = feed.subscribeRaw(
            SamsungHealthPlatform.TRACKER_BIA, this, handler,
            profile = SamsungHealthPlatform.UserProfile(
                heightCm = profile.heightCm.toFloat(),
                weightKg = profile.weightKg.toFloat(),
                male = profile.male,
                ageYears = profile.ageYears,
            ),
        )
        failed = !running
        if (!running) return
        handler.postDelayed(timeout, TIMEOUT_MILLIS)
        DebugProtocol.event("Bia", "started")
    }

    fun stop() = end(failed = false)

    override fun onRawPoints(points: List<RawPoint>) {
        if (!running) return
        for (raw in points) {
            val point = raw.toBiaPoint()
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
        handler.removeCallbacks(timeout)
        feed.unsubscribeRaw(SamsungHealthPlatform.TRACKER_BIA, this)
        DebugProtocol.event("Bia", "ended", "result" to (result != null), "failed" to failed)
        this.failed = failed
    }

    private companion object {
        /** A measurement takes about fifteen seconds once the fingers are on; this leaves time to place them. */
        const val TIMEOUT_MILLIS = 90_000L
    }
}

@Composable
internal fun BiaScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val year = remember { java.time.Year.now().value }
    var profile by remember { mutableStateOf(WearSettings.bodyProfile(context, year)) }
    var editing by remember { mutableStateOf(profile == null) }
    BackHandler(onBack = onClose)
    val current = profile
    if (editing || current == null) {
        BodyProfileEditor(current ?: DEFAULT_PROFILE) { saved ->
            WearSettings.setBodyProfile(context, saved, year)
            profile = saved
            editing = false
        }
        return
    }
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
    AdditionalHealthDataGate(context, onRefused = onClose) {
        KeepScreenOn()
        DisposableEffect(measurement, current) {
            measurement.start(current)
            onDispose { measurement.stop() }
        }
    }
    MeasureColumn {
        val result = measurement.result
        when {
            result != null -> {
                Line(stringResource(R.string.bia_body_fat, result.bodyFatPercent), title = true)
                Line(stringResource(R.string.bia_muscle, result.skeletalMuscleMassKg))
                Line(stringResource(R.string.bia_water, result.totalBodyWaterKg))
                Line(stringResource(R.string.bia_basal_rate, result.basalMetabolicRateKcal.toInt()))
                Line(stringResource(R.string.bia_estimate))
            }
            measurement.failed -> Text(stringResource(R.string.measure_failed))
            else -> {
                Text(
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
                Line(stringResource(R.string.measure_percent, (measurement.progress * 100).toInt()))
            }
        }
        Button(onClick = { editing = true }) { Text(stringResource(R.string.bia_profile)) }
        Button(onClick = onClose) { Text(stringResource(R.string.measure_close)) }
    }
}

/** Height, weight, age and sex, each stepped with two buttons; the platform takes whole numbers well enough. */
@Composable
private fun BodyProfileEditor(initial: WearSettings.BodyProfile, onSave: (WearSettings.BodyProfile) -> Unit) {
    var profile by remember { mutableStateOf(initial) }
    MeasureColumn {
        Line(stringResource(R.string.bia_profile_note))
        Stepper(stringResource(R.string.bia_height, profile.heightCm)) {
            profile = profile.copy(heightCm = (profile.heightCm + it).coerceIn(100, 230))
        }
        Stepper(stringResource(R.string.bia_weight, profile.weightKg)) {
            profile = profile.copy(weightKg = (profile.weightKg + it).coerceIn(30, 250))
        }
        Stepper(stringResource(R.string.bia_age, profile.ageYears)) {
            profile = profile.copy(ageYears = (profile.ageYears + it).coerceIn(10, 110))
        }
        Button(onClick = { profile = profile.copy(male = !profile.male) }) {
            Text(stringResource(if (profile.male) R.string.bia_sex_male else R.string.bia_sex_female))
        }
        Button(onClick = { onSave(profile) }) { Text(stringResource(R.string.bia_measure)) }
    }
}

@Composable
private fun Stepper(label: String, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { onStep(-1) }) { Text("−") }
        Text(label)
        Button(onClick = { onStep(1) }) { Text("+") }
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
