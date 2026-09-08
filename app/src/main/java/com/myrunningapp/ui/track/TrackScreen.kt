package com.myrunningapp.ui.track

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import com.myrunningapp.ui.map.RouteMap
import com.myrunningapp.ui.map.RouteCoordinate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myrunningapp.R
import com.myrunningapp.data.location.LocationTrackingService
import com.myrunningapp.domain.Units
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.RunSessionState
import com.myrunningapp.domain.tracking.RunSnapshot

@Composable
fun TrackScreen(onRunClick: (Long) -> Unit = {}, viewModel: TrackViewModel = hiltViewModel()) {
    val route by viewModel.route.collectAsStateWithLifecycle()
    val mapPoints = remember(route) { route.map { RouteCoordinate(it.fix.latitude, it.fix.longitude, it.segmentIndex) } }
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val startRequest by viewModel.startRequest.collectAsStateWithLifecycle()
    val lastFinishedRunId by viewModel.lastFinishedRunId.collectAsStateWithLifecycle()

    // The permission round trip loses the button's argument, so hold on to it.
    var pendingCountdown by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            viewModel.requestStart(withCountdown = pendingCountdown)
        }
    }

    // The ViewModel has no Context, so starting the service happens here.
    LaunchedEffect(startRequest) {
        val request = startRequest ?: return@LaunchedEffect
        ContextCompat.startForegroundService(
            context,
            LocationTrackingService.startIntent(
                context = context,
                activityType = request.activityType,
                countdownSeconds = request.countdownSeconds,
                weightKg = request.weightKg,
            ),
        )
        viewModel.onStartHandled()
    }

    val onStart: (Boolean) -> Unit = { withCountdown ->
        if (context.hasLocationPermission()) {
            viewModel.requestStart(withCountdown)
        } else {
            pendingCountdown = withCountdown
            permissionLauncher.launch(requiredPermissions())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RouteMap(
                points = mapPoints,
                currentPosition = state.snapshot.lastFix?.let { RouteCoordinate(it.latitude, it.longitude, state.snapshot.segmentIndex) },
                live = true,
                modifier = Modifier.fillMaxWidth().height(280.dp),
            )
            StatsBlock(state.snapshot)
        }

        when (state.snapshot.state) {
            RunSessionState.IDLE, RunSessionState.FINISHED -> {
                if (lastFinishedRunId != null) {
                    OutlinedButton(onClick = { lastFinishedRunId?.let(onRunClick) }) {
                        Text(stringResource(R.string.view_saved_route))
                    }
                }
                IdleControls(
                    selected = state.selectedActivityType,
                    countdownSeconds = state.countdownSeconds,
                    onSelect = viewModel::selectActivityType,
                    onStart = onStart,
                )
            }
            RunSessionState.COUNTDOWN -> CountdownControls(
                onSkip = { context.sendCommand(LocationTrackingService.ACTION_SKIP_COUNTDOWN) },
                onCancel = { context.sendCommand(LocationTrackingService.ACTION_CANCEL) },
            )
            RunSessionState.TRACKING -> RunningControls(
                onPause = { context.sendCommand(LocationTrackingService.ACTION_PAUSE) },
                onFinish = { context.sendCommand(LocationTrackingService.ACTION_FINISH) },
            )
            RunSessionState.PAUSED -> PausedControls(
                onResume = { context.sendCommand(LocationTrackingService.ACTION_RESUME) },
                onFinish = { context.sendCommand(LocationTrackingService.ACTION_FINISH) },
            )
        }
    }
}

@Composable
private fun StatsBlock(snapshot: RunSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (snapshot.state == RunSessionState.COUNTDOWN) {
                Text(
                    text = snapshot.countdownSecondsRemaining.toString(),
                    style = MaterialTheme.typography.displayLarge,
                )
                Text(
                    text = stringResource(R.string.track_countdown_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                return@Column
            }

            Text(
                text = Units.formatMiles(snapshot.distanceMeters),
                style = MaterialTheme.typography.displayMedium,
            )
            Stat(
                label = stringResource(R.string.track_moving_time),
                value = Units.formatDuration(snapshot.movingDurationSec),
            )
            Stat(
                label = stringResource(R.string.track_elapsed_time),
                value = Units.formatDuration(snapshot.elapsedDurationSec),
            )
            Stat(
                label = stringResource(R.string.track_avg_pace),
                value = Units.formatPace(snapshot.avgPaceSecPerMile),
            )
            Stat(
                label = stringResource(R.string.track_splits),
                value = snapshot.completedSplits.size.toString(),
            )
            if (snapshot.state == RunSessionState.PAUSED) {
                Text(
                    text = stringResource(R.string.track_paused),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun IdleControls(
    selected: ActivityType,
    countdownSeconds: Int,
    onSelect: (ActivityType) -> Unit,
    onStart: (Boolean) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActivityType.entries.forEach { type ->
            FilterChip(
                selected = type == selected,
                onClick = { onSelect(type) },
                label = { Text(stringResource(type.labelRes())) },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { onStart(false) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.track_start_now))
    }
    if (countdownSeconds > 0) {
        OutlinedButton(
            onClick = { onStart(true) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.track_start_in, countdownSeconds))
        }
    }
}

@Composable
private fun CountdownControls(
    onSkip: () -> Unit,
    onCancel: () -> Unit,
) {
    Button(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.track_skip_countdown))
    }
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.track_cancel))
    }
}

@Composable
private fun RunningControls(onPause: () -> Unit, onFinish: () -> Unit) {
    Button(onClick = onPause, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.track_pause))
    }
    OutlinedButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.track_finish))
    }
}

@Composable
private fun PausedControls(onResume: () -> Unit, onFinish: () -> Unit) {
    Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.track_resume))
    }
    OutlinedButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.track_finish))
    }
}

private fun ActivityType.labelRes(): Int = when (this) {
    ActivityType.RUN -> R.string.activity_run
    ActivityType.WALK -> R.string.activity_walk
}

private fun Context.sendCommand(action: String) {
    ContextCompat.startForegroundService(
        this,
        LocationTrackingService.commandIntent(this, action),
    )
}

private fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Background location is asked for separately (Android sends the user to Settings)
 * and is a milestone 6 concern; without it tracking still works with the app open.
 */
private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()
