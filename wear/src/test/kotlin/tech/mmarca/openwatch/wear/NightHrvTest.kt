package tech.mmarca.openwatch.wear

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightHrvTest {

    /** Beat times in ms from intervals that alternate around 1000 ms by +-[swing] with some wobble. */
    private fun beatTimes(count: Int, swing: Double, seed: Long = 1): DoubleArray {
        val random = Random(seed)
        val times = DoubleArray(count)
        var t = 700.0
        for (i in 0 until count) {
            times[i] = t
            val wobble = random.nextGaussian() * swing * 0.5
            t += 1000.0 + (if (i % 2 == 0) swing else -swing) + wobble
        }
        return times
    }

    private fun trueRmssd(beats: DoubleArray): Double {
        val ibi = DoubleArray(beats.size - 1) { beats[it + 1] - beats[it] }
        return sqrt((0 until ibi.size - 1).map { (ibi[it + 1] - ibi[it]).let { d -> d * d } }.average())
    }

    /**
     * A pulse is a fast systolic bump and a small later one, on a drifting
     * baseline with a DC level like the sensor's, sampled at 25 Hz with
     * steady timing.
     */
    private fun ppg(beats: DoubleArray, durationMillis: Long, noise: Double = 0.0, seed: Long = 2): Pair<DoubleArray, DoubleArray> {
        val random = Random(seed)
        val count = (durationMillis / 40).toInt()
        val times = DoubleArray(count) { it * 40.0 }
        val values = DoubleArray(count) { k ->
            val t = times[k]
            var v = 2_100_000.0 + 3_000 * sin(2 * PI * t / 1000 / 20) + noise * random.nextGaussian()
            for (b in beats) {
                if (abs(t - b) > 1500) continue
                v += 1_000 * exp(-sq((t - b) / 110.0)) + 250 * exp(-sq((t - b - 330) / 130.0))
            }
            v
        }
        return times to values
    }

    private fun sq(x: Double) = x * x

    @Test
    fun `known beat times give their RMSSD within a few milliseconds`() {
        val beats = beatTimes(330, swing = 25.0)
        val (times, values) = ppg(beats, 5L * 60 * 1000)
        val result = NightHrv.analyze(times, values)
        assertNotNull(result)
        val expected = trueRmssd(beats.filter { it < 5L * 60 * 1000 - 1500 }.toDoubleArray())
        assertEquals(expected, result!!.rmssdMillis, 6.0)
        assertTrue("beats ${result.beatCount}", result.beatCount in 200..320)
    }

    @Test
    fun `beat times are recovered finer than one sample`() {
        val beats = beatTimes(60, swing = 20.0)
        val (times, values) = ppg(beats, 60_000)
        val found = PpgBeats.detect(times, values)!!
        for (beat in found) {
            val nearest = beats.minByOrNull { abs(it - beat) }!!
            assertTrue("off by ${beat - nearest}", abs(beat - nearest) < 8.0)
        }
        assertTrue(found.size > 40)
    }

    @Test
    fun `a calm rhythm gives a small RMSSD and a livelier one a larger RMSSD`() {
        val calm = beatTimes(330, swing = 8.0)
        val lively = beatTimes(330, swing = 45.0)
        val (ct, cv) = ppg(calm, 5L * 60 * 1000)
        val (lt, lv) = ppg(lively, 5L * 60 * 1000)
        assertTrue(NightHrv.analyze(ct, cv)!!.rmssdMillis < NightHrv.analyze(lt, lv)!!.rmssdMillis)
    }

    @Test
    fun `noise on the signal does not push the RMSSD past a physiological range`() {
        val beats = beatTimes(330, swing = 25.0)
        val (times, values) = ppg(beats, 5L * 60 * 1000, noise = 60.0)
        val result = NightHrv.analyze(times, values)
        assertNotNull(result)
        assertTrue("rmssd ${result!!.rmssdMillis}", result.rmssdMillis < 80)
    }

    @Test
    fun `a burst of motion is rejected and leaves the RMSSD alone`() {
        val beats = beatTimes(330, swing = 25.0)
        val (times, values) = ppg(beats, 5L * 60 * 1000)
        val clean = NightHrv.analyze(times, values)!!
        val random = Random(9)
        // Ten seconds of large random swings.
        for (k in 3000 until 3250) values[k] += random.nextGaussian() * 4_000
        val hit = NightHrv.analyze(times, values)
        assertNotNull(hit)
        assertEquals(clean.rmssdMillis, hit!!.rmssdMillis, 10.0)
    }

    @Test
    fun `a missed beat does not become a huge step`() {
        val beats = beatTimes(330, swing = 25.0).toMutableList().also { it.removeAt(100) }.toDoubleArray()
        val result = HrvRmssd.of(beats)!!
        assertTrue("rmssd ${result.rmssdMillis}", result.rmssdMillis < 60)
    }

    @Test
    fun `too few clean beats give nothing`() {
        assertNull(HrvRmssd.of(beatTimes(60, swing = 25.0)))
        val random = Random(5)
        val chaos = DoubleArray(300).also { var t = 0.0; for (i in it.indices) { it[i] = t; t += 300 + random.nextDouble() * 1500 } }
        assertNull(HrvRmssd.of(chaos))
    }

    @Test
    fun `a hole in the samples gives nothing`() {
        val beats = beatTimes(330, swing = 25.0)
        val (times, values) = ppg(beats, 5L * 60 * 1000)
        val shifted = DoubleArray(times.size) { if (it > 2000) times[it] + 400 else times[it] }
        assertNull(NightHrv.analyze(shifted, values))
    }

    @Test
    fun `a signal with no pulse gives nothing`() {
        val random = Random(3)
        val times = DoubleArray(7500) { it * 40.0 }
        val values = DoubleArray(7500) { 2_100_000.0 + random.nextGaussian() * 10 }
        assertNull(NightHrv.analyze(times, values))
    }

    @Test
    fun `identical intervals give zero RMSSD`() {
        val beats = DoubleArray(200) { 700.0 + it * 1000.0 }
        assertEquals(0.0, HrvRmssd.of(beats)!!.rmssdMillis, 1e-9)
    }
}
