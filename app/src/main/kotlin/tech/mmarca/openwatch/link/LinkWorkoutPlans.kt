package tech.mmarca.openwatch.link

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openwatch.devices.wearos.WorkoutPlanSource
import tech.mmarca.openwatch.wearlink.WearPlan

/**
 * The last plan of each recorded workout a client ran on a watch, for the sync that brings the workout in
 * to write its exercise segments. In memory only, the 16 newest, labels included until the process ends:
 * a plan is never logged and never written to disk.
 */
@Singleton
class LinkWorkoutPlans @Inject constructor() : WorkoutPlanSource {

    private class Entry(val deviceId: String, val startedAtMillis: Long, val plan: WearPlan)

    private val kept = ArrayDeque<Entry>()

    /** The workout started on [deviceId] at [startedAtMillis] ended with [plan]. */
    @Synchronized
    fun remember(deviceId: String, startedAtMillis: Long, plan: WearPlan) {
        kept.addLast(Entry(deviceId, startedAtMillis, plan))
        while (kept.size > MAX_KEPT) kept.removeFirst()
    }

    /** The newest plan of the device whose workout began within [START_TOLERANCE_MILLIS] of [start]. */
    @Synchronized
    override fun planFor(deviceId: String, start: Instant): WearPlan? =
        kept.lastOrNull { it.deviceId == deviceId && kotlin.math.abs(it.startedAtMillis - start.toEpochMilli()) <= START_TOLERANCE_MILLIS }?.plan

    private companion object {
        const val MAX_KEPT = 16

        /** The phone asks the watch to start a moment before the watch notes its start; clocks drift a little. */
        const val START_TOLERANCE_MILLIS = 5 * 60_000L
    }
}
