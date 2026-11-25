package com.myrunningapp.ui.profile

import com.myrunningapp.data.prefs.AppPreferences
import com.myrunningapp.domain.health.HealthSyncUiState
import com.myrunningapp.domain.model.Profile
import com.myrunningapp.ui.update.UpdateSectionState

data class ProfileUiState(
    val loading: Boolean = true,
    val profile: Profile = Profile.DEFAULT,
    val hasSavedProfile: Boolean = false,
    val preferences: AppPreferences = AppPreferences.DEFAULT,
    val healthSync: HealthSyncUiState = HealthSyncUiState.Hidden,
    val update: UpdateSectionState = UpdateSectionState(),
)
