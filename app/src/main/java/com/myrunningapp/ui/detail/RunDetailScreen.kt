package com.myrunningapp.ui.detail

import androidx.compose.foundation.layout.*
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
import com.myrunningapp.ui.map.RouteMap
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun RunDetailScreen(onBack: () -> Unit, viewModel: RunDetailViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.route_back)) }
        when {
            state.loading -> CircularProgressIndicator()
            state.run == null -> Text(stringResource(R.string.route_not_found))
            else -> {
                val run = checkNotNull(state.run)
                Text(
                    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                        .withZone(ZoneId.systemDefault()).format(run.startedAt),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text("${Units.formatMiles(run.distanceMeters)} · ${Units.formatDuration(run.movingDurationSec)}")
                RouteMap(points = state.points, modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}
