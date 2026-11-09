package tech.mmarca.openwatch.wear.workout

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.WearAppService
import tech.mmarca.openwatch.wear.WearPermissions
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val snapshot = state.snapshot
        val sport = state.sport
        when {
            state.running && sport != null && snapshot != null -> Running(sport, snapshot)
            state.finished && sport != null && snapshot != null -> Summary(sport, snapshot) {
                WorkoutState.clearFinished()
                onClose()
            }
            else -> {
                Text(stringResource(R.string.workout_pick), style = MaterialTheme.typography.titleMedium)
                for (choice in WorkoutSport.entries) {
                    Button(
                        onClick = {
                            if (choice.gps && !WearPermissions.hasLocation(context)) {
                                pending = choice
                                askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            } else {
                                WearAppService.startWorkout(context, choice, gps = choice.gps)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(sportName(choice))) }
                }
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.workout_back)) }
            }
        }
    }
}

@Composable
private fun Running(sport: WorkoutSport, snapshot: WorkoutSnapshot) {
    val context = LocalContext.current
    Text(stringResource(sportName(sport)), style = MaterialTheme.typography.bodySmall)
    Text(clock(snapshot.activeSeconds), style = MaterialTheme.typography.displaySmall)
    if (snapshot.paused) Text(stringResource(R.string.workout_paused), color = MaterialTheme.colorScheme.tertiary)
    Values(sport, snapshot)
    if (snapshot.waitingForFix) Text(stringResource(R.string.workout_waiting_for_gps), style = MaterialTheme.typography.bodySmall)
    Button(
        onClick = { if (snapshot.paused) WearAppService.resumeWorkout(context) else WearAppService.pauseWorkout(context) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(if (snapshot.paused) R.string.workout_resume else R.string.workout_pause)) }
    Button(onClick = { WearAppService.stopWorkout(context) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.workout_stop))
    }
}

@Composable
private fun Summary(sport: WorkoutSport, snapshot: WorkoutSnapshot, onDone: () -> Unit) {
    Text(stringResource(R.string.workout_saved), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(sportName(sport)), style = MaterialTheme.typography.bodySmall)
    Text(clock(snapshot.activeSeconds), style = MaterialTheme.typography.displaySmall)
    Values(sport, snapshot)
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.workout_done)) }
}

@Composable
private fun Values(sport: WorkoutSport, snapshot: WorkoutSnapshot) {
    val lines = buildList {
        (snapshot.heartRateBpm ?: snapshot.averageHeartRateBpm)?.let { add(stringResource(R.string.workout_heart_rate, it)) }
        snapshot.distanceMetres?.let { add(stringResource(R.string.workout_distance, it / 1000.0)) }
        snapshot.steps?.takeIf { sport.steps }?.let { add(stringResource(R.string.workout_steps, it)) }
        snapshot.ascentMetres?.takeIf { it > 0 }?.let { add(stringResource(R.string.workout_ascent, it)) }
    }
    for (line in lines) Text(line, textAlign = TextAlign.Center)
}

private fun clock(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
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
