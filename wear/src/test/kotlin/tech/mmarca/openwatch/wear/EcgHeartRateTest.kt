package tech.mmarca.openwatch.wear

import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EcgHeartRateTest {

    /**
     * A trace at 500 Hz: a narrow R wave and a broad T wave per beat, on a
     * wandering baseline with mains hum and noise.
     */
    private fun ecg(seconds: Int, beatMillis: Double, hum: Double = 0.05, noise: Double = 0.02): FloatArray =
        ecgAt(seconds, DoubleArray((seconds * 1000 / beatMillis).toInt() + 2) { 100 + it * beatMillis }, pWave = false, hum, noise)

    /** The same with the R waves at [rTimes]; a P wave 160 ms before each when [pWave]. */
    private fun ecgAt(seconds: Int, rTimes: DoubleArray, pWave: Boolean, hum: Double = 0.05, noise: Double = 0.02): FloatArray {
        val random = Random(3)
        return FloatArray(seconds * 500) { k ->
            val t = k * 2.0
            var v = 0.3 * sin(2 * PI * t / 4000) + hum * sin(2 * PI * 50 * t / 1000) + noise * random.nextGaussian()
            for (r in rTimes) {
                if (r < t - 600 || r > t + 400) continue
                v += 1.2 * exp(-sq((t - r) / 8.0))
                v += 0.3 * exp(-sq((t - r - 250) / 50.0))
                if (pWave) v += 0.15 * exp(-sq((t - r + 160) / 25.0))
            }
            v.toFloat()
        }
    }

    private fun steady(seconds: Int, beatMillis: Double) = DoubleArray((seconds * 1000 / beatMillis).toInt() + 2) { 100 + it * beatMillis }

    private fun sq(x: Double) = x * x

    @Test
    fun `a steady trace gives its rate`() {
        for (bpm in listOf(45, 60, 75, 110, 160)) {
            val result = EcgHeartRate.of(ecg(seconds = 30, beatMillis = 60_000.0 / bpm))
            assertNotNull("at $bpm", result)
            assertEquals(bpm.toDouble(), result!!.bpm.toDouble(), 1.0)
        }
    }

    @Test
    fun `the broad T wave is not counted as a beat`() {
        val result = EcgHeartRate.of(ecg(seconds = 30, beatMillis = 1000.0))!!
        assertEquals(30.0, result.beatCount.toDouble(), 1.0)
    }

    @Test
    fun `noise alone and a short trace give no rate`() {
        val random = Random(5)
        assertNull(EcgHeartRate.of(FloatArray(15_000) { (0.05 * random.nextGaussian()).toFloat() }))
        assertNull(EcgHeartRate.of(FloatArray(15_000)))
        assertNull(EcgHeartRate.of(ecg(seconds = 5, beatMillis = 1000.0)))
    }

    @Test
    fun `even beats with a P wave read as sinus-like, without one as regular`() {
        for (bpm in listOf(55, 70, 90)) {
            val times = steady(30, 60_000.0 / bpm)
            val with = EcgHeartRate.of(ecgAt(30, times, pWave = true))!!
            assertEquals("at $bpm", EcgHeartRate.Rhythm.SINUS_LIKE, with.rhythm)
            val without = EcgHeartRate.of(ecgAt(30, times, pWave = false))!!
            assertEquals("at $bpm", EcgHeartRate.Rhythm.REGULAR, without.rhythm)
        }
    }

    @Test
    fun `beats that breathe slowly stay even, beats without a pattern read as irregular`() {
        // Twelve breaths a minute swing the distance by a twentieth, slowly. A much stronger swing reads as unclear.
        var t = 100.0
        val breathing = DoubleArray(40) { val at = t; t += 900 + 45 * sin(2 * PI * at / 5000); at }
        assertEquals(EcgHeartRate.Rhythm.SINUS_LIKE, EcgHeartRate.of(ecgAt(30, breathing, pWave = true))!!.rhythm)

        val random = Random(9)
        t = 100.0
        val scattered = DoubleArray(60) { val at = t; t += 500 + random.nextInt(600); at }
        val result = EcgHeartRate.of(ecgAt(30, scattered, pWave = false))!!
        assertEquals(EcgHeartRate.Rhythm.IRREGULAR, result.rhythm)
        assertEquals(false, result.pWave)
    }

    @Test
    fun `a fast even rhythm is regular, with no word on its P wave`() {
        val result = EcgHeartRate.of(ecgAt(30, steady(30, 60_000.0 / 150), pWave = true))!!
        assertEquals(EcgHeartRate.Rhythm.REGULAR, result.rhythm)
    }
}
