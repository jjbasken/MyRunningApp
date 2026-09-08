package com.myrunningapp.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myrunningapp.data.export.ExportDocument
import com.myrunningapp.data.export.RunExporter
import com.myrunningapp.data.prefs.PreferencesRepository
import com.myrunningapp.data.repository.RunRepository
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.Run
import com.myrunningapp.domain.model.Split
import com.myrunningapp.ui.map.RouteCoordinate
import com.myrunningapp.ui.nav.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RunDetailUiState(
    val loading: Boolean = true,
    val run: Run? = null,
    val points: List<RouteCoordinate> = emptyList(),
    val splits: List<Split> = emptyList(),
    /** From preferences; the live map never colours by pace. */
    val colorRouteByPace: Boolean = false,
)

@HiltViewModel
class RunDetailViewModel @Inject constructor(
    private val repository: RunRepository,
    private val preferencesRepository: PreferencesRepository,
    private val exporter: RunExporter,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val runId = checkNotNull(savedStateHandle.get<Long>(Routes.ARG_RUN_ID))

    val uiState = combine(
        repository.run(runId),
        repository.points(runId),
        repository.splits(runId),
        preferencesRepository.preferences,
    ) { run, points, splits, preferences ->
        RunDetailUiState(
            loading = false,
            run = run,
            points = points.map {
                RouteCoordinate(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    segmentIndex = it.segmentIndex,
                    // Carried only here: colouring by pace needs to know when
                    // each fix was taken, and the live map has no use for it.
                    timestampMillis = it.timestamp.toEpochMilli(),
                )
            },
            splits = splits,
            colorRouteByPace = preferences.colorRouteByPace,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RunDetailUiState())

    private val _pendingExport = MutableStateFlow<ExportDocument?>(null)
    /**
     * The GPX waiting for somewhere to go. The screen owns the Storage Access
     * Framework picker — a ViewModel has no Activity to launch one from — so it
     * consumes this and calls [onExportHandled].
     */
    val pendingExport: StateFlow<ExportDocument?> = _pendingExport.asStateFlow()

    fun exportRun() {
        viewModelScope.launch { _pendingExport.value = exporter.exportRun(runId) }
    }

    fun onExportHandled() {
        _pendingExport.value = null
    }

    private val _deleted = MutableStateFlow(false)
    /**
     * Set once the run is gone, so the screen can leave rather than sit on a row
     * that no longer exists.
     */
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    fun setActivityType(activityType: ActivityType) {
        viewModelScope.launch { repository.updateActivityType(runId, activityType) }
    }

    fun deleteRun() {
        viewModelScope.launch {
            repository.deleteRun(runId)
            _deleted.value = true
        }
    }
}
