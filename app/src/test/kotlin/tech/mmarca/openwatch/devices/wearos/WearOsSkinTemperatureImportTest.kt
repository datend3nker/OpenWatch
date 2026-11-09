package tech.mmarca.openwatch.devices.wearos

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearOsSkinTemperatureImportTest {

    private val t0 = Instant.parse("2026-11-08T22:00:00Z")

    private fun minutes(from: Int, count: Int, celsius: (Int) -> Double = { 34.0 }) =
        List(count) { WearOsSkinMinute(t0.plusSeconds(60L * (from + it)), celsius(from + it)) }

    @Test
    fun `a gap over half an hour starts a new run`() {
        val runs = WearOsSkinTemperatureImport.runs(minutes(0, 60) + minutes(100, 60))
        assertEquals(2, runs.size)
        assertEquals(t0, runs[0].start)
        assertEquals(t0.plusSeconds(6000), runs[1].start)
    }

    @Test
    fun `only runs that ended half an hour ago and lasted half an hour count`() {
        val runs = WearOsSkinTemperatureImport.runs(minutes(0, 20) + minutes(60, 120))
        val now = t0.plusSeconds(60L * (179 + 30))
        assertTrue(WearOsSkinTemperatureImport.finished(runs, now).map { it.start } == listOf(t0.plusSeconds(3600)))
        assertTrue(WearOsSkinTemperatureImport.finished(runs, t0.plusSeconds(60L * 185)).isEmpty())
    }

    @Test
    fun `the baseline is the earlier nights' median once there are three, else the night's own`() {
        val run = WearOsSkinTemperatureImport.runs(minutes(0, 60) { 34.0 + (it % 3) * 0.1 }).single()
        assertEquals(34.1, WearOsSkinTemperatureImport.baseline(run, listOf(33.0, 33.5)), 1e-9)
        assertEquals(33.5, WearOsSkinTemperatureImport.baseline(run, listOf(33.0, 33.5, 34.0)), 1e-9)
    }

    @Test
    fun `the record holds one delta per minute against the baseline, at the wrist`() {
        val run = WearOsSkinTemperatureImport.runs(minutes(0, 60) { 34.0 + it / 100.0 }).single()
        val record = WearOsSkinTemperatureImport.record(run, 34.0, ZoneOffset.ofHours(1), version = 5)!!
        assertEquals(60, record.deltas.size)
        assertEquals(0.59, record.deltas.last().delta.inCelsius, 1e-9)
        assertEquals(34.0, record.baseline!!.inCelsius, 1e-9)
        assertEquals("wearos_skin_${t0.toEpochMilli()}", record.metadata.clientRecordId)
        assertEquals(t0.plusSeconds(60L * 60), record.endTime)
    }

    @Test
    fun `a whole night of minutes is one valid record`() {
        val run = WearOsSkinTemperatureImport.runs(minutes(0, 9 * 60) { 33.0 + (it % 20) / 10.0 }).single()
        val record = WearOsSkinTemperatureImport.record(run, 34.35, ZoneOffset.ofHours(1), version = 5)
        assertTrue(record != null)
        assertEquals(9 * 60, record!!.deltas.size)
    }
}
