package com.myrunningapp.ui.plan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myrunningapp.R
import com.myrunningapp.domain.Units
import com.myrunningapp.domain.plan.CoachTip
import com.myrunningapp.domain.plan.ParsedPlan
import com.myrunningapp.domain.plan.PlanImportFailure
import com.myrunningapp.domain.plan.PlanOverview
import com.myrunningapp.domain.plan.PlanRequest
import com.myrunningapp.domain.plan.PlannedWorkout
import com.myrunningapp.domain.plan.TrainingPlan
import com.myrunningapp.domain.plan.WorkoutStatus
import com.myrunningapp.ui.export.readBytes
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle

/** A plan file is a few hundred KB at most; anything past this is not a plan. */
private const val MAX_PLAN_BYTES = 2L * 1024 * 1024

/**
 * The training plan: getting one from an AI assistant, importing it, and
 * following it week by week. The coaching during a run happens on the track
 * screen; this is the between-runs view.
 */
@Composable
fun PlanScreen(onRunClick: (Long) -> Unit, viewModel: PlanViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val appContext = context.applicationContext
    val resources = LocalResources.current
    val clipboardEmpty = stringResource(R.string.plan_clipboard_empty)
    val promptCopied = stringResource(R.string.plan_prompt_copied)
    val unreadable = stringResource(R.string.plan_import_unreadable)

    var showPrompt by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var openWorkoutId by rememberSaveable { mutableStateOf<Long?>(null) }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFile { appContext.readBytes(uri, MAX_PLAN_BYTES) }
    }
    val onPaste: () -> Unit = {
        val text = context.clipboardText()
        if (text.isNullOrBlank()) {
            scope.launch { snackbarHostState.showSnackbar(clipboardEmpty) }
        } else {
            viewModel.importText(text)
        }
    }
    val onOpenFile: () -> Unit = { fileLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }

    LaunchedEffect(viewModel) {
        viewModel.importEvents.collect { event ->
            val message = when (event) {
                is PlanViewModel.ImportEvent.Imported -> resources.getQuantityString(
                    if (event.merged) R.plurals.plan_imported_merged else R.plurals.plan_imported,
                    event.workouts, event.workouts,
                )
                is PlanViewModel.ImportEvent.Failed -> resources.importFailureMessage(event.failure, event.detail)
                PlanViewModel.ImportEvent.Unreadable -> unreadable
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0),
    ) { innerPadding ->
        val plan = state.plan
        val overview = state.overview
        when {
            state.loading -> Column(Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
                CircularProgressIndicator()
            }
            plan == null || overview == null -> NoPlan(
                modifier = Modifier.padding(innerPadding),
                importing = importing,
                onGetPrompt = { showPrompt = true },
                onPaste = onPaste,
                onOpenFile = onOpenFile,
            )
            else -> PlanContent(
                modifier = Modifier.padding(innerPadding),
                plan = plan,
                overview = overview,
                weeks = state.weeks,
                today = state.today,
                importing = importing,
                onWorkoutClick = { openWorkoutId = it.id },
                onGetPrompt = { showPrompt = true },
                onPaste = onPaste,
                onOpenFile = onOpenFile,
                onDelete = { confirmDelete = true },
            )
        }
    }

    if (showPrompt) {
        PromptDialog(
            today = state.today.takeIf { it != LocalDate.MIN } ?: viewModel.today(),
            raceDate = state.plan?.raceDate,
            buildPrompt = viewModel::buildPrompt,
            onCopied = {
                showPrompt = false
                scope.launch { snackbarHostState.showSnackbar(promptCopied) }
            },
            onDismiss = { showPrompt = false },
        )
    }

    pendingImport?.let { parsed ->
        ReplaceOrMergeDialog(
            parsed = parsed,
            onReplace = { viewModel.confirmImport(merge = false) },
            onMerge = { viewModel.confirmImport(merge = true) },
            onCancel = viewModel::cancelImport,
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.plan_delete_title)) },
            text = { Text(stringResource(R.string.plan_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deletePlan()
                }) { Text(stringResource(R.string.plan_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // Looked up fresh from state so the dialog follows a status change it caused.
    val openWorkout = openWorkoutId?.let { id -> state.weeks.firstNotNullOfOrNull { w -> w.workouts.find { it.id == id } } }
    if (openWorkout != null) {
        WorkoutDialog(
            workout = openWorkout,
            onStatus = { viewModel.setStatus(openWorkout.id, it) },
            onViewRun = { runId ->
                openWorkoutId = null
                onRunClick(runId)
            },
            onDismiss = { openWorkoutId = null },
        )
    }
}

// --- no plan yet ------------------------------------------------------------------

@Composable
private fun NoPlan(
    modifier: Modifier,
    importing: Boolean,
    onGetPrompt: () -> Unit,
    onPaste: () -> Unit,
    onOpenFile: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.plan_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.plan_empty_intro), style = MaterialTheme.typography.bodyLarge)
        Step(number = 1, text = stringResource(R.string.plan_step_prompt)) {
            Button(onClick = onGetPrompt) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.plan_get_prompt))
            }
        }
        Step(number = 2, text = stringResource(R.string.plan_step_ask)) {}
        Step(number = 3, text = stringResource(R.string.plan_step_import)) {
            if (importing) {
                CircularProgressIndicator(Modifier.size(24.dp))
            } else {
                ImportButtons(onPaste = onPaste, onOpenFile = onOpenFile)
            }
        }
        Text(
            stringResource(R.string.plan_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Step(number: Int, text: String, action: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.plan_step_label, number),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(text, style = MaterialTheme.typography.bodyMedium)
            action()
        }
    }
}

@Composable
private fun ImportButtons(onPaste: () -> Unit, onOpenFile: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onPaste) {
            Icon(Icons.Filled.ContentPaste, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.plan_paste))
        }
        OutlinedButton(onClick = onOpenFile) {
            Icon(Icons.Filled.FileOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.plan_open_file))
        }
    }
}

// --- the plan ------------------------------------------------------------------------

@Composable
private fun PlanContent(
    modifier: Modifier,
    plan: TrainingPlan,
    overview: PlanOverview,
    weeks: List<PlanWeek>,
    today: LocalDate,
    importing: Boolean,
    onWorkoutClick: (PlannedWorkout) -> Unit,
    onGetPrompt: () -> Unit,
    onPaste: () -> Unit,
    onOpenFile: () -> Unit,
    onDelete: () -> Unit,
) {
    val listState = rememberLazyListState()
    // The fixed items above the weeks: header, coach, today.
    val headerItems = 3
    LaunchedEffect(plan.id) {
        val current = weeks.indexOfFirst { it.isCurrent }
        if (current > 0) listState.scrollToItem(headerItems + current)
    }
    val dayFormatter = remember { DateTimeFormatter.ofPattern("EEE d MMM") }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PlanHeader(plan, overview, importing, onGetPrompt, onPaste, onOpenFile, onDelete)
        }
        item { CoachCard(overview.tips) }
        item {
            if (overview.today.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.plan_today), style = MaterialTheme.typography.titleMedium)
                    overview.today.forEach { workout ->
                        WorkoutRow(workout, today, dayFormatter, onClick = { onWorkoutClick(workout) })
                    }
                    if (overview.todaysRun != null) {
                        Text(
                            stringResource(R.string.plan_today_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                overview.nextRun?.let { next ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.plan_next), style = MaterialTheme.typography.titleMedium)
                        WorkoutRow(next, today, dayFormatter, onClick = { onWorkoutClick(next) })
                    }
                }
            }
        }
        items(weeks, key = { it.number }) { week ->
            WeekCard(week, today, dayFormatter, onWorkoutClick)
        }
    }
}

@Composable
private fun PlanHeader(
    plan: TrainingPlan,
    overview: PlanOverview,
    importing: Boolean,
    onGetPrompt: () -> Unit,
    onPaste: () -> Unit,
    onOpenFile: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(plan.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (importing) CircularProgressIndicator(Modifier.size(24.dp))
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.plan_menu))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.plan_paste)) },
                        onClick = { menuOpen = false; onPaste() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.plan_open_file)) },
                        onClick = { menuOpen = false; onOpenFile() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.plan_get_prompt)) },
                        onClick = { menuOpen = false; onGetPrompt() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.plan_delete_title)) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
        val daysToRace = overview.daysToRace
        if (plan.raceDate != null && daysToRace != null && daysToRace >= 0) {
            val date = plan.raceDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
            Text(
                if (daysToRace == 0L) stringResource(R.string.plan_race_today)
                else pluralStringResource(R.plurals.plan_race_in_days, daysToRace.toInt(), daysToRace.toInt(), date),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (overview.totalRuns > 0) {
            LinearProgressIndicator(
                progress = { overview.completedRuns.toFloat() / overview.totalRuns },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.plan_progress, overview.completedRuns, overview.totalRuns),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (overview.weekPlannedMeters > 0.0) {
            Text(
                stringResource(
                    R.string.plan_this_week,
                    Units.formatMiles(overview.weekCompletedMeters),
                    Units.formatMiles(overview.weekPlannedMeters),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun CoachCard(tips: List<CoachTip>) {
    if (tips.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.plan_coach),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            tips.forEach { tip ->
                Text(
                    tipText(tip),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun tipText(tip: CoachTip): String = when (tip) {
    is CoachTip.NotStarted -> pluralStringResource(
        R.plurals.coach_not_started, tip.daysUntilStart.toInt(), tip.daysUntilStart.toInt(),
    )
    is CoachTip.RaceWeek -> stringResource(R.string.coach_race_week)
    is CoachTip.Taper -> stringResource(R.string.coach_taper)
    is CoachTip.MissedRuns -> pluralStringResource(R.plurals.coach_missed, tip.count, tip.count)
    is CoachTip.EasyRunsTooFast -> stringResource(R.string.coach_easy_too_fast)
    CoachTip.LongRunToday -> stringResource(R.string.coach_long_run_today)
    CoachTip.RestDay -> stringResource(R.string.coach_rest_day)
    is CoachTip.OnTrack -> stringResource(R.string.coach_on_track, tip.completed, tip.due)
    CoachTip.PlanFinished -> stringResource(R.string.coach_finished)
}

@Composable
private fun WeekCard(
    week: PlanWeek,
    today: LocalDate,
    dayFormatter: DateTimeFormatter,
    onWorkoutClick: (PlannedWorkout) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (week.isCurrent) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.plan_week, week.number, week.start.format(dayFormatter)),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (week.plannedMeters > 0.0) {
                    Text(
                        stringResource(
                            R.string.plan_week_miles,
                            Units.formatMiles(week.completedMeters),
                            Units.formatMiles(week.plannedMeters),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.size(4.dp))
            week.workouts.forEachIndexed { index, workout ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                WorkoutRow(workout, today, dayFormatter, onClick = { onWorkoutClick(workout) }, inset = true)
            }
        }
    }
}

@Composable
private fun WorkoutRow(
    workout: PlannedWorkout,
    today: LocalDate,
    dayFormatter: DateTimeFormatter,
    onClick: () -> Unit,
    inset: Boolean = false,
) {
    val missed = workout.status == WorkoutStatus.PLANNED && workout.date.isBefore(today) && workout.type.isRun
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = if (inset) 16.dp else 0.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            when (workout.status) {
                WorkoutStatus.COMPLETED -> Icons.Filled.CheckCircle
                WorkoutStatus.SKIPPED -> Icons.Filled.RemoveCircleOutline
                WorkoutStatus.PLANNED -> if (workout.type.isRun) Icons.Filled.RadioButtonUnchecked else workout.type.icon
            },
            contentDescription = stringResource(workout.status.labelRes),
            tint = when {
                workout.status == WorkoutStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                missed -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Column(Modifier.weight(1f)) {
            Text(
                "${workout.date.format(dayFormatter)} · ${workout.title}",
                style = MaterialTheme.typography.bodyLarge,
            )
            val target = workout.targetText()
            if (target.isNotEmpty()) {
                Text(target, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val WorkoutStatus.labelRes: Int
    get() = when (this) {
        WorkoutStatus.PLANNED -> R.string.workout_status_planned
        WorkoutStatus.COMPLETED -> R.string.workout_status_completed
        WorkoutStatus.SKIPPED -> R.string.workout_status_skipped
    }

// --- dialogs ---------------------------------------------------------------------

@Composable
private fun WorkoutDialog(
    workout: PlannedWorkout,
    onStatus: (WorkoutStatus) -> Unit,
    onViewRun: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(workout.type.icon, contentDescription = null) },
        title = { Text(workout.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${workout.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))} · " +
                        stringResource(workout.type.labelRes),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val target = workout.targetText()
                if (target.isNotEmpty()) Text(target, style = MaterialTheme.typography.titleSmall)
                if (workout.description.isNotBlank()) Text(workout.description)
                if (workout.completedPaceSecPerMile != null && workout.completedDistanceMeters != null) {
                    Text(
                        stringResource(
                            R.string.plan_workout_ran,
                            Units.formatMiles(workout.completedDistanceMeters),
                            Units.formatPace(workout.completedPaceSecPerMile),
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    stringResource(workout.status.labelRes),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    workout.completedRunId?.let { runId ->
                        OutlinedButton(onClick = { onViewRun(runId) }) { Text(stringResource(R.string.plan_view_run)) }
                    }
                    if (workout.status == WorkoutStatus.PLANNED) {
                        OutlinedButton(onClick = { onStatus(WorkoutStatus.SKIPPED) }) {
                            Text(stringResource(R.string.plan_skip))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (workout.status == WorkoutStatus.PLANNED) {
                TextButton(onClick = { onStatus(WorkoutStatus.COMPLETED) }) { Text(stringResource(R.string.plan_mark_done)) }
            } else {
                TextButton(onClick = { onStatus(WorkoutStatus.PLANNED) }) { Text(stringResource(R.string.plan_mark_not_done)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun ReplaceOrMergeDialog(
    parsed: ParsedPlan,
    onReplace: () -> Unit,
    onMerge: () -> Unit,
    onCancel: () -> Unit,
) {
    val formatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.plan_import_choice_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    pluralStringResource(
                        R.plurals.plan_import_summary,
                        parsed.workouts.size,
                        parsed.name,
                        parsed.workouts.size,
                        parsed.firstDate.format(formatter),
                        parsed.lastDate.format(formatter),
                    ),
                )
                Text(stringResource(R.string.plan_import_choice_body))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onMerge) { Text(stringResource(R.string.plan_import_merge)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onReplace) { Text(stringResource(R.string.plan_import_replace)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Collects the few facts an AI assistant needs to write a sensible plan, and
 * turns them into a prompt the runner can copy or share straight into a chat app.
 */
@Composable
private fun PromptDialog(
    today: LocalDate,
    raceDate: LocalDate?,
    buildPrompt: (PlanRequest) -> String,
    onCopied: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var raceText by rememberSaveable { mutableStateOf((raceDate ?: today.plusYears(1)).toString()) }
    var runsPerWeek by rememberSaveable { mutableStateOf("4") }
    var weeklyMiles by rememberSaveable { mutableStateOf("10") }
    var longestRun by rememberSaveable { mutableStateOf("3") }
    var notes by rememberSaveable { mutableStateOf("") }

    val race = try {
        LocalDate.parse(raceText.trim()).takeIf { it.isAfter(today) }
    } catch (e: DateTimeParseException) {
        null
    }
    val runs = runsPerWeek.trim().toIntOrNull()?.takeIf { it in 2..7 }
    val weekly = weeklyMiles.trim().toDoubleOrNull()?.takeIf { it >= 0.0 && it <= 150.0 }
    val longest = longestRun.trim().toDoubleOrNull()?.takeIf { it >= 0.0 && it <= 50.0 }
    val request = if (race != null && runs != null && weekly != null && longest != null) {
        PlanRequest(
            raceDate = race,
            runsPerWeek = runs,
            currentWeeklyMiles = weekly,
            longestRecentRunMiles = longest,
            notes = notes,
        )
    } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plan_prompt_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.plan_prompt_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = raceText,
                    onValueChange = { raceText = it },
                    label = { Text(stringResource(R.string.plan_prompt_race_date)) },
                    isError = race == null,
                    supportingText = { Text(stringResource(R.string.plan_prompt_race_date_hint)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = runsPerWeek,
                    onValueChange = { runsPerWeek = it },
                    label = { Text(stringResource(R.string.plan_prompt_runs_per_week)) },
                    isError = runs == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = weeklyMiles,
                    onValueChange = { weeklyMiles = it },
                    label = { Text(stringResource(R.string.plan_prompt_weekly_miles)) },
                    isError = weekly == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = longestRun,
                    onValueChange = { longestRun = it },
                    label = { Text(stringResource(R.string.plan_prompt_longest_run)) },
                    isError = longest == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(300) },
                    label = { Text(stringResource(R.string.plan_prompt_notes)) },
                    supportingText = { Text(stringResource(R.string.plan_prompt_notes_hint)) },
                )
                TextButton(
                    enabled = request != null,
                    onClick = { request?.let { context.sharePrompt(buildPrompt(it)) } },
                ) { Text(stringResource(R.string.plan_prompt_share)) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = request != null,
                onClick = {
                    request?.let {
                        context.copyToClipboard(buildPrompt(it))
                        onCopied()
                    }
                },
            ) { Text(stringResource(R.string.plan_prompt_copy)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

// --- platform helpers ----------------------------------------------------------------

private fun Context.clipboardManager(): ClipboardManager? =
    getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

private fun Context.clipboardText(): String? {
    val clip = clipboardManager()?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(this)?.toString()
}

private fun Context.copyToClipboard(text: String) {
    clipboardManager()?.setPrimaryClip(ClipData.newPlainText(getString(R.string.plan_prompt_title), text))
}

/** Offers the prompt to any app that takes text — a chat assistant's app, most usefully. */
private fun Context.sharePrompt(text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { startActivity(Intent.createChooser(send, getString(R.string.plan_prompt_share))) }
}

private fun Resources.importFailureMessage(failure: PlanImportFailure, detail: String?): String {
    val base = getString(
        when (failure) {
            PlanImportFailure.NOT_JSON -> R.string.plan_import_not_json
            PlanImportFailure.NO_WORKOUTS -> R.string.plan_import_no_workouts
            PlanImportFailure.BAD_DATE -> R.string.plan_import_bad_date
            PlanImportFailure.BAD_WORKOUT -> R.string.plan_import_bad_workout
            PlanImportFailure.TOO_MANY -> R.string.plan_import_too_many
        },
    )
    return if (detail != null) "$base ($detail)" else base
}
