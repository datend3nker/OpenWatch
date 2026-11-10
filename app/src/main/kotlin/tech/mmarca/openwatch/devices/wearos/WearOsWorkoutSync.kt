package tech.mmarca.openwatch.devices.wearos

import tech.mmarca.openwatch.wearlink.WorkoutMetrics
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import tech.mmarca.openwatch.core.presentation.isPermissionFailure
import tech.mmarca.openwatch.data.repository.ImportedRecordRepository

/**
 * Writes the watch's finished workouts to Health Connect ([WearOsWorkoutImport]),
 * a page at a time from the phone's cursor ([WearOsSyncCursorStore.workoutCursor]).
 * The cursor moves after each workout is written, so a sync that dies mid-way
 * resumes there, and a workout written twice upserts. Ported from OpenVitals,
 * which writes through its own activity store; OpenWatch writes the records.
 *
 * Without the write grants nothing is written and the cursor stays: the
 * workouts wait on the watch (it keeps a month). That never fails the sync.
 */
internal class WearOsWorkoutSync(
    private val importRepository: ImportedRecordRepository,
    private val cursors: WearOsSyncCursorStore,
    private val granted: suspend () -> Set<String>,
    private val zone: () -> ZoneId,
    /** The workout's figures from its heart rate, route and the profile; null when they cannot be worked out. */
    private val summarize: suspend (WearOsWorkout) -> WorkoutMetrics.Summary? = { null },
) {

    /** [written] sessions, and the grants that [withheld] some workouts. */
    class Outcome(val written: Int, val withheld: Set<String>)

    /** Null when [pullPage] has no bonded watch to ask. A failure other than a missing grant throws. */
    suspend fun sync(
        deviceId: String,
        afterWritten: suspend (WearOsWorkout) -> Unit = {},
        pullPage: suspend (Instant) -> WearOsWorkoutPage?,
    ): Outcome? {
        var since = cursors.workoutCursor(deviceId)
        var written = 0
        var pages = 0
        while (true) {
            val page = pullPage(since) ?: return null
            if (page.workouts.isEmpty()) break
            for (workout in page.workouts) {
                val missing = WearOsWorkoutImport.permissions(workout) - granted()
                if (missing.isNotEmpty()) return Outcome(written, missing)
                val summary = try {
                    summarize(workout)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    WearOsLog.log("workout: figures not worked out: ${error.javaClass.simpleName}")
                    null
                }
                val records = if (summary == null) {
                    WearOsWorkoutImport.records(workout, zone())
                } else {
                    WearOsWorkoutImport.records(
                        workout, zone(), WearOsWorkoutMetrics.laps(workout, summary), WearOsWorkoutMetrics.notes(summary),
                    ) + WearOsWorkoutMetrics.records(workout, summary, zone(), granted())
                }
                try {
                    importRepository.insertImportedRecords(records)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (error !is SecurityException && !error.isPermissionFailure()) throw error
                    return Outcome(written, WearOsWorkoutImport.permissions(workout))
                }
                written++
                // Optional, as the walking energy: it never holds the cursor back.
                afterWritten(workout)
                since = workout.start
                cursors.setWorkoutCursor(deviceId, since)
            }
            if (!page.hasMore) break
            if (++pages >= MAX_PAGES) break
        }
        return Outcome(written, emptySet())
    }

    private companion object {
        /** A safety net against a watch that always says "more", as the other pulls have. */
        const val MAX_PAGES = 200
    }
}
