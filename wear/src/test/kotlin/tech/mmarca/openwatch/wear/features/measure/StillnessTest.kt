package tech.mmarca.openwatch.wear.features.measure

import kotlin.math.sin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StillnessTest {

    /** Ten readings a second for [seconds], the magnitude from [magnitude] at each reading. */
    private fun window(seconds: Int, magnitude: (Int) -> Double): Pair<LongArray, DoubleArray> {
        val count = seconds * 10
        return LongArray(count) { it * 100L } to DoubleArray(count) { magnitude(it) }
    }

    @Test
    fun `a resting wrist is still`() {
        val (times, magnitudes) = window(180) { 9.81 + 0.02 * sin(it * 0.3) }
        assertFalse(Stillness.moved(times, magnitudes))
    }

    @Test
    fun `an arm that keeps moving spoils the window`() {
        val (times, magnitudes) = window(180) { 9.81 + 2.0 * sin(it * 0.7) }
        assertTrue(Stillness.moved(times, magnitudes))
    }

    @Test
    fun `a short move is allowed, a long one is not`() {
        // 2 blocks of 36 move: under a fifth.
        val brief = window(180) { if (it in 100..199) 9.81 + 3.0 * sin(it * 0.9) else 9.81 }
        assertFalse(Stillness.moved(brief.first, brief.second))

        // 12 blocks of 36 move: a third.
        val long = window(180) { if (it < 600) 9.81 + 3.0 * sin(it * 0.9) else 9.81 }
        assertTrue(Stillness.moved(long.first, long.second))
    }

    @Test
    fun `no readings cannot be judged and count as moved`() {
        assertTrue(Stillness.moved(LongArray(0), DoubleArray(0)))
    }
}
