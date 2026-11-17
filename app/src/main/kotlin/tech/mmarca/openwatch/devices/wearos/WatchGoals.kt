package tech.mmarca.openwatch.devices.wearos

import kotlin.math.roundToInt
import tech.mmarca.openlink.OpenLinkSettingKey
import tech.mmarca.openlink.OpenLinkSettingKeys
import tech.mmarca.openlink.OpenLinkSettings
import tech.mmarca.openwatch.wearlink.WearLinkPreferences as Preferences

/**
 * The daily goals as the watch holds them and as OpenLink carries them, one to the other.
 * Pure. A goal cleared in an app that shares it means no goal: the watch draws no step or
 * energy ring then. The water goal is always there, so a cleared one is the default.
 */
internal object WatchGoals {

    private class Goal(val key: OpenLinkSettingKey, val token: String, val whenCleared: String)

    private val GOALS = listOf(
        Goal(OpenLinkSettingKeys.GOAL_STEPS, Preferences.GOAL_STEPS, whenCleared = "0"),
        Goal(OpenLinkSettingKeys.GOAL_ACTIVE_CALORIES_KCAL, Preferences.GOAL_ACTIVE_KCAL, whenCleared = "0"),
        Goal(OpenLinkSettingKeys.GOAL_WATER_ML, Preferences.GOAL_WATER_ML, whenCleared = Preferences.DEFAULT_WATER_GOAL_ML.toString()),
    )

    /** The watch settings [settings] holds a goal for; a goal never set is no entry, so the watch's own stays. */
    fun tokens(settings: OpenLinkSettings): List<Pair<String, String>> = GOALS.mapNotNull { goal ->
        val setting = settings.entries[goal.key.name] ?: return@mapNotNull null
        goal.token to (setting.values.singleOrNull()?.roundToInt()?.toString() ?: goal.whenCleared)
    }

    /**
     * The setting a watch setting stands for, with its numbers (empty to clear); null for a setting
     * that is no goal, or a value the key does not take.
     */
    fun toSetting(token: String, value: String): Pair<OpenLinkSettingKey, List<Double>>? {
        val goal = GOALS.firstOrNull { it.token == token } ?: return null
        val number = value.toIntOrNull() ?: return null
        if (number == 0) return if (goal.whenCleared == "0") goal.key to emptyList() else null
        return (goal.key to listOf(number.toDouble())).takeIf { goal.key.accepts(it.second) }
    }
}
