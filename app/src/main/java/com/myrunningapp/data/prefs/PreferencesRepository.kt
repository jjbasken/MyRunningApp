package com.myrunningapp.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

    private object Keys {
        val COUNTDOWN_SECONDS = intPreferencesKey("countdown_seconds")
        val VOICE = booleanPreferencesKey("voice_announcements")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val PACE_COLOR = booleanPreferencesKey("pace_color")
    }
}
