package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WearLinkEntry

class WearRoutesTest {

    /** The argument the navigation host reads back from a metric's route. */
    private fun argumentOf(route: String): String = route.substringAfter("metric/")

    @Test
    fun everyMetricsRouteNamesItsMetric() {
        for (metric in MetricTile.entries) {
            assertEquals(metric, WearRoutes.metricOf(argumentOf(WearRoutes.metric(metric))))
        }
    }

    @Test
    fun aNameWithoutAMetricIsNoMetric() {
        // A name a later build stored, another case, or no argument at all: none throws.
        assertNull(WearRoutes.metricOf("BODY_BATTERY"))
        assertNull(WearRoutes.metricOf("steps"))
        assertNull(WearRoutes.metricOf(""))
        assertNull(WearRoutes.metricOf(null))
    }

    @Test
    fun everyMeasurementsRouteNamesItsKind() {
        for (kind in SpotKind.entries) {
            assertEquals(kind, WearRoutes.spotKindOf(WearRoutes.measurement(kind).substringAfter("measure/")))
        }
        assertNull(WearRoutes.spotKindOf("GLUCOSE"))
        assertNull(WearRoutes.spotKindOf(null))
    }

    @Test
    fun aTileMayOpenTheLoggingAndMeasuringScreensAndNothingElse() {
        for (route in listOf(WearRoutes.QUICK_LOG, WearRoutes.LOG_WEIGHT, WearRoutes.LOG_BLOOD_PRESSURE, WearRoutes.BREATHE, WearRoutes.MEASURE)) {
            assertTrue(route, WearRoutes.isDeepLinkable(route))
        }
        for (kind in SpotKind.entries) assertTrue(WearRoutes.isDeepLinkable(WearRoutes.measurement(kind)))

        assertFalse(WearRoutes.isDeepLinkable(WearRoutes.STATUS))
        assertFalse(WearRoutes.isDeepLinkable(WearRoutes.QUICK_LOG_EDITOR))
        assertEquals(WearLinkEntry.Type.HEIGHT, WearRoutes.entryTypeOf(WearRoutes.logValue(WearLinkEntry.Type.HEIGHT).substringAfter('/')))
        assertNull(WearRoutes.entryTypeOf("COFFEE"))
        assertFalse(WearRoutes.isDeepLinkable(WearRoutes.SETTINGS))
        assertFalse(WearRoutes.isDeepLinkable(WearRoutes.PHONE))
        assertFalse(WearRoutes.isDeepLinkable(WearRoutes.SENSORS))
        assertFalse(WearRoutes.isDeepLinkable(WearRoutes.TILE_EDITOR))
        assertFalse(WearRoutes.isDeepLinkable(WearRoutes.WORKOUT))
        assertFalse(WearRoutes.isDeepLinkable(WearRoutes.DASHBOARD))
        assertFalse(WearRoutes.isDeepLinkable(WearRoutes.metric(MetricTile.STEPS)))
        // The pattern itself, a kind this build has not, another case, nothing.
        assertFalse(WearRoutes.isDeepLinkable(WearRoutes.MEASUREMENT))
        assertFalse(WearRoutes.isDeepLinkable("measure/GLUCOSE"))
        assertFalse(WearRoutes.isDeepLinkable("QUICK_LOG"))
        assertFalse(WearRoutes.isDeepLinkable(""))
        assertFalse(WearRoutes.isDeepLinkable(null))
    }
}
