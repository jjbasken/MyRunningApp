package com.myrunningapp.ui.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myrunningapp.R
import com.myrunningapp.domain.Units
import com.myrunningapp.domain.model.ActivityType
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Basic route browser; totals and management actions arrive in milestone 5. */
@Composable
fun HistoryScreen(onRunClick: (Long) -> Unit, viewModel: HistoryViewModel = hiltViewModel()) {
    val runs by viewModel.runs.collectAsStateWithLifecycle()
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.saved_routes), style = MaterialTheme.typography.headlineMedium) }
        if (runs == null) {
            item { CircularProgressIndicator() }
        } else if (runs.orEmpty().isEmpty()) {
            item { Text(stringResource(R.string.no_saved_routes)) }
        }
        items(runs.orEmpty(), key = { it.id }) { run ->
            Card(onClick = { onRunClick(run.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(formatter.format(run.startedAt), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(if (run.activityType == ActivityType.RUN) R.string.activity_run else R.string.activity_walk))
                    Text("${Units.formatMiles(run.distanceMeters)} · ${Units.formatDuration(run.movingDurationSec)}")
                }
            }
        }
    }
}
