package com.myrunningapp.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
)

@HiltViewModel
class RunDetailViewModel @Inject constructor(
    private val repository: RunRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val runId = checkNotNull(savedStateHandle.get<Long>(Routes.ARG_RUN_ID))

    val uiState = combine(
        repository.run(runId),
        repository.points(runId),
        repository.splits(runId),
    ) { run, points, splits ->
        RunDetailUiState(
            loading = false,
            run = run,
            points = points.map { RouteCoordinate(it.latitude, it.longitude, it.segmentIndex) },
            splits = splits,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RunDetailUiState())

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
