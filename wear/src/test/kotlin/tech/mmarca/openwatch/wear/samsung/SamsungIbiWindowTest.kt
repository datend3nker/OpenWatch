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
        val result = HrvRmssd.ofIntervals(SamsungIbiWindow.intervals(listOf(clean), t0, t0 + alternating.sum()))
        assertNotNull(result)
        assertEquals(40.0, result!!.rmssdMillis, 1e-9)
        assertTrue(result.beatCount >= n - 1)

        val poor = point(t0 + alternating.sum(), *alternating, flagged = (0 until n).filter { it % 3 != 0 }.toSet())
        assertNull(HrvRmssd.ofIntervals(SamsungIbiWindow.intervals(listOf(poor), t0, t0 + alternating.sum())))
    }
}
