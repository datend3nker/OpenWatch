package tech.mmarca.openwatch

import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import tech.mmarca.openwatch.devices.wearos.WearOsPhoneActivity
import tech.mmarca.openwatch.features.watches.WatchAutoSyncScheduler

class AppStartupActorsTest {

    private val autoSyncScheduler = mockk<WatchAutoSyncScheduler>(relaxed = true)
    private val phoneActivity = mockk<WearOsPhoneActivity>(relaxed = true)

    @Test
    fun `the sync schedules are restored and the phone activity relay starts`() {
        AppStartupActors(autoSyncScheduler, phoneActivity).start()

        verify(exactly = 1) { autoSyncScheduler.restoreAll() }
        verify(exactly = 1) { phoneActivity.start() }
    }
}
