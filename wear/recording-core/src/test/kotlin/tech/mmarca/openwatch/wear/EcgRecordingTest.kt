package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcgRecordingTest {

    @Test
    fun `only samples with both electrodes touched count, and a lifted finger starts over`() {
        val recording = EcgRecording(seconds = 1)
        recording.add(0.1f, touching = null)
        assertEquals("nothing before the contact", 0f, recording.recorded)
        recording.add(0.2f, touching = true)
        repeat(99) { recording.add(0.3f, touching = null) }
        assertEquals(0.2f, recording.recorded, 1e-6f)
        recording.add(0.4f, touching = false)
        assertEquals("lifted", 0f, recording.recorded)
        assertFalse(recording.contact)
    }

    @Test
    fun `it completes at its length and keeps no more`() {
        val recording = EcgRecording(seconds = 1)
        recording.add(Float.NaN, touching = true)
        repeat(600) { recording.add(it.toFloat(), touching = null) }
        assertTrue(recording.isComplete)
        assertEquals(500, recording.samples().size)
        assertEquals(499f, recording.trace(1).last())
        assertEquals(500, recording.trace(3).size)
    }
}
