package tech.mmarca.openwatch.wear.haptics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openwatch.wear.features.measure.MeasureError
import tech.mmarca.openwatch.wear.features.measure.MeasureUiState

class HapticCueTest {

    @Test
    fun `a result is one pulse`() {
        assertEquals(HapticCue.PULSE, hapticCueFor(MeasureUiState.Done(value = 64.0, secondary = null)))
    }

    @Test
    fun `a failed run is two pulses`() {
        for (error in listOf(MeasureError.OFF_BODY, MeasureError.NO_SIGNAL, MeasureError.MOVED, MeasureError.FAILED)) {
            assertEquals(error.name, HapticCue.DOUBLE_PULSE, hapticCueFor(MeasureUiState.Failed(error)))
        }
    }

    @Test
    fun `a failure found before the run stays silent`() {
        assertNull(hapticCueFor(MeasureUiState.Failed(MeasureError.NOT_SUPPORTED)))
        assertNull(hapticCueFor(MeasureUiState.Failed(MeasureError.PERMISSION_DENIED)))
    }

    @Test
    fun `waiting and running are silent`() {
        assertNull(hapticCueFor(MeasureUiState.Ready))
        assertNull(hapticCueFor(MeasureUiState.Running(progress = 0.5f, live = 70.0, waiting = false, offBody = false)))
    }

    @Test
    fun `a saved workout is one pulse and a dropped one is silent`() {
        assertEquals(HapticCue.PULSE, hapticCueForWorkoutEnd(saved = true))
        assertNull(hapticCueForWorkoutEnd(saved = false))
    }

    @Test
    fun `every cue starts with an off time and has on times`() {
        for (cue in HapticCue.entries) {
            assertEquals(0L, cue.timings.first())
            assertEquals(0, cue.timings.size % 2)
        }
    }
}
