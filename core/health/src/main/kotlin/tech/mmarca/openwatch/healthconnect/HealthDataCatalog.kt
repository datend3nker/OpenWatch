package tech.mmarca.openwatch.healthconnect

import androidx.annotation.StringRes
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import kotlin.reflect.KClass
import tech.mmarca.openwatch.core.health.R

/** Where a health data type comes from, or that it is read. One heading of the list the user sees. */
enum class HealthDataSection(@param:StringRes val titleRes: Int) {
    GARMIN_WATCH(R.string.health_data_section_garmin),
    WEAROS_WATCH(R.string.health_data_section_wearos),
    SCALE(R.string.health_data_section_scale),

    /** The devices of the Bluetooth standard health profiles (`:devices:ble-standard`). */
    STANDARD_DEVICES(R.string.health_data_section_standard),
    READS(R.string.health_data_section_reads),

    /** What the watch app asks for on the watch, to read the watch's own sensors. */
    WATCH_APP(R.string.health_data_section_watch_app),
}

/**
 * One health permission and what it is for.
 *
 * @param record the record type the permission covers; null for access that is not one type.
 * @param noteRes when a type is written only behind a switch, or what a read is for. Null
 *   where the section's heading says it all.
 */
data class HealthDataEntry(
    val permission: String,
    val record: KClass<out Record>? = null,
    @param:StringRes val noteRes: Int? = null,
)

/**
 * Every health permission the two manifests declare, by where its data comes from.
 *
 * This is the one list. The screen Health Connect opens for "why does this app want
 * this" renders it, the tables in `PRIVACY.md` and `docs/app/permissions.md` are checked
 * against it, and `HealthDataCatalogTest` fails when a manifest declares a permission
 * that is not here or the reverse. A device integration that writes a new type adds its
 * row here first.
 */
object HealthDataCatalog {

    val sections: Map<HealthDataSection, List<HealthDataEntry>> = mapOf(
        HealthDataSection.GARMIN_WATCH to listOf(
            write(ExerciseSessionRecord::class),
            HealthDataEntry(HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE),
            write(DistanceRecord::class),
            write(SpeedRecord::class),
            write(PowerRecord::class),
            write(ElevationGainedRecord::class),
            write(FloorsClimbedRecord::class),
            write(StepsRecord::class),
            write(ActiveCaloriesBurnedRecord::class),
            write(TotalCaloriesBurnedRecord::class),
            write(BasalMetabolicRateRecord::class),
            write(HeartRateRecord::class),
            write(RestingHeartRateRecord::class),
            write(HeartRateVariabilityRmssdRecord::class),
            write(OxygenSaturationRecord::class),
            write(RespiratoryRateRecord::class),
            write(Vo2MaxRecord::class),
            write(SleepSessionRecord::class),
            write(WeightRecord::class, R.string.health_data_note_garmin_weight),
        ),
        HealthDataSection.WEAROS_WATCH to listOf(
            write(HeartRateRecord::class),
            write(StepsRecord::class),
            write(ActiveCaloriesBurnedRecord::class, R.string.health_data_note_wearos_calories),
            write(FloorsClimbedRecord::class),
            write(ElevationGainedRecord::class),
            write(SleepSessionRecord::class, R.string.health_data_note_wearos_sleep),
            write(RestingHeartRateRecord::class),
            write(HeartRateVariabilityRmssdRecord::class, R.string.health_data_note_wearos_hrv),
            write(ExerciseSessionRecord::class, R.string.health_data_note_wearos_workout),
            HealthDataEntry(HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE),
            write(DistanceRecord::class),
            write(RespiratoryRateRecord::class, R.string.health_data_note_wearos_breathing_logged),
            write(OxygenSaturationRecord::class, R.string.health_data_note_wearos_spo2_measured),
            write(SkinTemperatureRecord::class, R.string.health_data_note_wearos_skin),
            write(HydrationRecord::class, R.string.health_data_note_wearos_entry),
            write(WeightRecord::class, R.string.health_data_note_wearos_entry),
            write(BloodPressureRecord::class, R.string.health_data_note_wearos_entry),
            write(MindfulnessSessionRecord::class, R.string.health_data_note_wearos_mindfulness),
            write(BodyFatRecord::class, R.string.health_data_note_wearos_entry_or_body_composition),
            write(LeanBodyMassRecord::class, R.string.health_data_note_wearos_body_composition),
            write(BodyWaterMassRecord::class, R.string.health_data_note_wearos_body_composition),
            write(BodyTemperatureRecord::class, R.string.health_data_note_wearos_entry),
            write(BloodGlucoseRecord::class, R.string.health_data_note_wearos_entry),
            write(HeightRecord::class, R.string.health_data_note_wearos_entry),
            write(Vo2MaxRecord::class, R.string.health_data_note_wearos_vo2),
        ),
        HealthDataSection.SCALE to listOf(
            write(WeightRecord::class),
            write(HeartRateRecord::class),
            write(BodyFatRecord::class, R.string.health_data_note_scale_estimate),
            write(LeanBodyMassRecord::class, R.string.health_data_note_scale_estimate),
            write(BodyWaterMassRecord::class, R.string.health_data_note_scale_estimate),
        ),
        HealthDataSection.STANDARD_DEVICES to listOf(
            write(HeartRateRecord::class, R.string.health_data_note_standard_heart_rate),
            write(BloodPressureRecord::class, R.string.health_data_note_standard_blood_pressure),
            write(BloodGlucoseRecord::class, R.string.health_data_note_standard_blood_glucose),
            write(WeightRecord::class, R.string.health_data_note_standard_weight),
        ),
        HealthDataSection.READS to listOf(
            read(WeightRecord::class, R.string.health_data_read_weight),
            read(HeightRecord::class, R.string.health_data_read_height),
            read(ExerciseSessionRecord::class, R.string.health_data_read_exercise),
            read(SleepSessionRecord::class, R.string.health_data_read_sleep),
            read(RestingHeartRateRecord::class, R.string.health_data_read_resting_heart_rate),
            read(HeartRateRecord::class, R.string.health_data_read_heart_rate),
            read(ActiveCaloriesBurnedRecord::class, R.string.health_data_read_day_totals),
            read(DistanceRecord::class, R.string.health_data_read_day_totals),
            HealthDataEntry(
                HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
                noteRes = R.string.health_data_read_history,
            ),
            HealthDataEntry(
                HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
                noteRes = R.string.health_data_read_background,
            ),
        ),
        HealthDataSection.WATCH_APP to listOf(
            read(HeartRateRecord::class, R.string.health_data_watch_heart_rate),
            HealthDataEntry(
                HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
                noteRes = R.string.health_data_watch_background,
            ),
            read(SkinTemperatureRecord::class, R.string.health_data_watch_skin),
            read(OxygenSaturationRecord::class, R.string.health_data_watch_spo2_spot),
        ),
    )

    /** What the phone app's manifest declares. */
    val phonePermissions: Set<String> =
        sections.filterKeys { it != HealthDataSection.WATCH_APP }.values.flatten().mapTo(sortedSetOf()) { it.permission }

    /** What the watch app's manifest declares. */
    val watchPermissions: Set<String> =
        sections.getValue(HealthDataSection.WATCH_APP).mapTo(sortedSetOf()) { it.permission }

    private fun write(record: KClass<out Record>, @StringRes noteRes: Int? = null) =
        HealthDataEntry(HealthPermission.getWritePermission(record), record, noteRes)

    private fun read(record: KClass<out Record>, @StringRes noteRes: Int? = null) =
        HealthDataEntry(HealthPermission.getReadPermission(record), record, noteRes)
}
