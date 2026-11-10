package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WornGateTest {

    private val t0 = 1_791_568_860_000L
    private val delay = WornGate.OFF_DELAY_MILLIS

    @Test
    fun `heart rate runs until something says the watch is off`() {
        val gate = WornGate()
        assertTrue(gate.heartRateWanted(t0))
        assertNull(gate.nextChangeAt())
    }

    @Test
    fun `off the wrist, the heart rate stops after the delay, not before`() {
        val gate = WornGate()
        gate.onWorn(t0, worn = false)
        assertTrue(gate.heartRateWanted(t0 + delay - 1))
        assertFalse(gate.heartRateWanted(t0 + delay))
        assertEquals(t0 + delay, gate.nextChangeAt())
    }

    @Test
    fun `a short false off does not stop it, and back on starts it at once`() {
        val gate = WornGate()
        gate.onWorn(t0, worn = false)
        gate.onWorn(t0 + 5_000, worn = true)
        assertTrue(gate.heartRateWanted(t0 + delay * 10))

        gate.onWorn(t0, worn = false)
        assertFalse(gate.heartRateWanted(t0 + delay))
        gate.onWorn(t0 + delay * 2, worn = true)
        assertTrue(gate.heartRateWanted(t0 + delay * 2))
    }

    @Test
    fun `a second off keeps the first time`() {
        val gate = WornGate()
        gate.onWorn(t0, worn = false)
        gate.onWorn(t0 + 30_000, worn = false)
        assertFalse(gate.heartRateWanted(t0 + delay))
    }

    @Test
    fun `a wake-up off the wrist runs it for one more delay`() {
        val gate = WornGate()
        gate.onWorn(t0, worn = false)
        val woken = t0 + delay * 5
        gate.onWakeUp(woken)
        assertTrue(gate.heartRateWanted(woken))
        assertFalse(gate.heartRateWanted(woken + delay))
        assertEquals(woken + delay, gate.nextChangeAt())
    }

    @Test
    fun `a wake-up while worn changes nothing`() {
        val gate = WornGate()
        gate.onWakeUp(t0)
        assertTrue(gate.heartRateWanted(t0 + delay * 10))
        assertNull(gate.nextChangeAt())
    }

    @Test
    fun `on the charger the heart rate stops at once, worn or not`() {
        val gate = WornGate()
        gate.onCharging(true)
        assertFalse(gate.heartRateWanted(t0))
        assertNull(gate.nextChangeAt())

        // The sensor saying "worn" on the charger changes nothing.
        gate.onWorn(t0 + 1_000, worn = true)
        assertFalse(gate.heartRateWanted(t0 + 1_000))
    }

    @Test
    fun `a wake-up on the charger does not start it`() {
        val gate = WornGate()
        gate.onWorn(t0, worn = false)
        gate.onCharging(true)
        gate.onWakeUp(t0 + delay * 5)
        assertFalse(gate.heartRateWanted(t0 + delay * 5))
    }

    @Test
    fun `off the charger a worn watch measures again, an unworn one gets one more delay`() {
        val worn = WornGate()
        worn.onCharging(true)
        worn.onCharging(false)
        assertTrue(worn.heartRateWanted(t0))

        val unworn = WornGate()
        unworn.onWorn(t0, worn = false)
        unworn.onCharging(true)
        unworn.onCharging(false)
        val pulled = t0 + delay * 5
        unworn.onWakeUp(pulled)
        assertTrue(unworn.heartRateWanted(pulled))
        assertFalse(unworn.heartRateWanted(pulled + delay))
    }
}
