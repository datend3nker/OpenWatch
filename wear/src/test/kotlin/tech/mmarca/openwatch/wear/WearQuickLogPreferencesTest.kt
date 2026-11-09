package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wear.features.quicklog.LogValueSpecs
import tech.mmarca.openwatch.wear.features.quicklog.QuickLogItem
import tech.mmarca.openwatch.wear.features.quicklog.QuickLogItem.BLOOD_PRESSURE
import tech.mmarca.openwatch.wear.features.quicklog.QuickLogItem.BREATHE
import tech.mmarca.openwatch.wear.features.quicklog.QuickLogItem.HEIGHT
import tech.mmarca.openwatch.wear.features.quicklog.QuickLogItem.WATER
import tech.mmarca.openwatch.wear.features.quicklog.QuickLogItem.WEIGHT

class WearQuickLogPreferencesTest {

    @Test
    fun decode_nothingStored_returnsAllItemsInTheOldOrder() {
        val values = LogValueSpecs.keys.map { type -> QuickLogItem.entries.first { it.entryType == type } }
        assertEquals(listOf(WATER, WEIGHT, BLOOD_PRESSURE) + values + BREATHE, decodeQuickLogItems(null))
    }

    @Test
    fun everySingleValueTypeHasAnItem() {
        val single = QuickLogItem.entries.filter { it.isSingleValue }.map { it.entryType }
        assertEquals(LogValueSpecs.keys.toList(), single)
    }

    @Test
    fun decode_emptyString_returnsNoItems() {
        assertTrue(decodeQuickLogItems("").isEmpty())
    }

    @Test
    fun encodeThenDecode_keepsSelectionAndOrder() {
        val custom = listOf(BREATHE, WATER, HEIGHT)
        assertEquals(custom, decodeQuickLogItems(encodeQuickLogItems(custom)))
    }

    @Test
    fun decode_dropsUnknownAndDuplicateNames() {
        assertEquals(listOf(WEIGHT, WATER), decodeQuickLogItems("WEIGHT,NOPE,WATER,WEIGHT"))
    }

    @Test
    fun move_down_placesItemAfterTarget() {
        assertEquals(
            listOf(WEIGHT, BLOOD_PRESSURE, WATER, BREATHE),
            listOf(WATER, WEIGHT, BLOOD_PRESSURE, BREATHE).withItemMoved(WATER, BLOOD_PRESSURE),
        )
    }

    @Test
    fun move_up_placesItemBeforeTarget() {
        assertEquals(
            listOf(BREATHE, WATER, WEIGHT, BLOOD_PRESSURE),
            listOf(WATER, WEIGHT, BLOOD_PRESSURE, BREATHE).withItemMoved(BREATHE, WATER),
        )
    }

    @Test
    fun move_ignoresAnItemThatIsNotActive() {
        val items = listOf(WATER, WEIGHT)
        assertEquals(items, items.withItemMoved(BREATHE, WATER))
        assertEquals(items, items.withItemMoved(WATER, BREATHE))
    }
}
