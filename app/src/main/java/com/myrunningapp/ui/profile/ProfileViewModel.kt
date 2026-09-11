package com.myrunningapp.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myrunningapp.data.db.dao.HealthSyncDao
import com.myrunningapp.data.export.ExportDocument
import com.myrunningapp.data.export.RunExporter
import com.myrunningapp.data.health.HealthConnectGateway
import com.myrunningapp.data.health.HealthPermissions
import com.myrunningapp.data.health.HealthSyncScheduler
import com.myrunningapp.data.prefs.PreferencesRepository
import com.myrunningapp.data.repository.ProfileRepository
import com.myrunningapp.domain.Units
import com.myrunningapp.domain.health.HealthSyncStatus
import com.myrunningapp.domain.health.HealthSyncUiState
import com.myrunningapp.domain.model.CountdownLength
import com.myrunningapp.domain.model.Profile
import com.myrunningapp.domain.model.Sex
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val preferencesRepository: PreferencesRepository,
    private val exporter: RunExporter,
    private val gateway: HealthConnectGateway,
    private val healthSyncDao: HealthSyncDao,
    private val healthSyncScheduler: HealthSyncScheduler,
) : ViewModel() {

    /**
     * Bumped to re-read permission state. Permission is granted outside this app,
     * in Health Connect's own UI, so nothing else would re-emit when the user
     * comes back.
     */
    private val healthRefresh = MutableStateFlow(0)

    /** What to hand the Health Connect permission contract. */
    val healthPermissionsToRequest: Set<String> = HealthPermissions.ALL

    private val healthSync: Flow<HealthSyncUiState> = combine(
        preferencesRepository.preferences,
        healthSyncDao.observeCounts(),
        healthRefresh,
    ) { prefs, counts, _ ->
        HealthSyncStatus.of(
            availability = gateway.availability(),
            enabled = prefs.healthSyncEnabled,
            writePermissionsGranted = gateway.hasWritePermissions(),
            counts = counts,
        )
    }.flowOn(Dispatchers.IO)

    val uiState: StateFlow<ProfileUiState> = combine(
        profileRepository.profile,
        profileRepository.hasSavedProfile,
        preferencesRepository.preferences,
        healthSync,
    ) { profile, hasSaved, prefs, health ->
        ProfileUiState(
            loading = false,
            profile = profile,
            hasSavedProfile = hasSaved,
            preferences = prefs,
            healthSync = health,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProfileUiState(),
    )

    private val _events = MutableSharedFlow<ProfileEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events = _events.asSharedFlow()

    /** Validates the pounds/inches form and persists it in canonical metric. */
    fun saveProfile(weightLb: String, heightIn: String, age: String, sex: Sex) {
        val weight = weightLb.trim().toDoubleOrNull()
        val height = heightIn.trim().toDoubleOrNull()
        val ageYears = age.trim().toIntOrNull()

        val error = when {
            weight == null || weight !in 40.0..660.0 -> "Enter a weight between 40 and 660 lb"
            height == null || height !in 36.0..96.0 -> "Enter a height between 36 and 96 in"
            ageYears == null || ageYears !in 5..120 -> "Enter an age between 5 and 120"
            else -> null
        }
        if (error != null) {
            _events.tryEmit(ProfileEvent.ValidationError(error))
            return
        }

        viewModelScope.launch {
            profileRepository.save(
                Profile(
                    weightKg = Units.lbToKg(weight!!),
                    heightCm = Units.inchesToCm(height!!),
                    age = ageYears!!,
                    sex = sex,
                ),
            )
            _events.tryEmit(ProfileEvent.Saved)
        }
    }

    private val _pendingExport = MutableStateFlow<ExportDocument?>(null)
    /** The backup waiting for the user to pick a destination. */
    val pendingExport: StateFlow<ExportDocument?> = _pendingExport.asStateFlow()

    /**
     * Builds the whole-database backup. Reading every run's track takes a moment
     * on a long history, which is why it happens on a tap rather than eagerly.
     * A history with no runs still exports — a file saying so is a truthful
     * backup, and beats a button that appears to do nothing.
     */
    fun exportEverything() {
        viewModelScope.launch { _pendingExport.value = exporter.exportEverything() }
    }

    fun onExportHandled() {
        _pendingExport.value = null
    }

    fun setCountdownLength(length: CountdownLength) {
        viewModelScope.launch { preferencesRepository.setCountdownLength(length) }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setVoiceAnnouncementsEnabled(enabled) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setKeepScreenOnDuringRun(enabled) }
    }

    fun setColorRouteByPace(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setColorRouteByPace(enabled) }
    }

    fun setHealthSyncEnabled(enabled: Boolean) = viewModelScope.launch {
        preferencesRepository.setHealthSyncEnabled(enabled)
        if (enabled) {
            // Switching it on is what queues the history; the migration deliberately did not.
            healthSyncDao.markAllPending()
            healthSyncScheduler.requestSync()
        }
    }

    fun onHealthPermissionResult(granted: Set<String>) = viewModelScope.launch {
        preferencesRepository.setHealthPermissionAsked(true)
        healthRefresh.value++
        if (granted.containsAll(HealthPermissions.WRITE)) healthSyncScheduler.requestSync()
    }

    fun syncNow() = viewModelScope.launch {
        healthSyncDao.retryFailed()
        healthRefresh.value++
        healthSyncScheduler.requestSync()
    }

    /**
     * Re-reads permission state. Health Connect's own settings screen grants (or
     * revokes) permission without ever calling back into a launcher, so this is
     * the only way the app finds out the user came back with a different grant.
     */
    fun refreshHealthState() {
        healthRefresh.value++
    }

    sealed interface ProfileEvent {
        data object Saved : ProfileEvent
        data class ValidationError(val message: String) : ProfileEvent
    }
}
