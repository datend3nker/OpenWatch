package tech.mmarca.openwatch.wear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wear.features.measure.Measurement
import tech.mmarca.openwatch.wear.health.WearCapabilities

class WearRoutesTest {

    @Test
    fun tilesMayOpenLoggingAndMeasuring_butNothingElse() {
        assertTrue(WearRoutes.isDeepLinkable(WearRoutes.QUICK_LOG))
        assertTrue(WearRoutes.isDeepLinkable(WearRoutes.measurement(Measurement.HEART_RATE)))
        assertFalse(WearRoutes.isDeepLinkable(WearRoutes.RECORDING))
        assertFalse(WearRoutes.isDeepLinkable("measure/ECG"))
    }

    @Test
    fun availableMetrics_offerPhoneAndLoggedMetrics_andOnlyMeasuredSensorOnes() {
        val available = WearCapabilities(probed = true, sensorMetrics = setOf(MetricTile.STEPS)).availableMetrics
        assertTrue(MetricTile.STEPS in available)
        assertTrue(MetricTile.SLEEP in available)
        assertTrue(MetricTile.HYDRATION in available)
        assertFalse(MetricTile.HEART_RATE in available)
        assertFalse(MetricTile.BLOOD_OXYGEN in available)
    }
}
