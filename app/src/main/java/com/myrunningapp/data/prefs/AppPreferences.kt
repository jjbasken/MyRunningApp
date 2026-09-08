package com.myrunningapp.data.prefs

import com.myrunningapp.domain.model.CountdownLength

/** User preferences (not body data — that's [com.myrunningapp.domain.model.Profile]). */
data class AppPreferences(
    val countdownLength: CountdownLength = CountdownLength.DEFAULT,
    val voiceAnnouncementsEnabled: Boolean = true,
    val keepScreenOnDuringRun: Boolean = true,
    val colorRouteByPace: Boolean = false,
) {
    companion object {
        val DEFAULT = AppPreferences()
    }
}
