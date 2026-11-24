package tech.mmarca.openwatch.link

import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openlink.OpenLinkDataWritten
import tech.mmarca.openwatch.healthconnect.HealthConnectWrite

@OptIn(ExperimentalCoroutinesApi::class)
class LinkDataWrittenTest {

    private fun write(type: String, fromSecond: Long, toSecond: Long) =
        HealthConnectWrite(setOf(type), Instant.ofEpochSecond(fromSecond), Instant.ofEpochSecond(toSecond))

    @Test
    fun `a burst of writes is told once, after the quiet, as one span`() = runTest {
        val writes = MutableSharedFlow<HealthConnectWrite>()
        val told = mutableListOf<OpenLinkDataWritten>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { writes.asDataWritten(5.seconds).collect { told += it } }

        writes.emit(write("HeartRate", 100, 200))
        advanceTimeBy(3_000)
        writes.emit(write("Steps", 50, 150))
        advanceTimeBy(4_000)
        assertTrue(told.isEmpty())
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(listOf(OpenLinkDataWritten(setOf("HeartRate", "Steps"), 50_000L, 200_000L)), told)
    }

    @Test
    fun `a write after the quiet starts a burst of its own`() = runTest {
        val writes = MutableSharedFlow<HealthConnectWrite>()
        val told = mutableListOf<OpenLinkDataWritten>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { writes.asDataWritten(5.seconds).collect { told += it } }

        writes.emit(write("Weight", 10, 10))
        advanceTimeBy(6_000)
        writes.emit(write("BodyFat", 20, 20))
        advanceTimeBy(6_000)

        assertEquals(listOf(setOf("Weight"), setOf("BodyFat")), told.map { it.recordTypes })
    }
}
