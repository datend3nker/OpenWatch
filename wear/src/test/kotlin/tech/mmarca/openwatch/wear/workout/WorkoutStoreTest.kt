package tech.mmarca.openwatch.wear.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WearLinkWorkout

private class MemoryRows : WorkoutRows {
    val points = sortedMapOf<Long, WearLinkWorkout.RoutePoint>()
    val pauseRows = sortedMapOf<Long, WearLinkWorkout.Pause>()
    val headerRows = sortedMapOf<Long, WearLinkWorkout>()
    var checkpointText: String? = null
    var pruned: Long? = null

    override fun putPoint(point: WearLinkWorkout.RoutePoint) { points.putIfAbsent(point.epochMillis, point) }
    override fun points(fromMillis: Long, toMillis: Long) = points.values.filter { it.epochMillis in fromMillis..toMillis }
    override fun deletePoints(fromMillis: Long, toMillis: Long) { points.keys.removeAll { it in fromMillis..toMillis } }
    override fun putPauses(pauses: List<WearLinkWorkout.Pause>) { pauses.forEach { pauseRows.putIfAbsent(it.startMillis, it) } }
    override fun pauses(fromMillis: Long, toMillis: Long) = pauseRows.values.filter { it.startMillis in fromMillis..toMillis }
    override fun putHeader(workout: WearLinkWorkout) { headerRows.putIfAbsent(workout.startMillis, workout) }
    override fun headers(sinceMillis: Long, limit: Int) = headerRows.values.filter { it.startMillis > sinceMillis }.take(limit)
    override fun checkpoint() = checkpointText
    override fun setCheckpoint(text: String) { checkpointText = text }
    override fun clearCheckpoint() { checkpointText = null }
    override fun prune(nowMillis: Long) { pruned = nowMillis }
}

class WorkoutStoreTest {

    private val t0 = 1_700_000_040_000L
    private val rows = MemoryRows()
    private val store = WorkoutStore(rows)

    private fun point(seconds: Int) = WearLinkWorkout.RoutePoint(t0 + seconds * 1000L, 480_000_000 + seconds, 110_000_000, null, null)

    private fun workout(minutes: Int = 25, pauses: List<WearLinkWorkout.Pause> = emptyList()) =
        WearLinkWorkout(t0, t0 + minutes * 60_000L, 56, 4000, 3210, null, pauses, listOf(point(0), point(5)))

    @Test fun `a finished workout comes back whole with its pauses and its route`() {
        val pause = WearLinkWorkout.Pause(t0 + 60_000, t0 + 90_000)
        val w = workout(pauses = listOf(pause))
        w.route.forEach(store::savePoint)

        assertTrue(store.finish(w, t0 + 2_000_000))

        val page = store.workoutsSince(0, 5)
        assertEquals(listOf(w), page.items)
        assertFalse(page.more)
        assertNull(rows.checkpointText)
        assertEquals(t0 + 2_000_000, rows.pruned)
    }

    @Test fun `a page cuts at the limit and says more remain`() {
        for (n in 0 until 3) {
            store.finish(workout().copy(startMillis = t0 + n * 3_600_000L, endMillis = t0 + n * 3_600_000L + 600_000L, route = emptyList()), t0)
        }

        val first = store.workoutsSince(0, 2)
        assertEquals(2, first.items.size)
        assertTrue(first.more)
        val rest = store.workoutsSince(first.items.last().startMillis, 2)
        assertEquals(1, rest.items.size)
        assertFalse(rest.more)
    }

    @Test fun `a workout shorter than half a minute is dropped with its route`() {
        val slip = workout().copy(endMillis = t0 + 10_000)
        slip.route.forEach(store::savePoint)

        assertFalse(store.finish(slip, t0 + 20_000))

        assertTrue(store.workoutsSince(0, 5).items.isEmpty())
        assertTrue(rows.points.isEmpty())
    }

    @Test fun `a workout the killed app left is saved where its checkpoint was written`() {
        store.saveCheckpoint(WorkoutCheckpoint("56", t0, t0 + 600_000, true, 1500, 1800, null, emptyList(), null))
        listOf(point(0), point(5), point(700)).forEach(store::savePoint)

        val saved = store.recover(t0 + 9_000_000)!!

        assertEquals(t0 + 600_000, saved.endMillis)
        assertEquals(56, saved.exerciseType)
        assertEquals(1500, saved.distanceMetres)
        assertEquals(1800, saved.steps)
        assertEquals(listOf(point(0), point(5)), saved.route)
        assertEquals(listOf(saved.copy(route = emptyList())), store.workoutsSince(0, 5).items.map { it.copy(route = emptyList()) })
        assertNull(rows.checkpointText)
    }

    @Test fun `a workout killed while paused ends its pause at the checkpoint`() {
        store.saveCheckpoint(WorkoutCheckpoint("70", t0, t0 + 600_000, false, null, null, null, emptyList(), t0 + 400_000))

        val saved = store.recover(t0 + 9_000_000)!!

        assertEquals(listOf(WearLinkWorkout.Pause(t0 + 400_000, t0 + 600_000)), saved.pauses)
        assertEquals(70, saved.exerciseType)
        assertNull(saved.distanceMetres)
    }

    @Test fun `a sport this build does not know is saved as another workout`() {
        store.saveCheckpoint(WorkoutCheckpoint("swim", t0, t0 + 600_000, false, null, null, null, emptyList(), null))

        assertEquals(0, store.recover(t0 + 9_000_000)!!.exerciseType)
    }

    @Test fun `with no checkpoint, or one that cannot be read, nothing is recovered`() {
        assertNull(store.recover(t0))
        rows.checkpointText = "garbage"
        assertNull(store.recover(t0))
        assertNull(rows.checkpointText)
    }
}
