package com.myrunningapp.ui.track

import android.Manifest
import android.content.Context
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myrunningapp.R
import com.myrunningapp.data.location.LocationTrackingService
import com.myrunningapp.domain.Units
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.RunSessionState
import com.myrunningapp.domain.permission.PermissionGate
import com.myrunningapp.domain.permission.PermissionStatus
import com.myrunningapp.domain.permission.PermissionStep
import com.myrunningapp.domain.tracking.RunSnapshot
import com.myrunningapp.ui.permissions.BackgroundRationaleDialog
import com.myrunningapp.ui.permissions.ForegroundRationaleDialog
import com.myrunningapp.ui.permissions.appSettingsIntent
import com.myrunningapp.ui.permissions.backgroundLocationNeedsSettings
import com.myrunningapp.ui.permissions.foregroundPermissions
import com.myrunningapp.ui.permissions.locationGrants

@Composable
fun TrackScreen(onRunClick: (Long) -> Unit = {}, viewModel: TrackViewModel = hiltViewModel()) {
    val route by viewModel.route.collectAsStateWithLifecycle()
    val mapPoints = remember(route) {
        route.map { RouteCoordinate(it.fix.latitude, it.fix.longitude, it.segmentIndex) }
    }
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val startRequest by viewModel.startRequest.collectAsStateWithLifecycle()
    val lastFinishedRunId by viewModel.lastFinishedRunId.collectAsStateWithLifecycle()

    // The permission round trip loses the button's argument, so hold on to it.
    var pendingCountdown by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<PermissionStep?>(null) }
    var foregroundDenied by remember { mutableStateOf(false) }

    // Re-read on every resume: a trip to Settings grants permissions without
    // ever calling back into a launcher.
    var grants by remember { mutableStateOf(context.locationGrants()) }
    LifecycleResumeEffect(Unit) {
        grants = context.locationGrants()
        onPauseOrDispose { }
    }

    val status = PermissionStatus(
        fineLocationGranted = grants.fineLocationGranted,
        backgroundLocationGranted = grants.backgroundLocationGranted,
        notificationsGranted = grants.notificationsGranted,
        backgroundAlreadyAsked = state.backgroundLocationAsked,
        backgroundWarningDismissed = state.backgroundWarningDismissed,
    )

    // Walks the gate forward one step at a time, starting the run once nothing
    // is left to ask. Reads the grants fresh rather than trusting `status`:
    // that was captured last composition, and a permission may have been
    // granted since — by a launcher callback, or over in Settings.
    fun proceed() {
        val fresh = context.locationGrants()
        grants = fresh
        val step = PermissionGate.next(
            status.copy(
                fineLocationGranted = fresh.fineLocationGranted,
                backgroundLocationGranted = fresh.backgroundLocationGranted,
                notificationsGranted = fresh.notificationsGranted,
            ),
        )
        when (step) {
            PermissionStep.Ready -> viewModel.requestStart(pendingCountdown)
            else -> dialog = step
        }
    }

    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        grants = context.locationGrants()
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            foregroundDenied = false
            proceed()
        } else {
            foregroundDenied = true
        }
    }

    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        grants = context.locationGrants()
        viewModel.onBackgroundLocationAsked()
        // Granted or not, the run itself was never blocked on this.
        viewModel.requestStart(pendingCountdown)
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

    // Only while a run is actually going: a phone that never sleeps on the
    // history screen would be a bug, not a feature.
    val view = LocalView.current
    val keepScreenOn = state.keepScreenOn && state.snapshot.isActive
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val onStart: (Boolean) -> Unit = { withCountdown ->
        pendingCountdown = withCountdown
        proceed()
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
            if (foregroundDenied) {
                WarningBanner(
                    message = stringResource(R.string.permission_denied),
                    actionLabel = stringResource(R.string.permission_open_settings),
                    onAction = { context.startActivity(appSettingsIntent(context)) },
                )
            } else if (PermissionGate.showsBackgroundWarning(status)) {
                WarningBanner(
                    message = stringResource(R.string.track_background_warning),
                    actionLabel = stringResource(R.string.action_dismiss),
                    onAction = viewModel::dismissBackgroundWarning,
                )
            }
            RouteMap(
                points = mapPoints,
                currentPosition = state.snapshot.lastFix?.let {
                    RouteCoordinate(it.latitude, it.longitude, state.snapshot.segmentIndex)
                },
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

    when (dialog) {
        PermissionStep.ForegroundRationale -> ForegroundRationaleDialog(
            onAllow = {
                dialog = null
                foregroundLauncher.launch(foregroundPermissions())
            },
            onDismiss = { dialog = null },
        )
        PermissionStep.BackgroundRationale -> BackgroundRationaleDialog(
            needsSettings = backgroundLocationNeedsSettings(),
            onAllow = {
                dialog = null
                viewModel.onBackgroundLocationAsked()
                if (backgroundLocationNeedsSettings()) {
                    // The user leaves for Settings; the run waits for them to
                    // come back and press Start, rather than beginning unseen.
                    context.startActivity(appSettingsIntent(context))
                } else {
                    backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            },
            onSkip = {
                dialog = null
                viewModel.onBackgroundLocationAsked()
                viewModel.requestStart(pendingCountdown)
            },
        )
        else -> Unit
    }
}

/** A standing explanation of something that is off, with the one action that fixes it. */
@Composable
private fun WarningBanner(message: String, actionLabel: String, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
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
