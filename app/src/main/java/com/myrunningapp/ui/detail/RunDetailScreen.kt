package com.myrunningapp.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myrunningapp.R
import com.myrunningapp.domain.Units
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.Run
import com.myrunningapp.domain.model.Split
import com.myrunningapp.data.export.RunExporter
import com.myrunningapp.ui.export.DocumentExportEffect
import com.myrunningapp.ui.map.RouteMap
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** One saved activity: where it went, what it added up to, and mile by mile. */
@Composable
fun RunDetailScreen(onBack: () -> Unit, viewModel: RunDetailViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    val pendingExport by viewModel.pendingExport.collectAsStateWithLifecycle()
    var confirmingDelete by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val failedMessage = stringResource(R.string.export_failed)
    // Read while the document is still pending: the callback fires after the
    // ViewModel has cleared it, and the message names the file that was written.
    val savedMessage = stringResource(R.string.export_saved, pendingExport?.fileName.orEmpty())

    // Deleting leaves nothing to show, so the screen sees itself out.
    LaunchedEffect(deleted) { if (deleted) onBack() }

    DocumentExportEffect(
        document = pendingExport,
        mimeType = RunExporter.GPX_MIME,
        onCancelled = viewModel::onExportHandled,
        onFinished = { saved ->
            scope.launch {
                snackbarHostState.showSnackbar(if (saved) savedMessage else failedMessage)
            }
            viewModel.onExportHandled()
        },
    )

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.route_back)) }

            when {
                state.loading -> CircularProgressIndicator()
                state.run == null -> Text(stringResource(R.string.route_not_found))
                else -> {
                    val run = checkNotNull(state.run)
                    Text(
                        remember {
                            DateTimeFormatter
                                .ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.SHORT)
                                .withZone(ZoneId.systemDefault())
                        }.format(run.startedAt),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    // A fixed height, not weight(): this column scrolls, and a map
                    // with no intrinsic height would otherwise collapse to nothing.
                    RouteMap(
                        points = state.points,
                        modifier = Modifier.fillMaxWidth().height(280.dp),
                        colorByPace = state.colorRouteByPace,
                    )
                    if (run.wasRecovered) {
                        Text(stringResource(R.string.run_recovered_detail))
                    }
                    StatBlock(run)
                    ActivityTypePicker(
                        selected = run.activityType,
                        onSelect = viewModel::setActivityType,
                    )
                    SplitsTable(state.splits)
                    OutlinedButton(
                        onClick = viewModel::exportRun,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.detail_export)) }
                    TextButton(onClick = { confirmingDelete = true }) {
                        Text(
                            stringResource(R.string.detail_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = { Text(stringResource(R.string.detail_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    viewModel.deleteRun()
                }) { Text(stringResource(R.string.history_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun StatBlock(run: Run) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatRow(stringResource(R.string.stat_distance), Units.formatMiles(run.distanceMeters))
            StatRow(
                stringResource(R.string.stat_moving_time),
                Units.formatDuration(run.movingDurationSec),
            )
            StatRow(
                stringResource(R.string.stat_elapsed_time),
                Units.formatDuration(run.elapsedDurationSec),
            )
            StatRow(
                stringResource(R.string.stat_avg_pace),
                Units.formatPace(run.avgPaceSecPerMile),
            )
            StatRow(
                stringResource(R.string.stat_calories),
                stringResource(R.string.stat_calories_value, run.calories),
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Corrects a walk logged as a run. The repository re-estimates calories when the
 * type changes, so the number above updates with the label.
 */
@Composable
private fun ActivityTypePicker(selected: ActivityType, onSelect: (ActivityType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.detail_activity_type),
            style = MaterialTheme.typography.labelLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActivityType.entries.forEach { type ->
                FilterChip(
                    selected = type == selected,
                    onClick = { onSelect(type) },
                    label = {
                        Text(
                            stringResource(
                                if (type == ActivityType.RUN) R.string.activity_run
                                else R.string.activity_walk,
                            ),
                        )
                    },
                )
            }
        }
    }
}

/**
 * The same numbers that were spoken during the run. The final row is usually a
 * partial mile — never announced, but it is part of the run and belongs here.
 */
@Composable
private fun SplitsTable(splits: List<Split>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.detail_splits), style = MaterialTheme.typography.titleMedium)
        if (splits.isEmpty()) {
            Text(
                stringResource(R.string.detail_no_splits),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }
        SplitRow(
            stringResource(R.string.detail_split_mile),
            stringResource(R.string.detail_split_distance),
            stringResource(R.string.detail_split_pace),
            stringResource(R.string.detail_split_time),
            header = true,
        )
        HorizontalDivider()
        splits.forEach { split ->
            SplitRow(
                split.splitNumber.toString(),
                Units.formatMiles(split.distanceMeters),
                Units.formatPace(split.paceSecPerMile),
                Units.formatDuration(split.durationSec),
            )
        }
    }
}

@Composable
private fun SplitRow(
    number: String,
    distance: String,
    pace: String,
    time: String,
    header: Boolean = false,
) {
    val style = if (header) {
        MaterialTheme.typography.labelMedium
    } else {
        // Tabular-ish: a monospace family keeps the pace column from dancing.
        MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(number, style = style, modifier = Modifier.weight(0.6f))
        Text(distance, style = style, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        Text(pace, style = style, modifier = Modifier.weight(1.3f), textAlign = TextAlign.End)
        Text(time, style = style, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}
