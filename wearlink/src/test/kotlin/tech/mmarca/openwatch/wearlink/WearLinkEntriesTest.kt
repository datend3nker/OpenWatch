package tech.mmarca.openwatch.wearlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WearLinkEntry.Type

class WearLinkEntriesTest {

    private val t = 1_791_616_222_062L

    @Test fun `an entry by hand and a measured one round-trip, with and without a second value`() {
        val water = WearLinkEntry.of(t, Type.WATER, 250.0)
        assertEquals("LG 1791616222062 water 250000 - h", WearLinkEntries.format(water))
        assertEquals(water, WearLinkEntries.parse(WearLinkEntries.format(water)))

        val pressure = WearLinkEntry.of(t, Type.BLOOD_PRESSURE, 121.0, 79.0)
        assertEquals("LG 1791616222062 bp 121000 79000 h", WearLinkEntries.format(pressure))
        assertEquals(pressure, WearLinkEntries.parse(WearLinkEntries.format(pressure)))

        val hrv = WearLinkEntry.of(t, Type.HRV, 42.3, measured = true)
        assertEquals("LG 1791616222062 hrv 42300 - m", WearLinkEntries.format(hrv))
        assertEquals(hrv, WearLinkEntries.parse(WearLinkEntries.format(hrv)))
        assertTrue(WearLinkEntries.parse(WearLinkEntries.format(hrv))!!.measured)
    }

    @Test fun `values are whole thousandths, never a number with an exponent`() {
        val weight = WearLinkEntry.of(t, Type.WEIGHT, 72.4)
        assertEquals(72_400L, weight.value1000)
        assertEquals(72.4, weight.value, 0.0)
        // 0.1 + 0.2 is 0.30000000000000004 as a double; on the wire it is 300.
        assertEquals(300L, WearLinkEntry.of(t, Type.MINDFULNESS, 0.1 + 0.2).value1000)
        assertFalse(WearLinkEntries.format(WearLinkEntry.of(t, Type.WATER, 1e-3)).contains("E"))
    }

    @Test fun `every type keeps its token, which is what the line carries`() {
        assertEquals(
            listOf("water", "weight", "bp", "mindful", "hr", "hrv", "bodyfat", "spo2", "breath", "temp", "glucose", "height", "lean", "bodywater"),
            Type.entries.map { it.token },
        )
        for (type in Type.entries) {
            val entry = WearLinkEntry.of(t, type, 1.0, if (type == Type.BLOOD_PRESSURE) 1.0 else null)
            assertEquals(entry, WearLinkEntries.parse(WearLinkEntries.format(entry)))
        }
    }

    @Test fun `a type this build does not know is skipped, and a line may grow at its end`() {
        assertNull(WearLinkEntries.parse("LG 1791616222062 coffee 5400 - h"))
        assertEquals(Type.BLOOD_GLUCOSE, WearLinkEntries.parse("LG 1791616222062 glucose 5400 - h")?.type)
        assertEquals(
            WearLinkEntry(t, Type.WATER, 250_000, null, false),
            WearLinkEntries.parse("LG 1791616222062 water 250000 - h later fields"),
        )
    }

    @Test fun `a line that cannot be an entry is refused`() {
        assertNull(WearLinkEntries.parse("LG 1791616222062 water 250000 -"))
        assertNull(WearLinkEntries.parse("LG 0 water 250000 - h"))
        assertNull(WearLinkEntries.parse("LG 1791616222062 water 0 - h"))
        assertNull(WearLinkEntries.parse("LG 1791616222062 water -5 - h"))
        assertNull(WearLinkEntries.parse("LG 1791616222062 water 250.0 - h"))
        assertNull(WearLinkEntries.parse("LG 1791616222062 water ${WearLinkEntries.MAX_VALUE1000 + 1} - h"))
        assertNull(WearLinkEntries.parse("LG 1791616222062 water 250000 - x"))
        assertNull(WearLinkEntries.parse("LG 1791616222062 WATER 250000 - h"))
        assertNull(WearLinkEntries.parse("HR 1791616222062 water 250000 - h"))
        // A blood pressure is two values.
        assertNull(WearLinkEntries.parse("LG 1791616222062 bp 121000 - h"))
        assertNull(WearLinkEntries.parse("LG 1791616222062 bp 121000 0 h"))
    }

    @Test fun `the longest entry fits the line limit`() {
        val entry = WearLinkEntry(Long.MAX_VALUE, Type.MINDFULNESS, WearLinkEntries.MAX_VALUE1000, WearLinkEntries.MAX_VALUE1000, true)
        assertTrue(WearLinkEntries.format(entry).length < WearLinkProtocol.MAX_LINE_LENGTH)
    }
}
