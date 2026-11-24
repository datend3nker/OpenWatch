package tech.mmarca.openwatch.wear.features.measure

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.mmarca.openwatch.wear.BodyComposition
import tech.mmarca.openwatch.wear.EcgHeartRate
import tech.mmarca.openwatch.wear.SpotEvent
import tech.mmarca.openwatch.wear.SpotFailure
import tech.mmarca.openwatch.wear.SpotHint
import tech.mmarca.openwatch.wear.SpotKind

/** The measurement screen's state. */
sealed interface MeasureUiState {
    /** What to do, and a start button. */
    data object Ready : MeasureUiState

    data class Running(
        /** The share of the measurement's longest time that has passed. */
        val progress: Float,
        /** The latest live value, such as the pulse now; null before the first. */
        val live: Double?,
        /** The sensor gives nothing usable yet. */
        val waiting: Boolean,
        /** The sensor says the watch is not on a wrist. */
        val offBody: Boolean,
        /** What the contact lacks now, where the measurement says so. */
        val hint: SpotHint? = null,
        /** An ECG's last seconds, oldest first; null for every other measurement. */
        val trace: FloatArray? = null,
        /** The measurement counts its own [progress] ([SpotEvent.Progress], [SpotEvent.Trace]) instead of the time. */
        val ownProgress: Boolean = false,
    ) : MeasureUiState

    /** The reading; an ECG's [ecg] and a body composition's [body] come with it. */
    data class Done(
        val value: Double,
        val secondary: Double?,
        val ecg: EcgHeartRate.Result? = null,
        val body: BodyComposition? = null,
    ) : MeasureUiState

    data class Failed(val failure: SpotFailure) : MeasureUiState
}

/**
 * Runs one spot measurement of [kind] for its screen, through [events]
 * (`WearGraph.spot`, which logs the reading): the screen opens no sensor.
 * Leaving the screen clears the ViewModel, which cancels the run and lets go
 * of the sensor. A run that ends, in its reading or in a failure, is told to
 * [onEnd] once, so the wrist buzzes; one that is cancelled is not.
 */
internal class MeasureViewModel(
    private val kind: SpotKind,
    private val events: (SpotKind) -> Flow<SpotEvent>,
    private val scope: CoroutineScope,
    private val onEnd: (MeasureUiState) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel(scope) {

    private val mutableState = MutableStateFlow<MeasureUiState>(MeasureUiState.Ready)
    val state: StateFlow<MeasureUiState> = mutableState.asStateFlow()

    private var run: Job? = null

    /** Starts the measurement, or again after one ended. */
    fun start() {
        run?.cancel()
        run = scope.launch {
            val startedAt = now()
            val durationMillis = kind.seconds * MILLIS_PER_SECOND
            var running = MeasureUiState.Running(progress = 0f, live = null, waiting = true, offBody = false)
            mutableState.value = running
            val ticker = launch {
                while (true) {
                    delay(TICK_MILLIS)
                    if (!running.ownProgress) running = running.copy(progress = ((now() - startedAt) / durationMillis).coerceIn(0f, 1f))
                    mutableState.value = running
                }
            }
            try {
                events(kind).collect { event ->
                    when (event) {
                        is SpotEvent.Live -> running = running.copy(live = event.value, waiting = false, offBody = false)
                        is SpotEvent.Waiting -> running = running.copy(waiting = true, offBody = event.offBody)
                        is SpotEvent.Progress -> running = running.copy(
                            progress = event.share, ownProgress = true, waiting = false, hint = event.hint,
                        )
                        is SpotEvent.Trace -> running = running.copy(
                            progress = event.recorded, ownProgress = true, waiting = false, trace = event.millivolts,
                            hint = if (event.contact) null else SpotHint.TOUCH_ELECTRODES,
                        )
                        is SpotEvent.Result -> mutableState.value = MeasureUiState.Done(event.value, event.secondary, event.ecg, event.body)
                        is SpotEvent.Failed -> mutableState.value = MeasureUiState.Failed(event.failure)
                    }
                    if (mutableState.value is MeasureUiState.Running) mutableState.value = running
                }
                // A run that ended without its reading or its reason failed.
                if (mutableState.value is MeasureUiState.Running) mutableState.value = MeasureUiState.Failed(SpotFailure.FAILED)
                onEnd(mutableState.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RuntimeException) {
                mutableState.value = MeasureUiState.Failed(if (error is SecurityException) SpotFailure.PERMISSION_DENIED else SpotFailure.FAILED)
                onEnd(mutableState.value)
            } finally {
                ticker.cancel()
            }
        }
    }

    /** A failure found before the run starts, such as a refused grant. */
    fun fail(failure: SpotFailure) {
        run?.cancel()
        mutableState.value = MeasureUiState.Failed(failure)
    }

    private companion object {
        const val TICK_MILLIS = 200L
        const val MILLIS_PER_SECOND = 1_000f
    }
}
