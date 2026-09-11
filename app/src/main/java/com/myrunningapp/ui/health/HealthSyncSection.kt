package com.myrunningapp.ui.health

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.myrunningapp.R
import com.myrunningapp.domain.health.HealthSyncUiState

/**
 * The Health Connect part of the settings screen.
 *
 * Draws nothing at all when Health Connect is absent: a toggle that cannot work
 * is worse than no toggle. Everything it shows comes from
 * [com.myrunningapp.domain.health.HealthSyncStatus], which is where the
 * decisions are tested.
 */
@Composable
fun HealthSyncSection(
    state: HealthSyncUiState,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onSyncNow: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is HealthSyncUiState.Hidden) return

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        HorizontalDivider()
        Text(
            text = stringResource(R.string.health_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )

        if (state is HealthSyncUiState.UpdateRequired) {
            Text(
                text = stringResource(R.string.health_update_required),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onOpenHealthConnect) {
                Text(stringResource(R.string.health_open_settings))
            }
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.health_toggle),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        Text(
            text = stringResource(R.string.health_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (state) {
            is HealthSyncUiState.Off -> Text(
                text = stringResource(R.string.health_off_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            is HealthSyncUiState.NeedsPermission -> {
                Text(
                    text = stringResource(R.string.health_needs_permission),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = onRequestPermission) {
                    Text(stringResource(R.string.health_grant))
                }
            }

            is HealthSyncUiState.Working -> {
                Text(
                    text = pluralStringResource(R.plurals.health_waiting, state.pending, state.pending),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                // The manual escape hatch: a page-sized drain can otherwise sit
                // waiting on the worker's own schedule with no way to nudge it.
                TextButton(onClick = onSyncNow) {
                    Text(stringResource(R.string.health_sync_now))
                }
            }

            is HealthSyncUiState.UpToDate -> Text(
                text = pluralStringResource(R.plurals.health_synced, state.synced, state.synced),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )

            is HealthSyncUiState.Failed -> {
                Text(
                    text = pluralStringResource(R.plurals.health_failed, state.failed, state.failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = onSyncNow) {
                    Text(stringResource(R.string.health_sync_now))
                }
            }

            HealthSyncUiState.Hidden, HealthSyncUiState.UpdateRequired -> Unit
        }
    }
}
