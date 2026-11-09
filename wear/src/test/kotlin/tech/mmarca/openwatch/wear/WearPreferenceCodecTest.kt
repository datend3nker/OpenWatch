package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openwatch.wearlink.HeartRateInterval

class WearPreferenceCodecTest {

    private val defaults = WatchPreferences(
        heartRateDay = HeartRateInterval.EVERY_10_SECONDS,
        heartRateSource = WearSettings.Source.RAW_SENSOR,
        hrvSource = WearSettings.Source.RAW_SENSOR,
        sleepHold = WearSettings.SleepHoldMode.LOCK,
        skinTemperature = false,
        spo2AtNight = false,
    )
    private val samsung = WatchAbilities(samsung = true, skinGranted = true, spo2Granted = false)
    private val pixel = WatchAbilities(samsung = false, skinGranted = false, spo2Granted = false)

    @Test
    fun `every setting and ability goes out as a token`() {
        assertEquals(
            mapOf(
                "hr_day" to "10", "hr_source" to "sensor", "hrv_source" to "sensor", "sleep_hold" to "lock",
                "skin" to "0", "spo2" to "0", "samsung" to "1", "skin_granted" to "1", "spo2_granted" to "0",
            ),
            WearPreferenceCodec.encode(defaults, samsung),
        )
    }

    @Test
    fun `each setting can be changed and comes back the same`() {
        var p = defaults
        for ((key, value) in listOf("hr_day" to "600", "hr_source" to "samsung", "hrv_source" to "samsung", "sleep_hold" to "synced", "skin" to "1", "spo2" to "1")) {
            p = WearPreferenceCodec.apply(p, samsung, key, value)!!
            assertEquals(value, WearPreferenceCodec.encode(p, samsung)[key])
        }
    }

    @Test
    fun `an unknown key, a bad value, an ability or a Samsung choice without the platform is refused`() {
        assertNull(WearPreferenceCodec.apply(defaults, samsung, "nope", "1"))
        assertNull(WearPreferenceCodec.apply(defaults, samsung, "hr_day", "0"))
        assertNull(WearPreferenceCodec.apply(defaults, samsung, "sleep_hold", "maybe"))
        assertNull(WearPreferenceCodec.apply(defaults, samsung, "samsung", "0"))
        assertNull(WearPreferenceCodec.apply(defaults, pixel, "hr_source", "samsung"))
        assertNull(WearPreferenceCodec.apply(defaults, pixel, "skin", "1"))
        assertEquals(defaults, WearPreferenceCodec.apply(defaults, pixel, "skin", "0"))
    }

    @Test
    fun `a quiet night goes out as synced and survives the phone setting synced`() {
        val quiet = defaults.copy(sleepHold = WearSettings.SleepHoldMode.QUIET)
        assertEquals("synced", WearPreferenceCodec.encode(quiet, samsung)["sleep_hold"])
        assertEquals(WearSettings.SleepHoldMode.QUIET, WearPreferenceCodec.apply(quiet, samsung, "sleep_hold", "synced")!!.sleepHold)
        assertEquals(WearSettings.SleepHoldMode.LOCK, WearPreferenceCodec.apply(quiet, samsung, "sleep_hold", "lock")!!.sleepHold)
    }
}
