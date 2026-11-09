package tech.mmarca.openwatch.wear.features.metric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateZonesTest {

    @Test
    fun zone_followsTheAcsmSharesOfTheMaximum() {
        val basis = ZoneBasis(200)
        assertEquals(0, heartRateZone(100.0, basis))
        assertEquals(1, heartRateZone(114.0, basis))
        assertEquals(2, heartRateZone(128.0, basis))
        assertEquals(3, heartRateZone(154.0, basis))
        assertEquals(4, heartRateZone(192.0, basis))
        assertEquals(4, heartRateZone(230.0, basis))
    }

    @Test
    fun withARestingRate_theZonesSitOnTheAcsmSharesOfTheReserve() {
        val basis = ZoneBasis(maxBpm = 190, restingBpm = 50)
        // Reserve 140: light from 50 + 0.30 * 140 = 92, near-maximal from 50 + 0.90 * 140 = 176.
        assertNull(heartRateZone(49.0, basis))
        assertEquals(0, heartRateZone(91.0, basis))
        assertEquals(1, heartRateZone(92.0, basis))
        assertEquals(2, heartRateZone(106.0, basis))
        assertEquals(3, heartRateZone(134.0, basis))
        assertEquals(4, heartRateZone(176.0, basis))
    }

    @Test
    fun shares_countSamplesPerZone_andIgnoreThoseBelowRest() {
        val shares = heartRateZoneShares(listOf(40.0, 80.0, 100.0, 140.0), ZoneBasis(maxBpm = 190, restingBpm = 50))
        assertEquals(listOf(0.25f, 0.25f, 0f, 0.25f, 0f), shares)
    }

    @Test
    fun progress_runsFromRestToMaximum() {
        val basis = ZoneBasis(maxBpm = 190, restingBpm = 50)
        assertEquals(0f, heartRateZoneProgress(50.0, basis), 0f)
        assertEquals(0.5f, heartRateZoneProgress(120.0, basis), 1e-6f)
        assertEquals(1f, heartRateZoneProgress(220.0, basis), 0f)
    }
}
