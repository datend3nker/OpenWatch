package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepHoldTest {

    private val start = 1_800_000_000_000L
    private var next = 0

    private fun minute(
        movement: Float = 0f,
        bpm: Int? = 60,
        screen: Boolean = false,
        steps: Int = 0,
        worn: Boolean = true,
    ) = SleepHold.Minute(start + 60_000L * next++, movement, bpm, screen, steps, worn)

    /** Thirty minutes of the day at a pulse of 75 so the level is known. */
    private fun SleepHold.day(count: Int = 30) = repeat(count) { onMinute(minute(movement = 12f, bpm = 75)) }

    private fun SleepHold.falling(count: Int) = repeat(count) { index -> onMinute(minute(bpm = 70 - index)) }

    @Test
    fun `ten quiet minutes with a falling heart rate start the hold`() {
        val hold = SleepHold()
        hold.day()

        hold.falling(9)
        assertFalse(hold.isHolding)
        hold.falling(1)
        assertTrue(hold.isHolding)
    }

    @Test
    fun `sitting still at a steady heart rate never starts it`() {
        val hold = SleepHold()
        hold.day()

        repeat(120) { hold.onMinute(minute(bpm = 74 + it % 2)) }

        assertFalse(hold.isHolding)
    }

    @Test
    fun `a fall that stays near the waking level does not start it`() {
        val hold = SleepHold()
        hold.day()

        repeat(20) { hold.onMinute(minute(bpm = 75 - minOf(it, 3))) }

        assertFalse(hold.isHolding)
    }

    @Test
    fun `a minute with steps, the screen or movement breaks the quiet`() {
        for (breaker in listOf(minute(steps = 3, bpm = 60), minute(screen = true, bpm = 60), minute(movement = 5f, bpm = 60))) {
            val hold = SleepHold()
            hold.day()
            hold.falling(6)
            hold.onMinute(breaker)
            hold.falling(6)
            assertFalse(hold.isHolding)
        }
    }

    @Test
    fun `without a known waking level it does not start`() {
        val hold = SleepHold()

        hold.day(5)
        hold.falling(12)

        assertFalse(hold.isHolding)
    }

    @Test
    fun `a turn in bed keeps the hold and five waking minutes release it`() {
        val hold = SleepHold()
        hold.day()
        hold.falling(12)
        assertTrue(hold.isHolding)

        repeat(2) { hold.onMinute(minute(movement = 14f, bpm = 62)) }
        hold.onMinute(minute(bpm = 58))
        assertTrue(hold.isHolding)

        repeat(4) { hold.onMinute(minute(screen = true, bpm = 80)) }
        assertTrue(hold.isHolding)
        hold.onMinute(minute(steps = 20, bpm = 85))
        assertFalse(hold.isHolding)
    }

    @Test
    fun `the watch leaving the wrist releases the hold and the evidence starts again`() {
        val hold = SleepHold()
        hold.day()
        hold.falling(12)

        hold.onMinute(minute(worn = false))
        assertFalse(hold.isHolding)

        hold.falling(9)
        assertFalse(hold.isHolding)
    }

    @Test
    fun `the cap releases the hold and a cooldown keeps it from starting again at once`() {
        val config = SleepHold.Config(maxHoldMinutes = 60, cooldownMinutes = 30)
        val hold = SleepHold(config)
        hold.day()
        hold.falling(12)
        assertTrue(hold.isHolding)

        repeat(60) { hold.onMinute(minute(bpm = 55)) }
        assertFalse(hold.isHolding)

        // A steady fall would start a hold, but not before the cooldown is over.
        repeat(25) { hold.onMinute(minute(bpm = 70 - it)) }
        assertFalse(hold.isHolding)
        repeat(15) { hold.onMinute(minute(bpm = 45 - it)) }
        assertTrue(hold.isHolding)
    }

    @Test
    fun `a gap in the minutes drops the evidence`() {
        val hold = SleepHold()
        hold.day()
        hold.falling(6)
        next += 5
        hold.falling(4)

        assertEquals(false, hold.isHolding)
    }
}
