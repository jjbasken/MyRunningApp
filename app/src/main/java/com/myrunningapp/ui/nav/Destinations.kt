package com.myrunningapp.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector

/** All navigation routes in the app. String routes keep the setup dependency-free. */
object Routes {
    const val TRACK = "track"
    const val HISTORY = "history"
    const val PROFILE = "profile"

    const val RUN_DETAIL = "run/{runId}"
    fun runDetail(runId: Long) = "run/$runId"
    const val ARG_RUN_ID = "runId"
}

/** The three top-level destinations shown in the bottom navigation bar. */
enum class TopLevelDestination(
    val route: String,
    val icon: ImageVector,
    val labelRes: Int,
) {
    TRACK(Routes.TRACK, Icons.AutoMirrored.Filled.DirectionsRun, com.myrunningapp.R.string.nav_track),
    HISTORY(Routes.HISTORY, Icons.Filled.History, com.myrunningapp.R.string.nav_history),
    PROFILE(Routes.PROFILE, Icons.Filled.Person, com.myrunningapp.R.string.nav_profile),
}
