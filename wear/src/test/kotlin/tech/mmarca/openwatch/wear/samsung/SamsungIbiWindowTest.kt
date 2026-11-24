package tech.mmarca.openwatch.wear.samsung

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wear.HrvRmssd

class SamsungIbiWindowTest {

    private val t0 = 1_794_024_000_000L

    private fun point(at: Long, vararg intervals: Int, flagged: Set<Int> = emptySet()) =
        IbiPoint(at, intervals, IntArray(intervals.size) { if (it in flagged) -1 else 0 })

    private fun List<Double>.str() = joinToString { if (it.isNaN()) "NaN" else it.toInt().toString() }

    @Test
    fun `intervals are timed back from their point and cut to the window`() {
        // Ends at t0+1000, t0+2000, t0+3000.
        val p = point(t0 + 3000, 1000, 1000, 1000)
        assertEquals("1000, 1000", SamsungIbiWindow.intervals(listOf(p), t0 + 1500, t0 + 3000).toList().str())
        assertEquals("1000", SamsungIbiWindow.intervals(listOf(p), t0, t0 + 1000).toList().str())
    }

    @Test
    fun `a flagged interval is NaN`() {
        val p = point(t0 + 3000, 1000, 1000, 1000, flagged = setOf(1))
        assertEquals("1000, NaN, 1000", SamsungIbiWindow.intervals(listOf(p), t0, t0 + 3000).toList().str())
    }

    @Test
    fun `adjacent points join, a gap between them is a break`() {
        val a = point(t0 + 2000, 1000, 1000)
        val b = point(t0 + 4000, 1000, 1000)
        assertEquals("1000, 1000, 1000, 1000", SamsungIbiWindow.intervals(listOf(a, b), t0, t0 + 10_000).toList().str())
        val late = point(t0 + 9000, 1000, 1000)
        assertEquals("1000, 1000, NaN, 1000, 1000", SamsungIbiWindow.intervals(listOf(a, late), t0, t0 + 10_000).toList().str())
    }

    @Test
    fun `points arriving out of order are put in time order`() {
        val a = point(t0 + 2000, 1000, 1000)
        val b = point(t0 + 4000, 900, 1100)
        assertEquals("1000, 1000, 900, 1100", SamsungIbiWindow.intervals(listOf(b, a), t0, t0 + 10_000).toList().str())
    }

    @Test
    fun `a history batch before the window is left out`() {
        val history = point(t0, *IntArray(500) { 1000 })
        val inside = point(t0 + 3000, 1000, 1000, 1000)
        assertEquals(3, SamsungIbiWindow.intervals(listOf(history, inside), t0 + 1, t0 + 3000).size)
    }

    @Test
    fun `a still window of platform intervals gives an RMSSD, a mostly flagged one does not`() {
        val n = 300
        val alternating = IntArray(n) { if (it % 2 == 0) 1000 else 1040 }
        val clean = point(t0 + alternating.sum(), *alternating)
        val result = HrvRmssd.ofIntervals(SamsungIbiWindow.intervals(listOf(clean), t0, t0 + alternating.sum()), WINDOW)
        assertNotNull(result)
        assertEquals(40.0, result!!.rmssdMillis, 1e-9)
        assertTrue(result.beatCount >= n - 1)

        val poor = point(t0 + alternating.sum(), *alternating, flagged = (0 until n).filter { it % 3 != 0 }.toSet())
        assertNull(HrvRmssd.ofIntervals(SamsungIbiWindow.intervals(listOf(poor), t0, t0 + alternating.sum()), WINDOW))
    }

    @Test
    fun `the platform's share of a night window passes at its own coverage only`() {
        // 110 intervals of about a second: what the platform gave a five-minute window on 2026-11-08/09.
        val partial = IntArray(110) { if (it % 2 == 0) 1100 else 1140 }
        val intervals = SamsungIbiWindow.intervals(listOf(point(t0 + partial.sum(), *partial)), t0, t0 + partial.sum())
        assertNull(HrvRmssd.ofIntervals(intervals, WINDOW))
        assertNotNull(HrvRmssd.ofIntervals(intervals, WINDOW, minCoverage = 0.3))
    }

    private companion object {
        const val WINDOW = 5.0 * 60 * 1000
    }

    /** A batch of [seconds] points a second apart from [from], every interval on the point at [carrier]. */
    private fun batch(from: Long, seconds: Int, carrier: Int, vararg intervals: Int) =
        List(seconds) { if (it == carrier) point(from + it * 1000L, *intervals) else point(from + it * 1000L) }

    @Test
    fun `a flushed batch's intervals are timed back from its end`() {
        // Five minutes of points; 299 beats a second apart, all on the first point.
        val flushed = batch(t0, 300, carrier = 0, *IntArray(299) { 1000 })
        // As it came, they all fall before the batch, so its own window is empty.
        assertEquals(1, SamsungIbiWindow.intervals(flushed, t0, t0 + 299_000).size)
        val placed = SamsungIbiWindow.placeLump(flushed)
        assertEquals(299, SamsungIbiWindow.intervals(placed, t0, t0 + 299_000).size)
        assertEquals(300, placed.size)
        assertTrue(placed.first().intervalsMillis.isEmpty())
    }

    @Test
    fun `a batch with its intervals spread over the points is left as it came`() {
        val spread = List(300) { point(t0 + it * 1000L, 1000) }
        assertTrue(SamsungIbiWindow.placeLump(spread) === spread)
        assertNull(SamsungIbiWindow.lumpCarrier(spread))
    }

    @Test
    fun `the history one point brings at a start is left as it came`() {
        val history = listOf(point(t0, *IntArray(535) { 1000 }))
        assertTrue(SamsungIbiWindow.placeLump(history) === history)
        assertNull(SamsungIbiWindow.lumpCarrier(history))
    }

    @Test
    fun `a lump that does not add up to its batch is left as it came`() {
        // Beats are missing: 200 seconds of intervals for 300 seconds of points.
        val short = batch(t0, 300, carrier = 0, *IntArray(200) { 1000 })
        assertTrue(SamsungIbiWindow.placeLump(short) === short)
        assertNotNull(SamsungIbiWindow.lumpCarrier(short))
        // History from before the batch: ten minutes of intervals on five of points.
        val long = batch(t0, 300, carrier = 0, *IntArray(600) { 1000 })
        assertTrue(SamsungIbiWindow.placeLump(long) === long)
    }

    @Test
    fun `a lump keeps its flags and the points keep their rates`() {
        val points = List(60) {
            IbiPoint(t0 + it * 1000L, if (it == 0) IntArray(59) { 1000 } else IntArray(0), if (it == 0) IntArray(59) { i -> if (i == 3) -1 else 0 } else IntArray(0), bpm = 50 + it, heartRateStatus = 1)
        }
        val placed = SamsungIbiWindow.placeLump(points)
        assertEquals(points.map { it.bpm }, placed.map { it.bpm })
        assertEquals(1, SamsungIbiWindow.intervals(placed, t0, t0 + 59_000).count { it.isNaN() })
    }
}
