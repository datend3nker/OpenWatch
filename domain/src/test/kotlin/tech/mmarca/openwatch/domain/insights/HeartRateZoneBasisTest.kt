package tech.mmarca.openwatch.domain.insights

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openwatch.domain.preferences.BiologicalSex
import tech.mmarca.openwatch.domain.preferences.BodyProfile

class HeartRateZoneBasisTest {

    private val today = LocalDate.of(2026, 11, 9)

    @Test
    fun `an entered maximum wins over the age`() {
        val basis = HeartRateZoneBasis.of(BodyProfile(birthYear = 1996, maxHeartRateBpm = 201, restingHeartRateBpm = 52), today)
        assertEquals(201, basis.maxBpm)
        assertEquals(52, basis.restingBpm)
    }

    @Test
    fun `the age predicts the maximum, by sex`() {
        assertEquals(187, HeartRateZoneBasis.of(BodyProfile(birthYear = 1996), today).maxBpm)
        assertEquals(180, HeartRateZoneBasis.of(BodyProfile(birthYear = 1996, sex = BiologicalSex.FEMALE), today).maxBpm)
    }

    @Test
    fun `an empty profile gives nothing`() {
        val basis = HeartRateZoneBasis.of(BodyProfile(), today)
        assertNull(basis.maxBpm)
        assertNull(basis.restingBpm)
    }

    @Test
    fun `a real observed maximum wins over the age, a brisk walk does not`() {
        val profile = BodyProfile(birthYear = 1996)
        assertEquals(196, HeartRateZoneBasis.of(profile, today, observedMaxBpm = 196, nightRestingBpm = 50).maxBpm)
        assertEquals(187, HeartRateZoneBasis.of(profile, today, observedMaxBpm = 140, nightRestingBpm = 50).maxBpm)
        // Without a resting rate an observed maximum cannot be judged.
        assertEquals(187, HeartRateZoneBasis.of(profile, today, observedMaxBpm = 196).maxBpm)
    }
}
