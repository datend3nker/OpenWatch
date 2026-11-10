package tech.mmarca.openwatch.wear.ui.ambient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientTest {

    @Test
    fun `the normal look keeps everything`() {
        val look = ambientLook(ambient = false)
        assertTrue(look.animate && look.showProgressArc && look.showSecondaryValues && look.showControls && look.coloured)
    }

    @Test
    fun `the ambient look drops motion, arcs, extras and colour`() {
        val look = ambientLook(ambient = true)
        assertFalse(look.animate)
        assertFalse(look.showProgressArc)
        assertFalse(look.showSecondaryValues)
        assertFalse(look.showControls)
        assertFalse(look.coloured)
    }

    @Test
    fun `ambient time shows whole minutes as hours and minutes`() {
        assertEquals("0:00", ambientElapsedText(0))
        assertEquals("0:00", ambientElapsedText(59))
        assertEquals("0:01", ambientElapsedText(60))
        assertEquals("0:59", ambientElapsedText(3_599))
        assertEquals("1:00", ambientElapsedText(3_600))
        assertEquals("2:05", ambientElapsedText(7_559))
        assertEquals("0:00", ambientElapsedText(-5))
    }
}
