package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchSensorsTest {

    @Test
    fun `each spot measurement needs its own sensor`() {
        assertEquals(SpotKind.entries.toSet(), WatchSensors.ALL.spotKinds)
        assertTrue(WatchSensors.NONE.spotKinds.isEmpty())
        assertEquals(setOf(SpotKind.HEART_RATE), WatchSensors.NONE.copy(heartRate = true).spotKinds)
        // A spot HRV needs single beats or the raw pulse signal; the heart rate sensor alone is not enough.
        assertEquals(
            setOf(SpotKind.HEART_RATE, SpotKind.AIR_PRESSURE, SpotKind.SPO2, SpotKind.ECG, SpotKind.BODY_COMPOSITION),
            WatchSensors.ALL.copy(heartBeat = false, rawPpg = false).spotKinds,
        )
        assertTrue(SpotKind.HRV in WatchSensors.ALL.copy(heartBeat = false).spotKinds)
        assertTrue(SpotKind.HRV in WatchSensors.ALL.copy(rawPpg = false).spotKinds)
        assertEquals(setOf(SpotKind.AIR_PRESSURE), WatchSensors.NONE.copy(barometer = true, stepCounter = true, gps = true).spotKinds)
        assertEquals("blood oxygen only from the Samsung tracker", setOf(SpotKind.SPO2), WatchSensors.NONE.copy(spo2 = true).spotKinds)
        assertEquals("the ECG and body composition only where the platform lists them", setOf(SpotKind.ECG, SpotKind.BODY_COMPOSITION), WatchSensors.NONE.copy(ecg = true, bodyComposition = true).spotKinds)
    }
}
