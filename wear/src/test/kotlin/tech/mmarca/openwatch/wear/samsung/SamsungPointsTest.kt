package tech.mmarca.openwatch.wear.samsung

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wear.BodyCompositionRun

class SamsungPointsTest {

    private fun raw(vararg ints: Int, floats: FloatArray = FloatArray(ints.size)) = RawPoint(1_794_100_000_000L, ints, floats)

    @Test
    fun `an SpO2 reading has a value only once completed with a plausible one`() {
        // As the Galaxy Watch7 sent them: status, value, heart rate, accuracy.
        val done = raw(2, 96, 51, 0).toSpo2Reading()
        assertEquals(96, done.percent)
        assertEquals(51, done.bpm)
        assertNull(raw(0, 0, 0, 0).toSpo2Reading().percent)
        assertNull(raw(-6, 0, 0, 0).toSpo2Reading().percent)
        assertNull(raw(2, 40, 51, 0).toSpo2Reading().percent)
        assertTrue(raw(-4, 0, 0, 0).toSpo2Reading().moving)
        assertFalse(done.moving)
    }

    @Test
    fun `a heart rate point carries its rate and its interval pairs`() {
        val point = raw(52, 33937, 1, 2, 1169, 0, 1125, -1).toHeartRatePoint()
        assertTrue(point.hasHeartRate)
        assertEquals(52, point.bpm)
        assertEquals(listOf(1169, 1125), point.intervalsMillis.toList())
        assertEquals(listOf(0, -1), point.statuses.toList())
    }

    @Test
    fun `a skin reading takes the two temperatures from the floats and is measured at status 0`() {
        val reading = raw(0, 0, 0, floats = floatArrayOf(33.9f, 22.1f, 0f)).toSkinReading()
        assertEquals(33.9f, reading.skinCelsius, 1e-6f)
        assertEquals(22.1f, reading.ambientCelsius, 1e-6f)
        assertTrue(reading.measured)
        assertFalse(raw(0, 0, -1).toSkinReading().measured)
    }

    @Test
    fun `an ECG point carries the electrodes' state only where the platform sent it`() {
        // The first point of a group: (adc, mV), ppg, lead-off, sequence, the two thresholds.
        val first = raw(1200, 5000, 0, 7, 9, 9, floats = floatArrayOf(0.42f, 0f, 0f, 0f, 1.5f, -1.5f)).toEcgPoint()
        assertTrue(first.hasLeadState)
        assertEquals(EcgPoint.LEAD_ON, first.leadOff)
        assertEquals(7, first.sequence)
        assertEquals(0.42f, first.millivolts, 1e-6f)
        val later = raw(1180, -1, -1, -1, -1, -1, floats = floatArrayOf(0.4f, 0f, 0f, 0f, -1f, -1f)).toEcgPoint()
        assertFalse(later.hasLeadState)
        assertTrue(raw(1180, -1, 5, 8, -1, -1).toEcgPoint().let { it.hasLeadState && it.leadOff != EcgPoint.LEAD_ON })
    }

    @Test
    fun `a body composition point is a result only at full progress with a plausible value`() {
        fun bia(status: Int, bodyFat: Float, progress: Float) = RawPoint(
            1_794_100_000_000L,
            IntArray(12).also { it[0] = status },
            floatArrayOf(0f, bodyFat, 14.1f, 41.0f, 44.2f, 31.5f, 1650f, 80.4f, 57.9f, progress, 480f, 5.1f),
        ).toBiaPoint()

        val done = bia(BodyCompositionRun.STATUS_OK, 19.6f, 1f)
        assertTrue(done.isResult)
        assertEquals(31.5f, done.skeletalMuscleMassKg, 1e-6f)
        assertEquals(41.0f, done.totalBodyWaterKg, 1e-6f)
        assertEquals(1650f, done.basalMetabolicRateKcal, 1e-6f)
        assertFalse(bia(BodyCompositionRun.STATUS_OK, 19.6f, 0.6f).isResult)
        assertFalse(bia(BodyCompositionRun.STATUS_FINGERS_DETACHED, 0f, 0f).isResult)
        assertFalse(bia(BodyCompositionRun.STATUS_OK, 0f, 1f).isResult)
    }

    @Test
    fun `a PPG point reads green, red and infrared from their pairs`() {
        val point = raw(2_100_000, 0, 31, -1, 47, -1).toPpgPoint()
        assertEquals(2_100_000, point.green)
        assertEquals(0, point.greenStatus)
        assertEquals(31, point.red)
        assertEquals(47, point.infrared)
    }
}
