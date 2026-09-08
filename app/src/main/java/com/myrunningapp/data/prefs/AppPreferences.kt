package com.myrunningapp.data.prefs

import com.myrunningapp.domain.model.CountdownLength

/** User preferences (not body data — that's [com.myrunningapp.domain.model.Profile]). */
data class AppPreferences(
    val countdownLength: CountdownLength = CountdownLength.DEFAULT,
    val voiceAnnouncementsEnabled: Boolean = true,
    val keepScreenOnDuringRun: Boolean = true,
    val colorRouteByPace: Boolean = false,
    /**
     * Remembered flow state rather than a setting — neither appears on the
     * settings screen. Android only shows its own permission dialog once, so the
     * app has to remember that it has already had the background conversation
     * and that the user has waved the resulting warning away.
     */
    val backgroundLocationAsked: Boolean = false,
    val backgroundWarningDismissed: Boolean = false,
) {
    companion object {
        val DEFAULT = AppPreferences()
    }
}
