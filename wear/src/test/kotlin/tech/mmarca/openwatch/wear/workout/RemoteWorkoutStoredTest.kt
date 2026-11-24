package tech.mmarca.openwatch.wear.workout

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import tech.mmarca.openwatch.wear.MetricStore
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutControl.Phase

/** The recorded workout's session in the watch's store: what is written is what a restarted service reads. */
@RunWith(AndroidJUnit4::class)
class RemoteWorkoutStoredTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = MetricStore(app, name = null)

    @After
    fun close() = store.close()

    @Test
    fun `a session reads back as it was written, a space in its id too, and is gone once cleared`() {
        val stored = RemoteWorkout.Stored(store)
        assertNull(stored.read())
        val held = RemoteWorkout.Held("ride 1/ä", Phase.STOPPED, 1_234L, "stopped_on_device")
        stored.write(held)
        assertEquals(held, RemoteWorkout.Stored(store).read())
        stored.write(held.copy(phase = Phase.RECORDING, reason = null))
        assertEquals(held.copy(phase = Phase.RECORDING, reason = null), stored.read())
        stored.write(null)
        assertNull(stored.read())
    }
}
