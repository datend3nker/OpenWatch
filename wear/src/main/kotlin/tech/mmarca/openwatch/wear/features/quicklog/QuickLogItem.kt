package tech.mmarca.openwatch.wear.features.quicklog

import androidx.annotation.StringRes
import tech.mmarca.openwatch.wear.R

/**
 * One entry the quick log can offer. The user chooses which ones show and in
 * what order; see [tech.mmarca.openwatch.wear.WearQuickLogPreferences].
 * The names are stored, so do not rename one.
 *
 * [entryType] gives the entry's symbol and colour (`EntryType.icon`, `.color`).
 */
enum class QuickLogItem(val entryType: EntryType, @StringRes private val fixedLabel: Int?) {
    WATER(EntryType.WATER, R.string.metric_hydration),
    WEIGHT(EntryType.WEIGHT, R.string.metric_weight),
    BLOOD_PRESSURE(EntryType.BLOOD_PRESSURE, R.string.quicklog_blood_pressure),
    BODY_FAT(EntryType.BODY_FAT, null),
    BLOOD_OXYGEN(EntryType.BLOOD_OXYGEN, null),
    RESPIRATORY_RATE(EntryType.RESPIRATORY_RATE, null),
    BODY_TEMPERATURE(EntryType.BODY_TEMPERATURE, null),
    BLOOD_GLUCOSE(EntryType.BLOOD_GLUCOSE, null),
    HEIGHT(EntryType.HEIGHT, null),
    BREATHE(EntryType.MINDFULNESS, R.string.breathe_title);

    /** The one-number types take their name from [LogValueSpecs]. */
    @get:StringRes
    val label: Int
        get() = fixedLabel ?: LogValueSpecs.getValue(entryType).label

    /** True for the items that open the stepper of [LogValueSpecs]. */
    val isSingleValue: Boolean get() = fixedLabel == null

    companion object {
        /** All items, in the order the quick log has always listed them. */
        val Defaults: List<QuickLogItem> = entries.toList()
    }
}
