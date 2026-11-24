package com.myrunningapp.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myrunningapp.data.export.GpxImportException
import com.myrunningapp.data.plan.TrainingPlanRepository
import com.myrunningapp.domain.plan.ParsedPlan
import com.myrunningapp.domain.plan.PlanImportException
import com.myrunningapp.domain.plan.PlanImportFailure
import com.myrunningapp.domain.plan.PlanOverview
import com.myrunningapp.domain.plan.PlanPrompt
import com.myrunningapp.domain.plan.PlanRequest
import com.myrunningapp.domain.plan.PlannedWorkout
import com.myrunningapp.domain.plan.TrainingPlan
import com.myrunningapp.domain.plan.WorkoutStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

/** One Monday-to-Sunday block of the plan, as the list draws it. */
data class PlanWeek(
    /** 1 for the plan's first week. */
    val number: Int,
    val start: LocalDate,
    val workouts: List<PlannedWorkout>,
    val plannedMeters: Double,
    val completedMeters: Double,
    val isCurrent: Boolean,
)

data class PlanUiState(
    val loading: Boolean = true,
    val plan: TrainingPlan? = null,
    val overview: PlanOverview? = null,
    val weeks: List<PlanWeek> = emptyList(),
    val today: LocalDate = LocalDate.MIN,
)

@HiltViewModel
class PlanViewModel @Inject constructor(
    private val repository: TrainingPlanRepository,
) : ViewModel() {

    sealed interface ImportEvent {
        data class Imported(val workouts: Int, val merged: Boolean) : ImportEvent
        data class Failed(val failure: PlanImportFailure, val detail: String?) : ImportEvent
        /** The picked file could not be read at all. */
        data object Unreadable : ImportEvent
    }

    /** Follows the date too, so a screen left open overnight moves on to the new day. */
    val uiState: StateFlow<PlanUiState> = combine(repository.activePlan, repository.todayFlow) { active, today ->
        if (active == null) {
            PlanUiState(loading = false, today = today)
        } else {
            PlanUiState(
                loading = false,
                plan = active.plan,
                overview = PlanOverview.of(active.plan, active.workouts, today),
                weeks = weeks(active.workouts, today),
                today = today,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanUiState())

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _pendingImport = MutableStateFlow<ParsedPlan?>(null)
    /**
     * A plan read successfully while another is already in use: the screen asks
     * whether it replaces that plan or adds weeks to it.
     */
    val pendingImport: StateFlow<ParsedPlan?> = _pendingImport.asStateFlow()

    private val _importEvents = MutableSharedFlow<ImportEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val importEvents = _importEvents.asSharedFlow()

    fun importText(text: String) = importWith { text }

    /** @param readFile reads the picked file, throwing [GpxImportException] when it cannot. */
    fun importFile(readFile: suspend () -> ByteArray) = importWith { readFile().decodeToString() }

    private fun importWith(read: suspend () -> String) {
        viewModelScope.launch {
            _importing.value = true
            try {
                val parsed = repository.parse(read())
                if (repository.hasPlan()) {
                    _pendingImport.value = parsed
                } else {
                    repository.replaceWith(parsed)
                    _importEvents.emit(ImportEvent.Imported(parsed.workouts.size, merged = false))
                }
            } catch (e: PlanImportException) {
                _importEvents.emit(ImportEvent.Failed(e.failure, e.detail))
            } catch (e: GpxImportException) {
                _importEvents.emit(ImportEvent.Unreadable)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _importEvents.emit(ImportEvent.Failed(PlanImportFailure.NOT_JSON, null))
            } finally {
                _importing.value = false
            }
        }
    }

    /** @param merge true to add the new weeks to the current plan, false to replace it. */
    fun confirmImport(merge: Boolean) {
        val parsed = _pendingImport.value ?: return
        _pendingImport.value = null
        viewModelScope.launch {
            val added = if (merge) {
                repository.mergeIntoCurrent(parsed)
            } else {
                repository.replaceWith(parsed)
                parsed.workouts.size
            }
            _importEvents.emit(ImportEvent.Imported(added, merged = merge))
        }
    }

    fun cancelImport() {
        _pendingImport.value = null
    }

    fun deletePlan() {
        viewModelScope.launch { repository.deletePlan() }
    }

    fun setStatus(workoutId: Long, status: WorkoutStatus) {
        viewModelScope.launch {
            when (status) {
                WorkoutStatus.COMPLETED -> repository.markCompleted(workoutId)
                WorkoutStatus.SKIPPED -> repository.markSkipped(workoutId)
                WorkoutStatus.PLANNED -> repository.resetWorkout(workoutId)
            }
        }
    }

    fun buildPrompt(request: PlanRequest): String = PlanPrompt.build(request, repository.today())

    fun today(): LocalDate = repository.today()

    private fun weeks(workouts: List<PlannedWorkout>, today: LocalDate): List<PlanWeek> {
        if (workouts.isEmpty()) return emptyList()
        val firstMonday = workouts.first().date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return workouts
            .groupBy { ChronoUnit.WEEKS.between(firstMonday, it.date).toInt() }
            .toSortedMap()
            .map { (index, list) ->
                val start = firstMonday.plusWeeks(index.toLong())
                val runs = list.filter { it.type.isRun }
                PlanWeek(
                    number = index + 1,
                    start = start,
                    workouts = list,
                    plannedMeters = runs.sumOf { it.distanceMeters ?: 0.0 },
                    completedMeters = runs.filter { it.status == WorkoutStatus.COMPLETED }
                        .sumOf { it.completedDistanceMeters ?: it.distanceMeters ?: 0.0 },
                    isCurrent = !today.isBefore(start) && today.isBefore(start.plusWeeks(1)),
                )
            }
    }
}
