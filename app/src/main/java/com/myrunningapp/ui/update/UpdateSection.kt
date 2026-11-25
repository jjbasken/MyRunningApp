package com.myrunningapp.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.myrunningapp.R
import com.myrunningapp.data.update.UpdateState

/** What the settings screen needs to draw the updater. */
data class UpdateSectionState(
    val state: UpdateState = UpdateState.Idle,
    val installedVersion: String = "",
    val autoCheckEnabled: Boolean = false,
    /** Installing restarts the app, so it waits for the run to finish. */
    val runActive: Boolean = false,
)

@Composable
fun UpdateSection(
    section: UpdateSectionState,
    onAutoCheckChange: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
    onInstall: () -> Unit,
    onConfirmInstall: () -> Unit,
    onStartOver: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = section.state
    // Re-read on resume: notification permission is changed in system settings.
    val context = LocalContext.current
    var notificationsOn by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        HorizontalDivider()
        Text(
            text = stringResource(R.string.update_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
        Text(
            text = stringResource(R.string.update_current_version, section.installedVersion),
            style = MaterialTheme.typography.bodyMedium,
        )

        if (state is UpdateState.Unsupported) {
            Note(stringResource(R.string.update_unsupported))
            return@Column
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.update_auto_toggle),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = section.autoCheckEnabled, onCheckedChange = onAutoCheckChange)
        }
        Note(stringResource(R.string.update_explainer))
        if (section.autoCheckEnabled && !notificationsOn) Note(stringResource(R.string.update_notifications_off))

        when (state) {
            UpdateState.Idle, UpdateState.Unsupported -> CheckButton(onCheckNow)

            UpdateState.Checking -> Status(stringResource(R.string.update_checking))

            UpdateState.UpToDate -> {
                Status(stringResource(R.string.update_up_to_date))
                CheckButton(onCheckNow)
            }

            is UpdateState.Available -> {
                Status(stringResource(R.string.update_available, state.release.version.toString()))
                ReleaseNotesLink(state.release.pageUrl)
                if (section.runActive) Note(stringResource(R.string.update_blocked_by_run))
                Button(
                    onClick = onInstall,
                    enabled = !section.runActive,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) { Text(stringResource(R.string.update_install)) }
            }

            is UpdateState.Downloading -> {
                Status(stringResource(R.string.update_downloading, (state.fraction * 100).toInt()))
                LinearProgressIndicator(
                    progress = { state.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }

            is UpdateState.Installing -> {
                Status(stringResource(R.string.update_installing))
                // Normally gone in a second; this is the way out if the installer never answers.
                StartOverButton(onStartOver, enabled = !section.runActive)
            }

            is UpdateState.AwaitingConfirmation -> {
                Status(stringResource(R.string.update_awaiting_confirmation))
                if (section.runActive) Note(stringResource(R.string.update_blocked_by_run))
                Button(
                    onClick = onConfirmInstall,
                    enabled = !section.runActive,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) { Text(stringResource(R.string.update_finish_install)) }
                StartOverButton(onStartOver, enabled = !section.runActive)
            }

            is UpdateState.Failed -> {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (state.release != null) {
                    ReleaseNotesLink(state.release.pageUrl)
                    if (section.runActive) Note(stringResource(R.string.update_blocked_by_run))
                    Button(
                        onClick = onInstall,
                        enabled = !section.runActive,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) { Text(stringResource(R.string.update_try_again)) }
                } else {
                    CheckButton(onCheckNow)
                }
            }
        }
    }
}

@Composable
private fun CheckButton(onCheckNow: () -> Unit) {
    OutlinedButton(onClick = onCheckNow, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(stringResource(R.string.update_check_now))
    }
}

@Composable
private fun StartOverButton(onStartOver: () -> Unit, enabled: Boolean) {
    TextButton(onClick = onStartOver, enabled = enabled) {
        Text(stringResource(R.string.update_start_over))
    }
}

@Composable
private fun ReleaseNotesLink(url: String?) {
    if (url == null) return
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) {
        Text(stringResource(R.string.update_release_notes))
    }
}

@Composable
private fun Status(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}
