package com.myrunningapp.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myrunningapp.data.prefs.PreferencesRepository
import com.myrunningapp.data.repository.ProfileRepository
import com.myrunningapp.domain.Units
import com.myrunningapp.domain.model.CountdownLength
import com.myrunningapp.domain.model.Profile
import com.myrunningapp.domain.model.Sex
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    val uiState: StateFlow<ProfileUiState> = combine(
        profileRepository.profile,
        profileRepository.hasSavedProfile,
        preferencesRepository.preferences,
    ) { profile, hasSaved, prefs ->
        ProfileUiState(
            loading = false,
            profile = profile,
            hasSavedProfile = hasSaved,
            preferences = prefs,
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

    sealed interface ProfileEvent {
        data object Saved : ProfileEvent
        data class ValidationError(val message: String) : ProfileEvent
    }
}
