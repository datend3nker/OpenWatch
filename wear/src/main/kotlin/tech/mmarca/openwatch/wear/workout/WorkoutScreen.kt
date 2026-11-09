package tech.mmarca.openwatch.wear.workout

import tech.mmarca.openwatch.wearlink.WorkoutMetrics
import tech.mmarca.openwatch.wear.features.metric.HeartZoneLabels
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.AlertDialog
import androidx.compose.runtime.LaunchedEffect
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import tech.mmarca.openwatch.wear.DisplayPreferences
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.WearAppService
import tech.mmarca.openwatch.wear.WearPermissions
import tech.mmarca.openwatch.wear.features.metric.ZoneBasis
import tech.mmarca.openwatch.wear.features.recording.RecordingScreen
import tech.mmarca.openwatch.wear.features.recording.WorkoutSummaryScreen
import tech.mmarca.openwatch.wear.ui.theme.WorkoutColor
import tech.mmarca.openwatch.wearlink.WorkoutSport

/**
 * The workout on the watch: pick a sport, then the running workout with its
 * time, heart rate, distance and steps, pause, resume and stop, then the
 * summary of a saved one. The recording itself runs in `WearAppService`
 * ([WorkoutRecorder]); this screen only sends it actions and shows
 * [WorkoutState]. A sport with a route asks for location first and records
 * without a route when it is refused.
 */
@Composable
internal fun WorkoutScreen(zones: ZoneBasis, onClose: () -> Unit) {
    val context = LocalContext.current
    val state by WorkoutState.state.collectAsState()
    var pending by remember { mutableStateOf<WorkoutSport?>(null) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val sport = pending ?: return@rememberLauncherForActivityResult
        pending = null
        WearAppService.startWorkout(context, sport, gps = result[Manifest.permission.ACCESS_FINE_LOCATION] == true)
    }

    val snapshot = state.snapshot
    val sport = state.sport
    var askRecovery by remember { mutableStateOf(false) }
    var figures by remember { mutableStateOf<WorkoutMetrics.Summary?>(null) }
    LaunchedEffect(state.finished, state.recovery?.oneMinuteBpm) {
        if (state.finished) figures = withContext(Dispatchers.IO) { WorkoutFigures.latest(context, zones) }
    }
    if (state.running && sport != null && snapshot != null) {
        RecordingScreen(
            state = snapshot.toRecordingState(sport, sportName(sport)),
            fields = workoutFieldsOf(sport, WearPermissions.hasLocation(context)),
            unitSystem = UnitSystem.METRIC,
            zones = zones,
            onTogglePause = { if (snapshot.paused) WearAppService.resumeWorkout(context) else WearAppService.pauseWorkout(context) },
            onStop = { askRecovery = true },
        )
        AlertDialog(
            visible = askRecovery,
            onDismissRequest = {
                askRecovery = false
                WearAppService.stopWorkout(context, measureRecovery = false)
            },
            confirmButton = {
                AlertDialogDefaults.ConfirmButton(onClick = {
                    askRecovery = false
                    WearAppService.stopWorkout(context, measureRecovery = true)
                })
            },
            dismissButton = {
                AlertDialogDefaults.DismissButton(onClick = {
                    askRecovery = false
                    WearAppService.stopWorkout(context, measureRecovery = false)
                })
            },
            title = { Text(stringResource(R.string.recovery_ask)) },
            text = { Text(stringResource(R.string.recovery_instruction)) },
        )
        return
    }
    val recovery = state.recovery
    if (state.finished && recovery != null && recovery.running) {
        RecoveryScreen(recovery)
        return
    }
    if (state.finished && sport != null && snapshot != null) {
        WorkoutSummaryScreen(
            state = snapshot.toRecordingState(sport, sportName(sport)),
            fields = workoutFieldsOf(sport, WearPermissions.hasLocation(context)),
            unitSystem = UnitSystem.METRIC,
            extras = summaryExtras(figures, state.recovery),
            onDone = {
                WorkoutState.clearFinished()
                onClose()
            },
        )
        return
    }

    val listState = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text(stringResource(R.string.workout_pick)) } }
            items(WorkoutSport.entries) { choice ->
                Button(
                    onClick = {
                        if (choice.gps && !WearPermissions.hasLocation(context)) {
                            pending = choice
                            askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        } else {
                            WearAppService.startWorkout(context, choice, gps = choice.gps)
                        }
                    },
                    icon = { Icon(activityTypeOf(choice).icon, contentDescription = null, tint = WorkoutColor) },
                    label = { Text(stringResource(sportName(choice))) },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(
                    onClick = onClose,
                    label = { Text(stringResource(R.string.workout_back)) },
                    colors = ButtonDefaults.outlinedButtonColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

internal fun sportName(sport: WorkoutSport): Int = when (sport) {
    WorkoutSport.WALKING -> R.string.workout_sport_walking
    WorkoutSport.RUNNING -> R.string.workout_sport_running
    WorkoutSport.HIKING -> R.string.workout_sport_hiking
    WorkoutSport.BIKING -> R.string.workout_sport_biking
    WorkoutSport.TREADMILL -> R.string.workout_sport_treadmill
    WorkoutSport.STAIR_CLIMBING -> R.string.workout_sport_stairs
    WorkoutSport.STAIR_CLIMBING_MACHINE -> R.string.workout_sport_stair_machine
    WorkoutSport.STATIONARY_BIKE -> R.string.workout_sport_stationary_bike
    WorkoutSport.ELLIPTICAL -> R.string.workout_sport_elliptical
    WorkoutSport.ROWING_MACHINE -> R.string.workout_sport_rowing
    WorkoutSport.STRENGTH_TRAINING -> R.string.workout_sport_strength
    WorkoutSport.HIIT -> R.string.workout_sport_hiit
    WorkoutSport.YOGA -> R.string.workout_sport_yoga
    WorkoutSport.PILATES -> R.string.workout_sport_pilates
    WorkoutSport.STRETCHING -> R.string.workout_sport_stretching
    WorkoutSport.DANCING -> R.string.workout_sport_dancing
    WorkoutSport.VOLLEYBALL -> R.string.workout_sport_volleyball
    WorkoutSport.OTHER_WORKOUT -> R.string.workout_sport_other
}

/** The figures below the recorded ones: heart rate, load, energy, splits, VO2max and the recovery. */
@Composable
private fun summaryExtras(figures: WorkoutMetrics.Summary?, recovery: RecoveryState?): List<Pair<String, String>> = buildList {
    val bpm = stringResource(R.string.unit_bpm)
    figures?.maxBpm?.let { add(stringResource(R.string.summary_max_heart_rate) to "$it $bpm") }
    figures?.zoneSeconds?.takeIf { zones -> zones.any { it > 0 } }?.forEachIndexed { index, seconds ->
        add(stringResource(HeartZoneLabels[index]) to "%d:%02d".format(seconds / 60, seconds % 60))
    }
    figures?.trimp?.let { add(stringResource(R.string.summary_trimp) to it.roundToInt().toString()) }
    val kcal = stringResource(R.string.unit_kcal)
    figures?.activeKcal?.let { add(stringResource(R.string.summary_active_energy) to "${it.roundToInt()} $kcal") }
        ?: figures?.totalKcal?.let { add(stringResource(R.string.summary_total_energy) to "${it.roundToInt()} $kcal") }
    figures?.paceSecondsPerKm?.let { add(stringResource(R.string.recording_pace) to "%d:%02d /km".format(it.toInt() / 60, it.toInt() % 60)) }
    figures?.splits?.forEach { split ->
        add(stringResource(R.string.summary_split, split.index + 1) to "%d:%02d".format(split.activeSeconds / 60, split.activeSeconds % 60))
    }
    figures?.vo2Max?.let { add(stringResource(R.string.metric_vo2_max) to "%.1f".format(it)) }
    recovery?.oneMinuteBpm?.let { add(stringResource(R.string.recovery_one_minute) to "−$it $bpm") }
    recovery?.twoMinutesBpm?.let { add(stringResource(R.string.recovery_two_minutes) to "−$it $bpm") }
}
