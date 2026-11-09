package tech.mmarca.openwatch.wear.features.quicklog

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LogValueSpecTest {

    private val previous = Locale.getDefault()

    @Before
    fun pinLocale() {
        // The display follows the locale; the decimal separator is not what these tests check.
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(previous)
    }

    private val temperature = LogValueSpecs.getValue(EntryType.BODY_TEMPERATURE)
    private val glucose = LogValueSpecs.getValue(EntryType.BLOOD_GLUCOSE)

    @Test
    fun metricIsStoredAndShownAsItIs() {
        assertEquals(36.5, temperature.toDisplay(36.5, imperial = false), 1e-9)
        assertEquals(36.5, temperature.toMetric(36.5, imperial = false), 1e-9)
    }

    @Test
    fun imperialConvertsALineAndBack() {
        assertEquals(97.7, temperature.toDisplay(36.5, imperial = true), 1e-9)
        assertEquals(36.5, temperature.toMetric(97.7, imperial = true), 1e-9)
    }

    @Test
    fun glucoseConvertsToMgPerDl() {
        assertEquals(99.1, glucose.toDisplay(5.5, imperial = true), 0.1)
    }

    @Test
    fun oneDecimalIsTenStepsPerUnit() {
        assertEquals(10, temperature.stepsPerUnit)
        assertEquals("36.5", temperature.format(36.5))
    }

    @Test
    fun everyLoggedValueHasAUnitAndARange() {
        for ((type, spec) in LogValueSpecs) {
            assertEquals("$type default inside its range", true, spec.metricDefault in spec.metricRange)
        }
    }
}
