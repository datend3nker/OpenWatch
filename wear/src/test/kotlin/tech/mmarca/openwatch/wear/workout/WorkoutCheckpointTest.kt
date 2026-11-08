package tech.mmarca.openwatch.wear.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WearLinkWorkout

class WorkoutCheckpointTest {

    @Test fun `a bare checkpoint round-trips`() {
        val bare = WorkoutCheckpoint("70", 1_700_000_000_000, 1_700_000_015_000, false, null, null, null, emptyList(), null)

        assertEquals("v1 70 1700000000000 1700000015000 0 - - - - -", bare.encode())
        assertEquals(bare, WorkoutCheckpoint.decode(bare.encode()))
    }

    @Test fun `a full checkpoint round-trips`() {
        val full = WorkoutCheckpoint(
            "56", 1_700_000_000_000, 1_700_000_900_000, true, 3210, 4000, 420,
            listOf(WearLinkWorkout.Pause(1_700_000_100_000, 1_700_000_160_000), WearLinkWorkout.Pause(1_700_000_300_000, 1_700_000_330_000)),
            1_700_000_800_000,
        )

        assertEquals(full, WorkoutCheckpoint.decode(full.encode()))
    }

    @Test fun `text this build cannot read gives none`() {
        assertNull(WorkoutCheckpoint.decode(null))
        assertNull(WorkoutCheckpoint.decode(""))
        assertNull(WorkoutCheckpoint.decode("v2 70 1 2 0 - - - - -"))
        assertNull(WorkoutCheckpoint.decode("v1 70 x 2 0 - - - - -"))
        assertNull(WorkoutCheckpoint.decode("v1 70 1 2 0 - - - - 5"))
        assertNull(WorkoutCheckpoint.decode("v1 70 1 2 0"))
    }
}
