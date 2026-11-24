package com.myrunningapp.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.myrunningapp.R
import com.myrunningapp.domain.Units
import com.myrunningapp.domain.plan.PlannedWorkout
import com.myrunningapp.domain.plan.WorkoutType
import java.util.Locale
import kotlin.math.roundToLong

val WorkoutType.labelRes: Int
    get() = when (this) {
        WorkoutType.EASY -> R.string.workout_type_easy
        WorkoutType.LONG -> R.string.workout_type_long
        WorkoutType.TEMPO -> R.string.workout_type_tempo
        WorkoutType.INTERVALS -> R.string.workout_type_intervals
        WorkoutType.RECOVERY -> R.string.workout_type_recovery
        WorkoutType.RACE -> R.string.workout_type_race
        WorkoutType.CROSS_TRAIN -> R.string.workout_type_cross_train
        WorkoutType.REST -> R.string.workout_type_rest
        WorkoutType.OTHER -> R.string.workout_type_other
    }

val WorkoutType.icon: ImageVector
    get() = when (this) {
        WorkoutType.LONG -> Icons.Filled.Route
        WorkoutType.TEMPO -> Icons.Filled.Timer
        WorkoutType.INTERVALS -> Icons.Filled.Speed
        WorkoutType.RACE -> Icons.Filled.EmojiEvents
        WorkoutType.CROSS_TRAIN -> Icons.Filled.FitnessCenter
        WorkoutType.REST -> Icons.Filled.Bedtime
        else -> Icons.AutoMirrored.Filled.DirectionsRun
    }

/** "6.00 mi · 11:00–11:30 /mi", "45 min", or "" for a workout with no numbers. */
fun PlannedWorkout.targetText(): String = listOfNotNull(
    distanceMeters?.let(Units::formatMiles),
    durationSec?.let { formatMinutes(it) },
    paceText(),
).joinToString(" · ")

/** "11:00–11:30 /mi", "9:45 /mi", or null without a pace target. */
fun PlannedWorkout.paceText(): String? {
    val fast = paceFastSecPerMile ?: return null
    val slow = paceSlowSecPerMile ?: return null
    if (fast.roundToLong() == slow.roundToLong()) return Units.formatPace(fast)
    return "${minutesSeconds(fast)}–${Units.formatPace(slow)}"
}

private fun formatMinutes(seconds: Long): String {
    val minutes = (seconds + 30) / 60
    return if (minutes >= 60) {
        String.format(Locale.US, "%d h %02d min", minutes / 60, minutes % 60)
    } else {
        "$minutes min"
    }
}

private fun minutesSeconds(secPerMile: Double): String {
    val total = secPerMile.roundToLong()
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}

/**
 * On the idle track screen: what the plan says to run today, and whether the
 * next run will be coached against it. On by default — that is the point of
 * having a plan — but a run that is not the workout (a jog with a friend) can
 * opt out.
 */
@Composable
fun TodaysWorkoutCard(
    workout: PlannedWorkout,
    coach: Boolean,
    willCoach: Boolean,
    onCoachChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.plan_today),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(workout.type.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    workout.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            val target = workout.targetText()
            if (target.isNotEmpty()) {
                Text(target, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            if (workout.description.isNotBlank()) {
                Text(
                    workout.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        if (willCoach) R.string.plan_coach_this_run else R.string.plan_coach_runs_only,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = coach && willCoach, enabled = willCoach, onCheckedChange = onCoachChange)
            }
        }
    }
}

/** During a coached run: what the run is being measured against. */
@Composable
fun CoachingBanner(workout: PlannedWorkout) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(workout.type.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Column {
                Text(
                    stringResource(R.string.plan_coaching, workout.title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                val target = workout.targetText()
                if (target.isNotEmpty()) {
                    Text(target, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}
