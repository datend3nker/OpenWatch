package tech.mmarca.openwatch.healthconnect

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
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
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import androidx.health.connect.client.records.Record
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * What OpenWatch wrote to Health Connect: the record types (class names without "Record", as
 * OpenLink names them) and the time they cover. Never a value.
 */
data class HealthConnectWrite(val recordTypes: Set<String>, val from: Instant, val to: Instant) {

    operator fun plus(other: HealthConnectWrite) =
        HealthConnectWrite(recordTypes + other.recordTypes, minOf(from, other.from), maxOf(to, other.to))

    companion object {
        /** Null for no records. The names survive R8: `proguard-rules.pro` keeps them. */
        fun of(records: List<Record>): HealthConnectWrite? {
            if (records.isEmpty()) return null
            val types = records.mapNotNullTo(mutableSetOf()) { it::class.simpleName?.removeSuffix("Record") }
            val times = records.flatMap(::timesOf)
            if (types.isEmpty()) return null
            // A type missing from timesOf still counts; alone, it covers the moment of the write.
            val now = Instant.now()
            return HealthConnectWrite(types, times.minOrNull() ?: now, times.maxOrNull() ?: now)
        }

        /**
         * The times a record covers. Health Connect's `IntervalRecord` and `InstantaneousRecord` are
         * internal to its library, so this names the types OpenWatch writes; a new one belongs here.
         */
        private fun timesOf(record: Record): List<Instant> = when (record) {
            is ActiveCaloriesBurnedRecord -> listOf(record.startTime, record.endTime)
            is CyclingPedalingCadenceRecord -> listOf(record.startTime, record.endTime)
            is DistanceRecord -> listOf(record.startTime, record.endTime)
            is ElevationGainedRecord -> listOf(record.startTime, record.endTime)
            is ExerciseSessionRecord -> listOf(record.startTime, record.endTime)
            is FloorsClimbedRecord -> listOf(record.startTime, record.endTime)
            is HeartRateRecord -> listOf(record.startTime, record.endTime)
            is HydrationRecord -> listOf(record.startTime, record.endTime)
            is MindfulnessSessionRecord -> listOf(record.startTime, record.endTime)
            is PowerRecord -> listOf(record.startTime, record.endTime)
            is SkinTemperatureRecord -> listOf(record.startTime, record.endTime)
            is SleepSessionRecord -> listOf(record.startTime, record.endTime)
            is SpeedRecord -> listOf(record.startTime, record.endTime)
            is StepsRecord -> listOf(record.startTime, record.endTime)
            is StepsCadenceRecord -> listOf(record.startTime, record.endTime)
            is TotalCaloriesBurnedRecord -> listOf(record.startTime, record.endTime)
            is WheelchairPushesRecord -> listOf(record.startTime, record.endTime)
            is BasalMetabolicRateRecord -> listOf(record.time)
            is BloodGlucoseRecord -> listOf(record.time)
            is BloodPressureRecord -> listOf(record.time)
            is BodyFatRecord -> listOf(record.time)
            is BodyTemperatureRecord -> listOf(record.time)
            is BodyWaterMassRecord -> listOf(record.time)
            is HeartRateVariabilityRmssdRecord -> listOf(record.time)
            is HeightRecord -> listOf(record.time)
            is LeanBodyMassRecord -> listOf(record.time)
            is OxygenSaturationRecord -> listOf(record.time)
            is RespiratoryRateRecord -> listOf(record.time)
            is RestingHeartRateRecord -> listOf(record.time)
            is WeightRecord -> listOf(record.time)
            else -> emptyList()
        }
    }
}

/** Every successful insert of this app, for the apps connected over OpenLink to read again. */
@Singleton
class HealthConnectWrites @Inject constructor() {

    private val _written = MutableSharedFlow<HealthConnectWrite>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val written: SharedFlow<HealthConnectWrite> = _written.asSharedFlow()

    fun report(records: List<Record>) {
        HealthConnectWrite.of(records)?.let(_written::tryEmit)
    }
}
