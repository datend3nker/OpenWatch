package tech.mmarca.openwatch.wear.features.measure

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import tech.mmarca.openwatch.wear.DebugProtocol
import tech.mmarca.openwatch.wear.EcgHeartRate
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.samsung.EcgPoint
import tech.mmarca.openwatch.wear.samsung.RawPoint
import tech.mmarca.openwatch.wear.samsung.SamsungHealthPlatform
import tech.mmarca.openwatch.wear.samsung.SamsungHeartRateFeed
import tech.mmarca.openwatch.wear.samsung.toEcgPoint
import tech.mmarca.openwatch.wear.ui.components.MetricValueRow

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

    enum class Phase { READY, WAITING_FOR_CONTACT, RECORDING, DONE, NO_RATE, PERMISSION_DENIED, FAILED }

    var phase by mutableStateOf(Phase.READY)
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

    /** The wearer refused the permission the platform checks. */
    fun denied() {
        phase = Phase.PERMISSION_DENIED
    }

    val isRunning: Boolean get() = phase == Phase.WAITING_FOR_CONTACT || phase == Phase.RECORDING

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

/**
 * The ECG as a measurement: ready with its instruction and a start button,
 * the trace and the progress along the edge while it records, then the
 * heart rate and the description of the rhythm.
 */
@Composable
internal fun EcgScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val measurement = remember { EcgMeasurement(SamsungHeartRateFeed.shared(context)) }
    DisposableEffect(measurement) { onDispose { measurement.stop() } }
    KeepScreenOn(measurement.isRunning)
    val start = rememberPermittedStart(Measurement.ECG, onGranted = measurement::start, onDenied = measurement::denied)
    val phase = measurement.phase

    val edgeButton: (@Composable () -> Unit)? = when (phase) {
        EcgMeasurement.Phase.READY -> {
            { EdgeButton(onClick = start) { Text(stringResource(R.string.measure_start)) } }
        }
        EcgMeasurement.Phase.DONE -> {
            { EdgeButton(onClick = onClose) { Text(stringResource(R.string.action_done)) } }
        }
        EcgMeasurement.Phase.PERMISSION_DENIED -> {
            { EdgeButton(onClick = start) { Text(stringResource(R.string.measure_grant)) } }
        }
        EcgMeasurement.Phase.NO_RATE, EcgMeasurement.Phase.FAILED -> {
            { EdgeButton(onClick = start) { Text(stringResource(R.string.action_retry)) } }
        }
        EcgMeasurement.Phase.WAITING_FOR_CONTACT, EcgMeasurement.Phase.RECORDING -> null
    }
    OwnMeasurementFrame(
        measurement = Measurement.ECG,
        progress = measurement.progress.takeIf { measurement.isRunning },
        edgeButton = edgeButton,
    ) {
        when (phase) {
            EcgMeasurement.Phase.READY -> {
                Icon(Measurement.ECG.icon, null, tint = Measurement.ECG.accentColor, modifier = Modifier.size(28.dp))
                FrameTitle(stringResource(Measurement.ECG.label))
                FrameBody(stringResource(Measurement.ECG.instruction))
            }
            EcgMeasurement.Phase.WAITING_FOR_CONTACT, EcgMeasurement.Phase.RECORDING -> {
                EcgTrace(measurement)
                FrameBody(
                    stringResource(
                        if (phase == EcgMeasurement.Phase.RECORDING) R.string.ecg_recording else R.string.ecg_touch,
                    ),
                )
            }
            EcgMeasurement.Phase.DONE -> {
                Icon(Measurement.ECG.icon, null, tint = Measurement.ECG.accentColor, modifier = Modifier.size(24.dp))
                MetricValueRow(
                    value = measurement.bpm.toString(),
                    unit = stringResource(R.string.unit_bpm),
                    valueStyle = MaterialTheme.typography.displayMedium,
                )
                FrameTitle(
                    stringResource(
                        when (measurement.rhythm) {
                            EcgHeartRate.Rhythm.SINUS_LIKE -> R.string.ecg_rhythm_sinus_like
                            EcgHeartRate.Rhythm.REGULAR -> R.string.ecg_rhythm_regular
                            EcgHeartRate.Rhythm.IRREGULAR -> R.string.ecg_rhythm_irregular
                            EcgHeartRate.Rhythm.UNCLEAR -> R.string.ecg_rhythm_unclear
                        },
                    ),
                )
                EcgTrace(measurement)
                FrameBody(stringResource(R.string.ecg_not_medical))
            }
            EcgMeasurement.Phase.NO_RATE, EcgMeasurement.Phase.PERMISSION_DENIED, EcgMeasurement.Phase.FAILED -> {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp),
                )
                FrameTitle(stringResource(Measurement.ECG.label))
                FrameBody(
                    stringResource(
                        when (phase) {
                            EcgMeasurement.Phase.NO_RATE -> R.string.ecg_no_rate
                            EcgMeasurement.Phase.PERMISSION_DENIED -> R.string.measure_error_additional_permission
                            else -> R.string.measure_failed
                        },
                    ),
                )
            }
        }
    }
}

/** The last seconds of the trace, scaled to their own range. */
@Composable
private fun EcgTrace(measurement: EcgMeasurement) {
    val color = Measurement.ECG.accentColor
    Canvas(Modifier.fillMaxWidth().height(40.dp)) {
        // Read here, so each delivery draws the trace again.
        measurement.traceVersion
        val trace = measurement.trace(TRACE_SECONDS)
        if (trace.size < 2) return@Canvas
        val low = trace.min()
        val span = (trace.max() - low).coerceAtLeast(1e-6f)
        var last: Offset? = null
        for (i in trace.indices step TRACE_STEP) {
            val point = Offset(
                x = size.width * i / (TRACE_SECONDS * EcgHeartRate.SAMPLE_RATE_HZ),
                y = size.height * (1f - (trace[i] - low) / span),
            )
            last?.let { drawLine(color, it, point, strokeWidth = 2f) }
            last = point
        }
    }
}

private const val TRACE_SECONDS = 3

/** Every third sample: 6 ms, still several points on an R wave. */
private const val TRACE_STEP = 3
