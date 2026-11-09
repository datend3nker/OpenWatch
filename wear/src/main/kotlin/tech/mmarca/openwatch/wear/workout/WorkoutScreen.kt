package tech.mmarca.openwatch.wear.workout

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
internal fun WorkoutScreen(onClose: () -> Unit) {
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
    if (state.running && sport != null && snapshot != null) {
        RecordingScreen(
            state = snapshot.toRecordingState(sport, sportName(sport)),
            fields = workoutFieldsOf(sport, WearPermissions.hasLocation(context)),
            unitSystem = UnitSystem.METRIC,
            maxHeartRate = DisplayPreferences().maxHeartRate,
            onTogglePause = { if (snapshot.paused) WearAppService.resumeWorkout(context) else WearAppService.pauseWorkout(context) },
            onStop = { WearAppService.stopWorkout(context) },
        )
        return
    }
    if (state.finished && sport != null && snapshot != null) {
        WorkoutSummaryScreen(
            state = snapshot.toRecordingState(sport, sportName(sport)),
            fields = workoutFieldsOf(sport, WearPermissions.hasLocation(context)),
            unitSystem = UnitSystem.METRIC,
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
