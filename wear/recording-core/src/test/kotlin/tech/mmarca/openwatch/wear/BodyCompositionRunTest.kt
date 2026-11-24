package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.mmarca.openwatch.wear.BodyCompositionRun.Companion.STATUS_FINGERS_DRY
import tech.mmarca.openwatch.wear.BodyCompositionRun.Companion.STATUS_NO_CALIBRATION
import tech.mmarca.openwatch.wear.BodyCompositionRun.Companion.STATUS_OK
import tech.mmarca.openwatch.wear.BodyCompositionRun.Point
import tech.mmarca.openwatch.wear.BodyCompositionRun.Step

class BodyCompositionRunTest {

    private fun point(at: Long, status: Int = STATUS_OK, progress: Float = 0.5f, result: Boolean = false) = Point(at, status, progress, result)

    @Test
    fun `the first point's missing calibration is no failure, and the progress and the result follow`() {
        val run = BodyCompositionRun().apply { started(1_000L) }
        assertEquals(Step.Continue, run.next(point(1_000L, STATUS_NO_CALIBRATION)))
        assertEquals(Step.Progress(0.5f, null), run.next(point(1_100L)))
        assertEquals(Step.Progress(0.5f, SpotHint.FINGERS_DRY), run.next(point(1_200L, STATUS_FINGERS_DRY)))
        assertEquals(Step.Result, run.next(point(1_300L, progress = 1f, result = true)))
    }

    @Test
    fun `a run that stays without calibration is started again, twice, then given up`() {
        val run = BodyCompositionRun().apply { started(1_000L) }
        fun lost(from: Long): Step {
            var step: Step = Step.Continue
            for (i in 0 until BodyCompositionRun.NO_CALIBRATION_POINTS) step = run.next(point(from + i, STATUS_NO_CALIBRATION))
            return step
        }
        assertEquals(Step.Restart, lost(1_000L))
        run.started(2_000L)
        // A point the run before left behind is not counted.
        assertEquals(Step.Continue, run.next(point(1_500L, STATUS_NO_CALIBRATION)))
        assertEquals(Step.Restart, lost(2_000L))
        run.started(3_000L)
        assertEquals(Step.NoCalibration, lost(3_000L))
    }

    @Test
    fun `once calibrated, a later point without it is let pass`() {
        val run = BodyCompositionRun().apply { started(1_000L) }
        run.next(point(1_000L))
        repeat(20) { assertEquals(Step.Continue, run.next(point(1_001L + it, STATUS_NO_CALIBRATION))) }
    }
}
