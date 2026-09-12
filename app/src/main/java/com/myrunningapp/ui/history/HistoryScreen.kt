package com.myrunningapp.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myrunningapp.R
import com.myrunningapp.domain.Units
import com.myrunningapp.ui.activity.icon
import com.myrunningapp.ui.activity.labelRes
import com.myrunningapp.domain.model.Run
import com.myrunningapp.domain.stats.RunTotals
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Every completed activity, newest first, under a header of what the week and
 * all of history add up to.
 */
@Composable
fun HistoryScreen(onRunClick: (Long) -> Unit, viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Held here rather than in the ViewModel: an in-flight confirmation is about
    // this screen, and should not survive the user navigating away from it.
    var pendingDelete by remember { mutableStateOf<Run?>(null) }

    val formatter = remember {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withZone(ZoneId.systemDefault())
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(R.string.history_title),
                style = MaterialTheme.typography.headlineMedium,
            )
        }

        if (state.runs.isNotEmpty()) {
            item { TotalsHeader(thisWeek = state.thisWeek, allTime = state.allTime) }
        }

        when {
            state.loading -> item { CircularProgressIndicator() }
            state.runs.isEmpty() -> item {
                Text(
                    stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        items(state.runs, key = { it.id }) { run ->
            RunRow(
                run = run,
                date = formatter.format(run.startedAt),
                onClick = { onRunClick(run.id) },
                onLongClick = { pendingDelete = run },
            )
        }
    }

    pendingDelete?.let { run ->
        DeleteRunDialog(
            date = formatter.format(run.startedAt),
            onConfirm = {
                viewModel.deleteRun(run.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** This week beside all time — the two spans worth glancing at before a run. */
@Composable
private fun TotalsHeader(thisWeek: RunTotals, allTime: RunTotals) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TotalsColumn(
                label = stringResource(R.string.history_this_week),
                totals = thisWeek,
                modifier = Modifier.weight(1f),
            )
            TotalsColumn(
                label = stringResource(R.string.history_all_time),
                totals = allTime,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TotalsColumn(label: String, totals: RunTotals, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            Units.formatMiles(totals.distanceMeters),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            pluralStringResource(
                R.plurals.history_activity_count,
                totals.runCount,
                totals.runCount,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            Units.formatDuration(totals.movingDurationSec),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RunRow(run: Run, date: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    val activityLabel = stringResource(run.activityType.labelRes)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // Long-press to delete, as the design asks. A tap opens the run, so
            // the destructive gesture is the deliberate one, and it still confirms.
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = run.activityType.icon,
                contentDescription = activityLabel,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(date, style = MaterialTheme.typography.titleMedium)
                if (run.wasRecovered) {
                    Text(stringResource(R.string.run_recovered))
                }
                Text(
                    "${Units.formatMiles(run.distanceMeters)} · " +
                        Units.formatDuration(run.movingDurationSec),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    Units.formatPaceOrSpeed(run.avgPaceSecPerMile, run.activityType) + " · " +
                        stringResource(R.string.stat_calories_value, run.calories),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DeleteRunDialog(date: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_delete_title)) },
        text = { Text(stringResource(R.string.history_delete_message, date)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.history_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
