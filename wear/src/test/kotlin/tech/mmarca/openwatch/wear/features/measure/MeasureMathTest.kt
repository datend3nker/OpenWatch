package tech.mmarca.openwatch.wear.features.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeasureMathTest {

    @Test
    fun settledHeartRate_needsEnoughValidReadings() {
        assertNull(settledHeartRate(List(HeartRateReadingsNeeded - 1) { 70.0 }))
        // Zero means "no signal" and does not count.
        assertNull(settledHeartRate(List(HeartRateReadingsNeeded - 1) { 70.0 } + 0.0))
    }

    @Test
    fun settledHeartRate_isMedianOfLastFive_soOneStrayReadingDoesNotMoveIt() {
        val readings = listOf(90.0, 88.0, 85.0, 72.0, 71.0, 140.0, 70.0, 72.0)
        assertEquals(72.0, settledHeartRate(readings)!!, 0.0)
    }

    @Test
    fun rrIntervals_areMillisecondsBetweenBeats() {
        val beats = listOf(0L, 800_000_000L, 1_650_000_000L)
        assertEquals(listOf(800.0, 850.0), rrIntervalsMillis(beats))
    }

    /** Beat timestamps in nanoseconds from intervals in milliseconds. */
    private fun beatsOf(intervals: List<Double>): List<Long> =
        intervals.runningFold(0.0) { t, rr -> t + rr }.map { (it * 1_000_000).toLong() }

    private fun alternating(count: Int) = List(count) { if (it % 2 == 0) 800.0 else 840.0 }

    @Test
    fun rmssd_ofAlternatingIntervals_isTheirDifference() {
        assertEquals(40.0, rmssd(beatsOf(alternating(HrvIntervalsNeeded + 1)), HrvIntervalsNeeded)!!, 1e-6)
    }

    @Test
    fun rmssd_dropsAnOutlierThatComesFirst() {
        // 1900 ms is inside the allowed range; it must not make every later 800 ms beat look ectopic.
        val rr = listOf(1_900.0) + alternating(60)
        assertEquals(40.0, rmssd(beatsOf(rr), HrvIntervalsNeeded)!!, 1e-6)
    }

    @Test
    fun rmssd_doesNotJoinTheTwoSidesOfARemovedBeat() {
        // One missed beat gives a 1600 ms interval; the pair across it must not count as a step.
        // 39 steps of 40 ms before it, 39 steps of 0 ms after it; the 840 to 760 jump across it would add 80.
        val rr = alternating(40) + 1_600.0 + List(40) { 760.0 }
        assertEquals(40.0 / Math.sqrt(2.0), rmssd(beatsOf(rr), HrvIntervalsNeeded)!!, 0.01)
    }

    @Test
    fun rmssd_withTooFewBeats_isNull() {
        assertNull(rmssd(beatsOf(alternating(HrvIntervalsNeeded - 5)), HrvIntervalsNeeded))
    }
}
