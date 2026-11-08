package tech.mmarca.openwatch.wearlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearLinkWorkoutLinesTest {

    private val t = 1_700_000_000_000L

    @Test fun `a header round-trips with and without its optional totals`() {
        val full = WearLinkWorkout(t, t + 60_000, 56, 4000, 3210, 420)
        assertEquals("WO 1700000000000 1700000060000 56 4000 3210 420", WearLinkWorkoutLines.formatHeader(full))
        assertEquals(full, WearLinkWorkoutLines.parseHeader(WearLinkWorkoutLines.formatHeader(full)))

        val bare = WearLinkWorkout(t, t + 60_000, 70, null, null, null)
        assertEquals("WO 1700000000000 1700000060000 70 - - -", WearLinkWorkoutLines.formatHeader(bare))
        assertEquals(bare, WearLinkWorkoutLines.parseHeader(WearLinkWorkoutLines.formatHeader(bare)))
    }

    @Test fun `a header that cannot be a workout is refused`() {
        assertNull(WearLinkWorkoutLines.parseHeader("WO 1700000000000 1700000000000 56 - - -"))
        assertNull(WearLinkWorkoutLines.parseHeader("WO 1700000060000 1700000000000 56 - - -"))
        assertNull(WearLinkWorkoutLines.parseHeader("WO 1700000000000 ${t + WearLinkWorkoutLines.MAX_DURATION_MILLIS + 1} 56 - - -"))
        assertNull(WearLinkWorkoutLines.parseHeader("WO 0 60000 56 - - -"))
        assertNull(WearLinkWorkoutLines.parseHeader("WO 1700000000000 1700000060000 56 -1 - -"))
        assertNull(WearLinkWorkoutLines.parseHeader("WO 1700000000000 1700000060000 56 ${WearLinkWorkoutLines.MAX_STEPS + 1} - -"))
        assertNull(WearLinkWorkoutLines.parseHeader("WO 1700000000000 1700000060000 56 - - "))
        assertNull(WearLinkWorkoutLines.parseHeader("HV 1700000000000 1700000060000 56 - - -"))
    }

    @Test fun `a pause round-trips and an empty one is refused`() {
        val pause = WearLinkWorkout.Pause(t, t + 5_000)
        assertEquals(pause, WearLinkWorkoutLines.parsePause(WearLinkWorkoutLines.formatPause(pause)))
        assertNull(WearLinkWorkoutLines.parsePause("WP 1700000005000 1700000000000"))
        assertNull(WearLinkWorkoutLines.parsePause("WP 1700000000000"))
    }

    @Test fun `a route point round-trips, signed altitude and missing accuracy included`() {
        val point = WearLinkWorkout.RoutePoint(t, -337_000_000, 1_512_000_000, -42, null)
        assertEquals("WR 1700000000000 -337000000 1512000000 -42 -", WearLinkWorkoutLines.formatPoint(point))
        assertEquals(point, WearLinkWorkoutLines.parsePoint(WearLinkWorkoutLines.formatPoint(point)))
    }

    @Test fun `a point off the globe or without a time is refused`() {
        assertNull(WearLinkWorkoutLines.parsePoint("WR 1700000000000 900000001 0 - -"))
        assertNull(WearLinkWorkoutLines.parsePoint("WR 1700000000000 0 1800000001 - -"))
        assertNull(WearLinkWorkoutLines.parsePoint("WR 0 0 0 - -"))
        assertNull(WearLinkWorkoutLines.parsePoint("WR 1700000000000 0 0 - -5"))
        assertNull(WearLinkWorkoutLines.parsePoint("WR 1700000000000 0 0 -"))
    }

    @Test fun `every line of the longest point fits the line limit`() {
        val point = WearLinkWorkout.RoutePoint(Long.MAX_VALUE, -900_000_000, -1_800_000_000, -100_000, 100_000)
        assertTrue(WearLinkWorkoutLines.formatPoint(point).length < WearLinkProtocol.MAX_LINE_LENGTH)
    }

    @Test fun `lines come header first, then pauses, then points, and stop at the caps`() {
        val route = List(WearLinkWorkoutLines.MAX_POINTS + 10) { WearLinkWorkout.RoutePoint(t + it, 0, 0, null, null) }
        val workout = WearLinkWorkout(t, t + 60_000, 8, null, 1, null, listOf(WearLinkWorkout.Pause(t + 1, t + 2)), route)

        val lines = WearLinkWorkoutLines.lines(workout).toList()

        assertTrue(lines[0].startsWith("WO "))
        assertTrue(lines[1].startsWith("WP "))
        assertTrue(lines[2].startsWith("WR "))
        assertEquals(2 + WearLinkWorkoutLines.MAX_POINTS, lines.size)
    }
}
