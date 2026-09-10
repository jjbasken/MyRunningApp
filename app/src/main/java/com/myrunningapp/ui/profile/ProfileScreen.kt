package com.myrunningapp.ui.profile

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myrunningapp.R
import com.myrunningapp.data.export.RunExporter
import com.myrunningapp.domain.Units
import com.myrunningapp.domain.health.HealthSyncUiState
import com.myrunningapp.domain.model.CountdownLength
import com.myrunningapp.domain.model.Sex
import com.myrunningapp.ui.export.DocumentExportEffect
import com.myrunningapp.ui.health.HealthSyncSection
import com.myrunningapp.ui.theme.MyRunningTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(viewModel: ProfileViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val savedMessage = stringResource(R.string.profile_saved)

    val healthPermissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted -> viewModel.onHealthPermissionResult(granted) }

    // Re-read on every resume: a trip to Health Connect's own settings changes
    // permission state without ever calling back into a launcher.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshHealthState()
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            val message = when (event) {
                is ProfileViewModel.ProfileEvent.Saved -> savedMessage
                is ProfileViewModel.ProfileEvent.ValidationError -> event.message
            }
            scope.launch { snackbarHostState.showSnackbar(message) }
        }
    }

    val pendingExport by viewModel.pendingExport.collectAsStateWithLifecycle()
    val failedMessage = stringResource(R.string.export_failed)
    val exportedMessage = stringResource(R.string.export_saved, pendingExport?.fileName.orEmpty())

    DocumentExportEffect(
        document = pendingExport,
        mimeType = RunExporter.JSON_MIME,
        onCancelled = viewModel::onExportHandled,
        onFinished = { saved ->
            scope.launch {
                snackbarHostState.showSnackbar(if (saved) exportedMessage else failedMessage)
            }
            viewModel.onExportHandled()
        },
    )

    ProfileContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onSave = viewModel::saveProfile,
        onCountdownChange = viewModel::setCountdownLength,
        onVoiceChange = viewModel::setVoiceEnabled,
        onKeepScreenOnChange = viewModel::setKeepScreenOn,
        onPaceColorChange = viewModel::setColorRouteByPace,
        onExportAll = viewModel::exportEverything,
        onHealthToggle = { enabled ->
            viewModel.setHealthSyncEnabled(enabled)
            if (enabled && !state.preferences.healthPermissionAsked) {
                healthPermissionLauncher.launch(viewModel.healthPermissionsToRequest)
            }
        },
        onHealthRequestPermission = {
            // Android stops showing the dialog after two declines; once the ask is
            // spent, Health Connect's own settings are the only way through.
            if (state.preferences.healthPermissionAsked) {
                openHealthConnectSettings(context)
            } else {
                healthPermissionLauncher.launch(viewModel.healthPermissionsToRequest)
            }
        },
        onHealthSyncNow = viewModel::syncNow,
        onOpenHealthConnect = { openHealthConnectSettings(context) },
    )
}

/**
 * Opens Health Connect's own settings screen. A stale or old provider may not
 * export this action — falling back to doing nothing beats crashing the app
 * from a settings link.
 */
private fun openHealthConnectSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileContent(
    state: ProfileUiState,
    snackbarHostState: SnackbarHostState,
    onSave: (weightLb: String, heightIn: String, age: String, sex: Sex) -> Unit,
    onCountdownChange: (CountdownLength) -> Unit,
    onVoiceChange: (Boolean) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onPaceColorChange: (Boolean) -> Unit,
    onExportAll: () -> Unit,
    onHealthToggle: (Boolean) -> Unit,
    onHealthRequestPermission: () -> Unit,
    onHealthSyncNow: () -> Unit,
    onOpenHealthConnect: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.profile_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        var weight by rememberSaveable { mutableStateOf("") }
        var height by rememberSaveable { mutableStateOf("") }
        var age by rememberSaveable { mutableStateOf("") }
        var sex by rememberSaveable { mutableStateOf(Sex.MALE) }
        var seeded by rememberSaveable { mutableStateOf(false) }

        // Seed the form once, from whatever is stored (or the defaults).
        LaunchedEffect(state.loading, state.profile) {
            if (!seeded && !state.loading) {
                weight = Units.roundLb(state.profile.weightKg).toString()
                height = Units.roundInches(state.profile.heightCm).toString()
                age = state.profile.age.toString()
                sex = state.profile.sex
                seeded = true
            }
        }

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            if (state.loading) {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }

            SectionHeader(stringResource(R.string.profile_title))

            NumberField(
                value = weight,
                onValueChange = { weight = it },
                label = stringResource(R.string.profile_weight),
            )
            NumberField(
                value = height,
                onValueChange = { height = it },
                label = stringResource(R.string.profile_height),
            )
            NumberField(
                value = age,
                onValueChange = { age = it },
                label = stringResource(R.string.profile_age),
                decimal = false,
            )

            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.profile_sex), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = sex == Sex.MALE,
                    onClick = { sex = Sex.MALE },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text(stringResource(R.string.profile_sex_male)) }
                SegmentedButton(
                    selected = sex == Sex.FEMALE,
                    onClick = { sex = Sex.FEMALE },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text(stringResource(R.string.profile_sex_female)) }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onSave(weight, height, age, sex) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.profile_save)) }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionHeader(stringResource(R.string.profile_prefs))

            Text(stringResource(R.string.pref_countdown), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    CountdownLength.OFF to stringResource(R.string.pref_countdown_off),
                    CountdownLength.TEN to stringResource(R.string.pref_countdown_10),
                    CountdownLength.THIRTY to stringResource(R.string.pref_countdown_30),
                )
                options.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = state.preferences.countdownLength == value,
                        onClick = { onCountdownChange(value) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    ) { Text(label) }
                }
            }

            Spacer(Modifier.height(8.dp))
            SwitchRow(
                label = stringResource(R.string.pref_voice),
                checked = state.preferences.voiceAnnouncementsEnabled,
                onCheckedChange = onVoiceChange,
            )
            SwitchRow(
                label = stringResource(R.string.pref_keep_screen_on),
                checked = state.preferences.keepScreenOnDuringRun,
                onCheckedChange = onKeepScreenOnChange,
            )
            SwitchRow(
                label = stringResource(R.string.pref_pace_color),
                checked = state.preferences.colorRouteByPace,
                onCheckedChange = onPaceColorChange,
            )

            HealthSyncSection(
                state = state.healthSync,
                enabled = state.healthSync !is HealthSyncUiState.Off,
                onToggle = onHealthToggle,
                onRequestPermission = onHealthRequestPermission,
                onSyncNow = onHealthSyncNow,
                onOpenHealthConnect = onOpenHealthConnect,
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            SectionHeader(stringResource(R.string.profile_data))
            Text(
                stringResource(R.string.profile_export_all_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onExportAll, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.profile_export_all))
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    decimal: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            val filtered = input.filter { it.isDigit() || (decimal && it == '.') }
            onValueChange(filtered)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Preview
@Composable
private fun ProfileContentPreview() {
    MyRunningTheme {
        ProfileContent(
            state = ProfileUiState(loading = false),
            snackbarHostState = remember { SnackbarHostState() },
            onSave = { _, _, _, _ -> },
            onCountdownChange = {},
            onVoiceChange = {},
            onKeepScreenOnChange = {},
            onPaceColorChange = {},
            onExportAll = {},
            onHealthToggle = {},
            onHealthRequestPermission = {},
            onHealthSyncNow = {},
            onOpenHealthConnect = {},
        )
    }
}
