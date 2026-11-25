package com.myrunningapp.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.myrunningapp.domain.model.CountdownLength
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Reads and writes [AppPreferences] backed by Preferences DataStore. */
@Singleton
class PreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val preferences: Flow<AppPreferences> = dataStore.data.map { prefs ->
        AppPreferences(
            countdownLength = CountdownLength.fromSeconds(
                prefs[Keys.COUNTDOWN_SECONDS] ?: CountdownLength.DEFAULT.seconds,
            ),
            voiceAnnouncementsEnabled = prefs[Keys.VOICE] ?: true,
            keepScreenOnDuringRun = prefs[Keys.KEEP_SCREEN_ON] ?: true,
            colorRouteByPace = prefs[Keys.PACE_COLOR] ?: false,
            backgroundLocationAsked = prefs[Keys.BACKGROUND_ASKED] ?: false,
            backgroundWarningDismissed = prefs[Keys.BACKGROUND_WARNING_DISMISSED] ?: false,
            healthSyncEnabled = prefs[Keys.HEALTH_SYNC_ENABLED] ?: false,
            healthPermissionAsked = prefs[Keys.HEALTH_PERMISSION_ASKED] ?: false,
            updateChecksEnabled = prefs[Keys.UPDATE_CHECKS_ENABLED] ?: false,
            lastNotifiedUpdate = prefs[Keys.LAST_NOTIFIED_UPDATE],
        )
    }

    suspend fun setCountdownLength(length: CountdownLength) {
        dataStore.edit { it[Keys.COUNTDOWN_SECONDS] = length.seconds }
    }

    suspend fun setVoiceAnnouncementsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.VOICE] = enabled }
    }

    suspend fun setKeepScreenOnDuringRun(enabled: Boolean) {
        dataStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setColorRouteByPace(enabled: Boolean) {
        dataStore.edit { it[Keys.PACE_COLOR] = enabled }
    }

    suspend fun setBackgroundLocationAsked(asked: Boolean) {
        dataStore.edit { it[Keys.BACKGROUND_ASKED] = asked }
    }

    suspend fun setBackgroundWarningDismissed(dismissed: Boolean) {
        dataStore.edit { it[Keys.BACKGROUND_WARNING_DISMISSED] = dismissed }
    }

    suspend fun setHealthSyncEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.HEALTH_SYNC_ENABLED] = enabled }
    }

    suspend fun setHealthPermissionAsked(asked: Boolean) {
        dataStore.edit { it[Keys.HEALTH_PERMISSION_ASKED] = asked }
    }

    suspend fun setUpdateChecksEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.UPDATE_CHECKS_ENABLED] = enabled }
    }

    suspend fun setLastNotifiedUpdate(tag: String) {
        dataStore.edit { it[Keys.LAST_NOTIFIED_UPDATE] = tag }
    }

    private object Keys {
        val COUNTDOWN_SECONDS = intPreferencesKey("countdown_seconds")
        val VOICE = booleanPreferencesKey("voice_announcements")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val PACE_COLOR = booleanPreferencesKey("pace_color")
        val BACKGROUND_ASKED = booleanPreferencesKey("background_location_asked")
        val BACKGROUND_WARNING_DISMISSED = booleanPreferencesKey("background_warning_dismissed")
        val HEALTH_SYNC_ENABLED = booleanPreferencesKey("health_sync_enabled")
        val HEALTH_PERMISSION_ASKED = booleanPreferencesKey("health_permission_asked")
        val UPDATE_CHECKS_ENABLED = booleanPreferencesKey("update_checks_enabled")
        val LAST_NOTIFIED_UPDATE = stringPreferencesKey("last_notified_update")
    }
}
