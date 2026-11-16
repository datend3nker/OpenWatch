package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import java.time.ZoneId

/**
 * Maps a body composition measurement the wearer took on the watch (the
 * Samsung platform's estimate from impedance) to Health Connect: body fat,
 * lean body mass and body water, the three the scale writes too, keyed
 * `wearos_bc_<kind>_<millis>`. A measurement is final, so a re-pull is the
 * same records. Each record has its own write permission, and one is made
 * only where its permission is held.
 *
 * Pure, so it is testable without a device.
 */
object WearOsBodyCompositionImport {

    const val CLIENT_ID_PREFIX = "wearos_bc_"

    private val bodyFat = HealthPermission.getWritePermission(BodyFatRecord::class)
    private val leanMass = HealthPermission.getWritePermission(LeanBodyMassRecord::class)
    private val bodyWater = HealthPermission.getWritePermission(BodyWaterMassRecord::class)

    val writePermissions: Set<String> = setOf(bodyFat, leanMass, bodyWater)

    /** The records of [measurement] that [granted] allows; a value Health Connect would refuse is left out. */
    fun records(measurement: WearOsBodyComposition, zone: ZoneId, granted: Set<String>): List<Record> {
        val time = measurement.time
        val offset = zone.rules.getOffset(time)
        val millis = time.toEpochMilli()
        fun metadata(kind: String) = Metadata.autoRecorded(
            device = Device(type = Device.TYPE_WATCH),
            clientRecordId = "$CLIENT_ID_PREFIX${kind}_$millis",
        )
        fun make(permission: String, record: () -> Record): Record? =
            if (permission !in granted) null else try {
                record()
            } catch (_: IllegalArgumentException) {
                null
            }
        return listOfNotNull(
            make(bodyFat) { BodyFatRecord(time, offset, Percentage(measurement.bodyFatPercent), metadata("fat")) },
            make(leanMass) { LeanBodyMassRecord(time, offset, Mass.kilograms(measurement.fatFreeMassKg), metadata("lean")) },
            make(bodyWater) { BodyWaterMassRecord(time, offset, Mass.kilograms(measurement.bodyWaterKg), metadata("water")) },
        )
    }
}
