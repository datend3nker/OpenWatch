package tech.mmarca.openwatch.wear

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import tech.mmarca.openwatch.wear.samsung.EcgPoint
import tech.mmarca.openwatch.wear.samsung.RawPoint
import tech.mmarca.openwatch.wear.samsung.SamsungHealthPlatform
import tech.mmarca.openwatch.wear.samsung.SamsungHeartRateFeed
import tech.mmarca.openwatch.wear.samsung.toEcgPoint

/**
 * One ECG recording from the Samsung Health Platform's on-demand tracker:
 * [RECORD_SECONDS] of unbroken contact (wrist on the back, a finger on the
 * upper button), 500 samples a second. A lifted finger starts the recording
 * over. The trace is shown while it runs, and at the end its heart rate and
 * a description of its rhythm ([EcgHeartRate]): even or not, with a P wave
 * or without. Nothing is stored and no finding is drawn from it.
 *
 * Every method and callback is on the main thread.
 */
internal class EcgMeasurement(private val feed: SamsungHeartRateFeed) : SamsungHeartRateFeed.RawSubscriber {

    enum class Phase { WAITING_FOR_CONTACT, RECORDING, DONE, NO_RATE, FAILED }

    var phase by mutableStateOf(Phase.WAITING_FOR_CONTACT)
        private set
    var progress by mutableStateOf(0f)
        private set
    var bpm by mutableStateOf(0)
        private set
    var rhythm by mutableStateOf(EcgHeartRate.Rhythm.UNCLEAR)
        private set

    /** Changes with every delivery, so the trace is drawn again. */
    var traceVersion by mutableStateOf(0)
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val samples = FloatArray(RECORD_SECONDS * EcgHeartRate.SAMPLE_RATE_HZ)
    private var count = 0
    private var contact = false
    private var running = false
    private val timeout = Runnable { end(Phase.FAILED) }

    fun start() {
        if (running) return
        count = 0
        contact = false
        progress = 0f
        phase = Phase.WAITING_FOR_CONTACT
        running = feed.subscribeRaw(SamsungHealthPlatform.TRACKER_ECG, this, handler)
        if (!running) {
            phase = Phase.FAILED
            return
        }
        handler.postDelayed(timeout, TIMEOUT_MILLIS)
        DebugProtocol.event("Ecg", "started")
    }

    fun stop() = end(null)

    /** The last [seconds] of the recording so far, oldest first. */
    fun trace(seconds: Int): FloatArray {
        val wanted = minOf(count, seconds * EcgHeartRate.SAMPLE_RATE_HZ)
        return samples.copyOfRange(count - wanted, count)
    }

    override fun onRawPoints(points: List<RawPoint>) {
        if (!running) return
        for (raw in points) {
            val point = raw.toEcgPoint()
            if (point.hasLeadState) {
                val touching = point.leadOff == EcgPoint.LEAD_ON
                // Only an unbroken recording counts.
                if (!touching) count = 0
                contact = touching
            }
            if (!contact || point.millivolts.isNaN()) continue
            if (count < samples.size) samples[count++] = point.millivolts
        }
        progress = count.toFloat() / samples.size
        phase = if (contact) Phase.RECORDING else Phase.WAITING_FOR_CONTACT
        traceVersion++
        if (count == samples.size) {
            val result = EcgHeartRate.of(samples)
            bpm = result?.bpm ?: 0
            rhythm = result?.rhythm ?: EcgHeartRate.Rhythm.UNCLEAR
            DebugProtocol.event(
                "Ecg", "analysed",
                "bpm" to result?.bpm, "beats" to result?.beatCount, "rhythm" to result?.rhythm?.name,
                "irregularity" to result?.irregularity, "pWave" to result?.pWave,
            )
            end(if (result != null) Phase.DONE else Phase.NO_RATE)
        }
    }

    override fun onError() = end(Phase.FAILED)

    private fun end(result: Phase?) {
        if (!running) return
        running = false
        handler.removeCallbacks(timeout)
        feed.unsubscribeRaw(SamsungHealthPlatform.TRACKER_ECG, this)
        DebugProtocol.event("Ecg", "ended", "phase" to result?.name, "samples" to count)
        if (result != null) phase = result
    }

    private companion object {
        const val RECORD_SECONDS = 30

        /** Long enough to find the contact and record once over; past it the screen says so. */
        const val TIMEOUT_MILLIS = 120_000L
    }
}

@Composable
internal fun EcgScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val measurement = remember { EcgMeasurement(SamsungHeartRateFeed.shared(context)) }
    AdditionalHealthDataGate(context, onRefused = onClose) {
        KeepScreenOn()
        DisposableEffect(measurement) {
            measurement.start()
            onDispose { measurement.stop() }
        }
    }
    BackHandler(onBack = onClose)
    MeasureColumn {
        Text(
            stringResource(
                when (measurement.phase) {
                    EcgMeasurement.Phase.WAITING_FOR_CONTACT -> R.string.ecg_touch
                    EcgMeasurement.Phase.RECORDING -> R.string.ecg_recording
                    EcgMeasurement.Phase.DONE -> R.string.ecg_done
                    EcgMeasurement.Phase.NO_RATE -> R.string.ecg_no_rate
                    EcgMeasurement.Phase.FAILED -> R.string.measure_failed
                },
            ),
        )
        when (measurement.phase) {
            EcgMeasurement.Phase.DONE -> {
                Line(stringResource(R.string.status_heart_rate_bpm, measurement.bpm), title = true)
                Line(
                    stringResource(
                        when (measurement.rhythm) {
                            EcgHeartRate.Rhythm.SINUS_LIKE -> R.string.ecg_rhythm_sinus_like
                            EcgHeartRate.Rhythm.REGULAR -> R.string.ecg_rhythm_regular
                            EcgHeartRate.Rhythm.IRREGULAR -> R.string.ecg_rhythm_irregular
                            EcgHeartRate.Rhythm.UNCLEAR -> R.string.ecg_rhythm_unclear
                        },
                    ),
                )
            }
            EcgMeasurement.Phase.RECORDING -> Line(stringResource(R.string.measure_percent, (measurement.progress * 100).toInt()))
            else -> Unit
        }
        if (measurement.phase != EcgMeasurement.Phase.FAILED) EcgTrace(measurement)
        if (measurement.phase == EcgMeasurement.Phase.DONE || measurement.phase == EcgMeasurement.Phase.NO_RATE) {
            Line(stringResource(R.string.ecg_not_medical))
        }
        Button(onClick = onClose) { Text(stringResource(R.string.measure_close)) }
    }
}

/** The last seconds of the trace, scaled to their own range. */
@Composable
private fun EcgTrace(measurement: EcgMeasurement) {
    Canvas(Modifier.fillMaxWidth().height(48.dp)) {
        // Read here, so each delivery draws the trace again.
        measurement.traceVersion
        val trace = measurement.trace(TRACE_SECONDS)
        if (trace.size < 2) return@Canvas
        val low = trace.min()
        val span = (trace.max() - low).coerceAtLeast(1e-6f)
        val step = TRACE_STEP
        var last: Offset? = null
        for (i in trace.indices step step) {
            val point = Offset(
                x = size.width * i / (TRACE_SECONDS * EcgHeartRate.SAMPLE_RATE_HZ),
                y = size.height * (1f - (trace[i] - low) / span),
            )
            last?.let { drawLine(Color.Green, it, point, strokeWidth = 2f) }
            last = point
        }
    }
}

private const val TRACE_SECONDS = 3

/** Every third sample: 6 ms, still several points on an R wave. */
private const val TRACE_STEP = 3

/** The frame the two measurement screens share. */
@Composable
internal fun MeasureColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** A measurement the wearer watches: the screen stays on while it is shown. */
@Composable
internal fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * Runs [content] once the watch holds the permission the platform checks for
 * its ECG and body composition trackers, asking for it first; [onRefused]
 * when the wearer says no.
 */
@Composable
internal fun AdditionalHealthDataGate(context: Context, onRefused: () -> Unit, content: @Composable () -> Unit) {
    var granted by remember { mutableStateOf(WearPermissions.hasAdditionalHealthData(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        if (!result) onRefused()
    }
    LaunchedEffect(Unit) {
        if (!granted) ask.launch(WearPermissions.additionalHealthData)
    }
    if (granted) content()
}
