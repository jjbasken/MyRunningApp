package com.myrunningapp.ui.track

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myrunningapp.data.location.RunTracker
import com.myrunningapp.data.prefs.PreferencesRepository
import com.myrunningapp.data.repository.ProfileRepository
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.tracking.RunSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the track screen draws. */
data class TrackUiState(
    val snapshot: RunSnapshot,
    /** The type chosen for the *next* run; a run in progress keeps its own. */
    val selectedActivityType: ActivityType,
    val countdownSeconds: Int,
)

/**
 * The track screen's view of the run.
 *
 * Deliberately thin: the run itself lives in [RunTracker], which outlives this
 * ViewModel, so leaving the screen and coming back re-attaches to a run already
 * in progress instead of losing it. Commands go out through the service, so the
 * notification's buttons and the screen's take the same path.
 */
@HiltViewModel
class TrackViewModel @Inject constructor(
    private val tracker: RunTracker,
    private val preferencesRepository: PreferencesRepository,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    val route = tracker.route

    private val selectedActivityType = MutableStateFlow(ActivityType.RUN)

    val uiState: StateFlow<TrackUiState> = combine(
        tracker.snapshot,
        selectedActivityType,
        preferencesRepository.preferences,
    ) { snapshot, activityType, preferences ->
        TrackUiState(
            snapshot = snapshot,
            selectedActivityType = activityType,
            countdownSeconds = preferences.countdownLength.seconds,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TrackUiState(
            snapshot = RunSnapshot.idle(ActivityType.RUN),
            selectedActivityType = ActivityType.RUN,
            countdownSeconds = 0,
        ),
    )

    /** The run just completed, for the "view this run" link. */
    val lastFinishedRunId: StateFlow<Long?> = tracker.lastFinishedRunId

    private val _startRequest = MutableStateFlow<StartRequest?>(null)
    /**
     * Set when the screen should start the service. The screen consumes it and
     * calls [onStartHandled] — the ViewModel has no Context to start a service with.
     */
    val startRequest: StateFlow<StartRequest?> = _startRequest.asStateFlow()

    data class StartRequest(
        val activityType: ActivityType,
        val countdownSeconds: Int,
        val weightKg: Double,
    )

    fun selectActivityType(activityType: ActivityType) {
        selectedActivityType.value = activityType
    }

    /** @param withCountdown false for "start now", true for the configured delay. */
    fun requestStart(withCountdown: Boolean) {
        viewModelScope.launch {
            val preferences = preferencesRepository.preferences.first()
            val profile = profileRepository.get()
            _startRequest.value = StartRequest(
                activityType = selectedActivityType.value,
                countdownSeconds = if (withCountdown) preferences.countdownLength.seconds else 0,
                weightKg = profile.weightKg,
            )
        }
    }

    fun onStartHandled() {
        _startRequest.value = null
    }
}
