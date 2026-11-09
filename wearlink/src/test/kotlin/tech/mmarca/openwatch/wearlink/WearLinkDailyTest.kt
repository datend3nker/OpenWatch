package tech.mmarca.openwatch.wearlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearLinkDailyTest {

    @Test
    fun `a day round-trips, missing values included`() {
        val day = DaySummary(20_401, sleepMinutes = 432, awakeMinutes = 20, remMinutes = 90, lightMinutes = 250, deepMinutes = 72, restingBpm = 52)
        val line = WearLinkDaily.formatPutDay(day)
        assertEquals("PUT_DAY 20401 432 20 90 250 72 52 - -", line)
        assertEquals(day, WearLinkDaily.parsePutDay(line))
        assertEquals(WearLinkProtocol.Request.PutDay(day), WearLinkProtocol.parseRequest(line))
    }

    @Test
    fun `a day with an impossible value is refused`() {
        assertNull(WearLinkDaily.parsePutDay("PUT_DAY 20401 2000 - - - - - - -"))
        assertNull(WearLinkDaily.parsePutDay("PUT_DAY 20401 -5 - - - - - - -"))
        assertNull(WearLinkDaily.parsePutDay("PUT_DAY 20401 432 - - - -"))
    }

    @Test
    fun `an entry round-trips`() {
        val water = WatchEntry(1_794_000_000_000, WearLinkDaily.WATER, 250.0)
        val pressure = WatchEntry(1_794_000_000_000, WearLinkDaily.BLOOD_PRESSURE, 120.0, 80.0)
        assertEquals(water, WearLinkDaily.parseEntry(WearLinkDaily.formatEntry(water)))
        assertEquals(pressure, WearLinkDaily.parseEntry(WearLinkDaily.formatEntry(pressure)))
    }

    @Test
    fun `an entry of an unknown type or value is refused`() {
        assertNull(WearLinkDaily.parseEntry("LG 1794000000000 coffee 1.0 -"))
        assertNull(WearLinkDaily.parseEntry("LG 1794000000000 water NaN -"))
        assertNull(WearLinkDaily.parseEntry("LG 0 water 250.0 -"))
    }

    @Test
    fun `the entries request parses`() {
        assertEquals(WearLinkProtocol.Request.EntriesSince(5, 10), WearLinkProtocol.parseRequest("LG_SINCE 5 10"))
    }
}
