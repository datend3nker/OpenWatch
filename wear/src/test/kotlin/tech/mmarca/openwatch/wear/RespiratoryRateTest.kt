package tech.mmarca.openwatch.wear

import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RespiratoryRateTest {

    private val seconds = 300

    /** Beats whose intervals swing with the breath: [swing] ms around 900 ms at [breathsPerMinute]. */
    private fun beats(breathsPerMinute: Double, swing: Double = 40.0, seed: Long = 1): DoubleArray {
        val random = Random(seed)
        val list = ArrayList<Double>()
        var t = 700.0
        while (t < seconds * 1000.0) {
            list += t
            t += 900.0 + swing * sin(2 * PI * breathsPerMinute / 60.0 * t / 1000.0) + random.nextGaussian() * 6
        }
        return list.toDoubleArray()
    }

    /** 25 Hz pulse on a baseline that moves with the breath. */
    private fun ppg(breathsPerMinute: Double, swing: Double = 800.0, beats: DoubleArray = beats(breathsPerMinute)): Pair<DoubleArray, DoubleArray> {
        val count = seconds * 25
        val times = DoubleArray(count) { it * 40.0 }
        val values = DoubleArray(count) { k ->
            val t = times[k]
            var v = 2_100_000.0 + swing * sin(2 * PI * breathsPerMinute / 60.0 * t / 1000.0)
            for (b in beats) {
                if (kotlin.math.abs(t - b) > 600) continue
                v += 1_000 * exp(-((t - b) / 110.0) * ((t - b) / 110.0))
            }
            v
        }
        return times to values
    }

    @Test
    fun `intervals that swing with the breath give the breathing rate`() {
        for (rate in listOf(8.0, 12.0, 15.0, 22.0)) {
            val estimate = RespiratoryRate.fromIntervals(beats(rate))
            assertNotNull("$rate", estimate)
            assertEquals(rate, estimate!!.breathsPerMinute, 0.6)
        }
    }

    @Test
    fun `a baseline that moves with the breath gives the breathing rate`() {
        for (rate in listOf(9.0, 14.0, 20.0)) {
            val (t, v) = ppg(rate)
            val estimate = RespiratoryRate.fromSignal(t, v)
            assertNotNull("$rate", estimate)
            assertEquals(rate, estimate!!.breathsPerMinute, 0.6)
        }
    }

    @Test
    fun `two agreeing routes give their mean`() {
        val b = beats(15.0)
        val (t, v) = ppg(15.0, beats = b)
        val window = RespiratoryRate.of(b, t, v)
        assertEquals(15.0, window.value!!, 0.6)
    }

    @Test
    fun `routes that disagree give no value`() {
        val window = RespiratoryRate.Window(
            RespiratoryRate.Estimate(12.0, 0.6),
            RespiratoryRate.Estimate(19.0, 0.6),
        )
        assertNull(window.value)
    }

    @Test
    fun `steady intervals with no swing give nothing`() {
        val steady = DoubleArray(330) { 700.0 + it * 900.0 }
        assertNull(RespiratoryRate.fromIntervals(steady))
    }

    @Test
    fun `noise alone gives nothing or a weak peak that is refused`() {
        val random = Random(5)
        val times = DoubleArray(seconds * 25) { it * 40.0 }
        val values = DoubleArray(times.size) { 2_100_000.0 + random.nextGaussian() * 500 }
        val estimate = RespiratoryRate.fromSignal(times, values)
        assertTrue(estimate == null || estimate.peakShare >= RespiratoryRate.MIN_PEAK_SHARE)
    }

    @Test
    fun `too few beats give nothing`() {
        assertNull(RespiratoryRate.fromIntervals(beats(15.0).copyOf(50)))
    }

    @Test
    fun `a window shorter than two minutes gives nothing`() {
        val (t, v) = ppg(15.0)
        assertNull(RespiratoryRate.fromSignal(t.copyOf(25 * 90), v.copyOf(25 * 90)))
    }

    @Test
    fun `one route alone counts only with a clear peak`() {
        val clear = RespiratoryRate.Window(RespiratoryRate.Estimate(13.3, 0.66), null)
        assertEquals(13.3, clear.value!!, 1e-9)
        assertEquals(1, clear.routes)
        val weak = RespiratoryRate.Window(RespiratoryRate.Estimate(14.3, 0.35), null)
        assertNull(weak.value)
        assertEquals(0, weak.routes)
    }
}
