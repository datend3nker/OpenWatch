package tech.mmarca.openwatch.devices.wearos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openlink.OpenLinkSettingKeys
import tech.mmarca.openlink.OpenLinkSettings

class WatchGoalsTest {

    @Test
    fun `a goal that was set goes to the watch, one never set does not`() {
        val settings = OpenLinkSettings()
            .with(OpenLinkSettingKeys.GOAL_STEPS, listOf(9_000.0), 1L)
            .with(OpenLinkSettingKeys.GOAL_SLEEP_MINUTES, listOf(480.0), 1L)
        assertEquals(listOf("goal_steps" to "9000"), WatchGoals.tokens(settings))
    }

    @Test
    fun `a cleared step goal is no goal on the watch, a cleared water goal is the default`() {
        val settings = OpenLinkSettings()
            .with(OpenLinkSettingKeys.GOAL_STEPS, emptyList(), 1L)
            .with(OpenLinkSettingKeys.GOAL_ACTIVE_CALORIES_KCAL, emptyList(), 1L)
            .with(OpenLinkSettingKeys.GOAL_WATER_ML, emptyList(), 1L)
        assertEquals(
            listOf("goal_steps" to "0", "goal_kcal" to "0", "goal_water" to "2000"),
            WatchGoals.tokens(settings),
        )
    }

    @Test
    fun `a watch setting becomes a setting only for a goal and a value that fits`() {
        assertEquals(OpenLinkSettingKeys.GOAL_STEPS to listOf(12_000.0), WatchGoals.toSetting("goal_steps", "12000"))
        assertEquals(OpenLinkSettingKeys.GOAL_STEPS to emptyList<Double>(), WatchGoals.toSetting("goal_steps", "0"))
        assertNull(WatchGoals.toSetting("goal_water", "0"))
        assertNull(WatchGoals.toSetting("goal_steps", "50"))
        assertNull(WatchGoals.toSetting("goal_steps", "x"))
        assertNull(WatchGoals.toSetting("hr_day", "10"))
    }
}
