package tech.mmarca.openwatch.wearlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateZonesTest {

    @Test
    fun `with a resting rate the zones sit on the ACSM shares of the reserve`() {
        val basis = HeartRateZones.Basis(maxBpm = 190, restingBpm = 50)
        // Reserve 140: light from 50 + 0.30 * 140 = 92, near-maximal from 50 + 0.90 * 140 = 176.
        assertNull(HeartRateZones.zoneOf(49.0, basis))
        assertEquals(0, HeartRateZones.zoneOf(91.0, basis))
        assertEquals(1, HeartRateZones.zoneOf(92.0, basis))
        assertEquals(2, HeartRateZones.zoneOf(106.0, basis))
        assertEquals(3, HeartRateZones.zoneOf(134.0, basis))
        assertEquals(4, HeartRateZones.zoneOf(176.0, basis))
    }

    @Test
    fun `without one they sit on the ACSM shares of the maximum`() {
        val basis = HeartRateZones.Basis(200)
        assertEquals(0, HeartRateZones.zoneOf(100.0, basis))
        assertEquals(1, HeartRateZones.zoneOf(114.0, basis))
        assertEquals(2, HeartRateZones.zoneOf(128.0, basis))
        assertEquals(3, HeartRateZones.zoneOf(154.0, basis))
        assertEquals(4, HeartRateZones.zoneOf(192.0, basis))
    }
}
