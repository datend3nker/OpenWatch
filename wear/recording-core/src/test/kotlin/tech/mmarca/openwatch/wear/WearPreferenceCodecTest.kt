package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.healthcalculations.insights.HeartRateZones
import tech.mmarca.healthcalculations.preferences.BiologicalSex
import tech.mmarca.healthcalculations.preferences.BodyProfile
import tech.mmarca.openwatch.wearlink.HeartRateInterval

class WearPreferenceCodecTest {

    private val defaults = WatchPreferences(
        heartRateDay = HeartRateInterval.EVERY_10_SECONDS,
        heartRateSource = MeasurementSource.RAW_SENSOR,
        hrvSource = MeasurementSource.RAW_SENSOR,
        syncedSleepHold = false,
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
                "skin" to "0", "spo2" to "0", "hr_max" to "0", "hr_rest" to "0",
                "body_hg" to "0", "body_cm" to "0", "birth_year" to "0", "sex" to "0",
                "samsung" to "1", "skin_granted" to "1", "spo2_granted" to "0",
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
    fun `the green PPG is an HRV source only, and only with its grant`() {
        val granted = samsung.copy(ppgGranted = true)
        val chosen = WearPreferenceCodec.apply(defaults, granted, "hrv_source", "samsung_ppg")!!
        assertEquals(MeasurementSource.SAMSUNG_PPG, chosen.hrvSource)
        assertEquals("samsung_ppg", WearPreferenceCodec.encode(chosen, granted)["hrv_source"])
        assertNull("without the grant", WearPreferenceCodec.apply(defaults, samsung, "hrv_source", "samsung_ppg"))
        assertNull("without the platform", WearPreferenceCodec.apply(defaults, pixel.copy(ppgGranted = true), "hrv_source", "samsung_ppg"))
        assertNull("never the heart rate's", WearPreferenceCodec.apply(defaults, granted, "hr_source", "samsung_ppg"))
    }

    @Test
    fun `the zones' heart rates take whole bpm in range, and 0 clears them`() {
        val set = WearPreferenceCodec.apply(defaults, pixel, "hr_max", "187")!!
        assertEquals(187, set.maxHeartRate)
        assertEquals(null, WearPreferenceCodec.apply(set, pixel, "hr_max", "0")!!.maxHeartRate)
        assertEquals(52, WearPreferenceCodec.apply(defaults, pixel, "hr_rest", "52")!!.restingHeartRate)
        assertNull(WearPreferenceCodec.apply(defaults, pixel, "hr_max", "40"))
        assertNull(WearPreferenceCodec.apply(defaults, pixel, "hr_rest", "x"))
    }

    @Test
    fun `the profile takes whole numbers in range and a sex token, 0 clears it, and it is the figures' body`() {
        var p = defaults
        for ((key, value) in listOf("body_hg", "702", "body_cm", "178", "birth_year", "1990", "sex", "f").chunked(2).map { it[0] to it[1] }) {
            p = WearPreferenceCodec.apply(p, pixel, key, value)!!
            assertEquals(value, WearPreferenceCodec.encode(p, pixel)[key])
        }

        assertEquals(BodyProfile(birthYear = 1990, weightKg = 70.2, heightCm = 178.0, sex = BiologicalSex.FEMALE), p.bodyProfile())
        assertEquals(null, WearPreferenceCodec.apply(p, pixel, "body_hg", "0")!!.weightHectograms)
        assertEquals(null, WearPreferenceCodec.apply(p, pixel, "sex", "0")!!.sex)
        assertEquals(BiologicalSex.MALE, WearPreferenceCodec.apply(p, pixel, "sex", "m")!!.sex)
        assertNull(WearPreferenceCodec.apply(p, pixel, "body_hg", "150"))
        assertNull(WearPreferenceCodec.apply(p, pixel, "body_cm", "300"))
        assertNull(WearPreferenceCodec.apply(p, pixel, "birth_year", "1850"))
        assertNull(WearPreferenceCodec.apply(p, pixel, "sex", "x"))
        assertEquals(BodyProfile(), defaults.bodyProfile())
    }

    @Test
    fun `the zones rest on the phone's heart rates, and there are none without a maximum`() {
        assertNull(defaults.zoneBasis())
        assertNull("a resting rate alone is no zones", defaults.copy(restingHeartRate = 52).zoneBasis())
        assertEquals(HeartRateZones.Basis(maxBpm = 176), defaults.copy(maxHeartRate = 176).zoneBasis())
        assertEquals(
            HeartRateZones.Basis(maxBpm = 176, restingBpm = 52),
            defaults.copy(maxHeartRate = 176, restingHeartRate = 52).zoneBasis(),
        )
    }
}
