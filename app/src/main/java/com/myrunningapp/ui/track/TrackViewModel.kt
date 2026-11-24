package com.myrunningapp.ui.track

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myrunningapp.data.location.RunTracker
import com.myrunningapp.data.location.LocationClient
import com.myrunningapp.domain.tracking.GpsFix
import com.myrunningapp.data.prefs.PreferencesRepository
import com.myrunningapp.data.plan.TrainingPlanRepository
import com.myrunningapp.domain.plan.PlanOverview
import com.myrunningapp.domain.plan.PlannedWorkout
import com.myrunningapp.data.repository.ProfileRepository
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.tracking.RunSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.CancellationException
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
    val keepScreenOn: Boolean,
    /** Remembered permission-flow state; see [com.myrunningapp.domain.permission.PermissionGate]. */
    val backgroundLocationAsked: Boolean,
    val backgroundWarningDismissed: Boolean,
    /** Today's run from the training plan, still to do; null with no plan or a rest day. */
    val todaysWorkout: PlannedWorkout? = null,
    /** Whether the next run is coached against [todaysWorkout]. */
    val coachTodaysWorkout: Boolean = true,
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
    private val planRepository: TrainingPlanRepository,
    locationClient: LocationClient,
) : ViewModel() {

    val route = tracker.route

    // Collected only by the resumed idle screen after permission is granted.
    // Preview fixes never enter the recorder or contribute to run distance.
    val locationPreview = locationClient.fixes()
        .map<GpsFix, GpsFix?> { it }
        .catch { error ->
            if (error is CancellationException) throw error
            emit(null)
        }

    private val selectedActivityType = MutableStateFlow(ActivityType.RUN)
    private val coachTodaysWorkout = MutableStateFlow(true)

    private val todaysWorkout = planRepository.activePlan.map { active ->
        active?.let { PlanOverview.todaysRun(it.workouts, planRepository.today()) }
    }

    /** The workout the run in progress is being coached against. */
    val activeWorkout: StateFlow<PlannedWorkout?> = tracker.activeWorkout

    val uiState: StateFlow<TrackUiState> = combine(
        tracker.snapshot,
        selectedActivityType,
        preferencesRepository.preferences,
        todaysWorkout,
        coachTodaysWorkout,
    ) { snapshot, activityType, preferences, workout, coach ->
        TrackUiState(
            snapshot = snapshot,
            selectedActivityType = activityType,
            countdownSeconds = preferences.countdownLength.seconds,
            keepScreenOn = preferences.keepScreenOnDuringRun,
            backgroundLocationAsked = preferences.backgroundLocationAsked,
            backgroundWarningDismissed = preferences.backgroundWarningDismissed,
            todaysWorkout = workout,
            coachTodaysWorkout = coach,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TrackUiState(
            snapshot = RunSnapshot.idle(ActivityType.RUN),
            selectedActivityType = ActivityType.RUN,
            countdownSeconds = 0,
            keepScreenOn = false,
            // Assume the conversation has happened until preferences load, so a
            // rationale dialog never flashes up on the strength of a default.
            backgroundLocationAsked = true,
            backgroundWarningDismissed = true,
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
        val workoutId: Long?,
    )

    fun selectActivityType(activityType: ActivityType) {
        selectedActivityType.value = activityType
    }

    /** @param withCountdown false for "start now", true for the configured delay. */
    fun requestStart(withCountdown: Boolean) {
        viewModelScope.launch {
            val preferences = preferencesRepository.preferences.first()
            val profile = profileRepository.get()
            val activityType = selectedActivityType.value
            val state = uiState.value
            _startRequest.value = StartRequest(
                activityType = activityType,
                countdownSeconds = if (withCountdown) preferences.countdownLength.seconds else 0,
                weightKg = profile.weightKg,
                workoutId = state.todaysWorkout?.id
                    ?.takeIf { state.coachTodaysWorkout && activityType == ActivityType.RUN },
            )
        }
    }

    fun setCoachTodaysWorkout(enabled: Boolean) {
        coachTodaysWorkout.value = enabled
    }

    fun onStartHandled() {
        _startRequest.value = null
    }

    /** Android shows its permission dialog once; record that we have had the ask. */
    fun onBackgroundLocationAsked() {
        viewModelScope.launch { preferencesRepository.setBackgroundLocationAsked(true) }
    }

    fun dismissBackgroundWarning() {
        viewModelScope.launch { preferencesRepository.setBackgroundWarningDismissed(true) }
    }
}
