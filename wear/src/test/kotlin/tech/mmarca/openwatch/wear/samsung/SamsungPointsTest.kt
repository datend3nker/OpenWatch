package tech.mmarca.openwatch.wear.samsung

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungPointsTest {

    private fun raw(vararg ints: Int, floats: FloatArray = FloatArray(ints.size)) = RawPoint(1_794_100_000_000L, ints, floats)

    @Test
    fun `an SpO2 point is complete only with status 2 and a plausible value`() {
        // As the Galaxy Watch7 sent them: status, value, heart rate, accuracy.
        assertTrue(raw(2, 96, 51, 0).toSpo2Point().isComplete)
        assertFalse(raw(0, 0, 0, 0).toSpo2Point().isComplete)
        assertFalse(raw(-6, 0, 0, 0).toSpo2Point().isComplete)
        assertFalse(raw(2, 40, 51, 0).toSpo2Point().isComplete)
        assertEquals(Spo2Point.STATUS_MOVING, raw(-4, 0, 0, 0).toSpo2Point().status)
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
    fun `a skin point reads the two temperatures from the floats`() {
        val point = raw(0, 0, 0, floats = floatArrayOf(33.9f, 22.1f, 0f)).toSkinPoint()
        assertEquals(33.9f, point.objectCelsius, 1e-6f)
        assertEquals(22.1f, point.ambientCelsius, 1e-6f)
        assertEquals(0, point.status)
    }
}
