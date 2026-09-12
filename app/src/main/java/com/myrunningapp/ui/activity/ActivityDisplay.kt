package com.myrunningapp.ui.activity

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.ui.graphics.vector.ImageVector
import com.myrunningapp.R
import com.myrunningapp.domain.model.ActivityType

/**
 * How each [ActivityType] is named and drawn.
 *
 * One place rather than one per screen: the picker, the history row and the
 * detail header all have to agree, and the `when`s here are exhaustive, so a
 * fourth activity is a compile error rather than a ride quietly labelled as a
 * walk on two screens out of three.
 */

@get:StringRes
val ActivityType.labelRes: Int
    get() = when (this) {
        ActivityType.RUN -> R.string.activity_run
        ActivityType.WALK -> R.string.activity_walk
        ActivityType.BIKE -> R.string.activity_bike
    }

val ActivityType.icon: ImageVector
    get() = when (this) {
        ActivityType.RUN -> Icons.AutoMirrored.Filled.DirectionsRun
        ActivityType.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
        ActivityType.BIKE -> Icons.AutoMirrored.Filled.DirectionsBike
    }

/** "Average pace" or "Average speed", to match what the number under it is. */
@get:StringRes
val ActivityType.averagePaceLabelRes: Int
    get() = if (readsAsSpeed) R.string.stat_avg_speed else R.string.stat_avg_pace

/** The same label on the tracking screen, which words its stats differently. */
@get:StringRes
val ActivityType.trackAveragePaceLabelRes: Int
    get() = if (readsAsSpeed) R.string.track_avg_speed else R.string.track_avg_pace

/** The splits-table column header: "Pace" or "Speed". */
@get:StringRes
val ActivityType.splitPaceLabelRes: Int
    get() = if (readsAsSpeed) R.string.detail_split_speed else R.string.detail_split_pace
