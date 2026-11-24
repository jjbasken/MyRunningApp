package com.myrunningapp.domain.plan

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** The usual race distances, offered as one-tap choices in the prompt form. */
enum class RaceDistance(val miles: Double) {
    FIVE_K(3.1),
    TEN_K(6.2),
    HALF_MARATHON(13.1),
    MARATHON(26.2),
    ;

    companion object {
        /** The longest race the form accepts: [PlanParser] rejects longer single workouts. */
        const val MAX_MILES = 60.0
        const val MIN_MILES = 1.0

        /** The preset [miles] is, to a tenth of a mile, or null for any other distance. */
        fun of(miles: Double): RaceDistance? = entries.firstOrNull { kotlin.math.abs(it.miles - miles) < 0.05 }
    }
}

/** What the runner tells the app before it writes a prompt for them. */
data class PlanRequest(
    val raceDate: LocalDate,
    val raceDistanceMiles: Double = 13.1,
    val runsPerWeek: Int = 4,
    val currentWeeklyMiles: Double = 10.0,
    val longestRecentRunMiles: Double = 3.0,
    /** Free text: injuries, preferred long-run day, a goal time. */
    val notes: String = "",
)

/**
 * The prompt the runner pastes into an AI chat assistant to get a plan back in the
 * shape [PlanParser] reads.
 *
 * The app does not call an AI itself: run data never leaves the phone, and the
 * runner can use whichever assistant they like. So the prompt has to carry the
 * whole contract — the schema, the date convention, and "JSON only" — and it asks
 * for week/day numbering rather than calendar dates, which models get wrong far
 * less often over a year-long plan.
 */
object PlanPrompt {

    fun build(request: PlanRequest, today: LocalDate): String {
        // Plans start on the coming Monday, unless the race comes first: then this
        // week's, or the prompt would have training begin after the race.
        val comingMonday = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        val start = if (request.raceDate.isBefore(comingMonday)) {
            today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        } else {
            comingMonday
        }
        val weeks = ((request.raceDate.toEpochDay() - start.toEpochDay()) / 7 + 1)
            .coerceAtLeast(1)
        val distance = miles(request.raceDistanceMiles)
        val race = raceName(request.raceDistanceMiles)
        val taperWeeks = taperWeeks(request.raceDistanceMiles)
        val taper = if (taperWeeks == 1) "the final week" else "the last $taperWeeks weeks"
        val notes = request.notes.trim().takeIf { it.isNotEmpty() }
            ?.let { "\n- Other notes: $it" }.orEmpty()

        return """
You are an experienced running coach. Write me a complete $race training plan.

About me:
- Race date: ${request.raceDate} (race distance $distance miles)
- Plan starts: $start (a Monday) — that is $weeks ${if (weeks == 1L) "week" else "weeks"} of training
- I can run ${request.runsPerWeek} days per week
- I currently run about ${miles(request.currentWeeklyMiles)} miles per week; my longest recent run is ${miles(request.longestRecentRunMiles)} miles$notes

Coaching guidelines:
- Build weekly mileage gradually (no more than about 10% per week) with a lighter recovery week every 3–4 weeks.
- Most runs should be easy. Include long runs, and tempo or interval sessions once I have a base.
- Taper over $taper. The final workout is the race itself on ${request.raceDate}, type "race".
- Give target paces in minutes per mile as a range, e.g. "10:30-11:00". Leave targetPace out if a run should be by feel.

Output format — this is imported by an app, so follow it exactly:
- Reply with ONLY a single JSON object. No prose before or after it.
- Use "week" (1 = the week starting $start) and "day" ("Mon" … "Sun") for every workout. Do not use calendar dates.
- List only running days; leave rest days out.
- Keep "description" under 100 characters.
- "type" is one of: "easy", "long", "tempo", "intervals", "recovery", "race".

{
  "name": "${race.replaceFirstChar { it.uppercase() }} plan",
  "startDate": "$start",
  "raceDate": "${request.raceDate}",
  "raceDistanceMiles": $distance,
  "workouts": [
    { "week": 1, "day": "Tue", "type": "easy", "title": "Easy run", "distanceMiles": 3, "targetPace": "11:30-12:30", "description": "Conversational effort." },
    { "week": 1, "day": "Thu", "type": "intervals", "title": "6 x 400m", "distanceMiles": 3.5, "targetPace": "9:30-10:00", "description": "Warm up 1 mi, 6 x 400m fast with 400m jog, cool down." },
    { "week": 1, "day": "Sat", "type": "long", "title": "Long run", "durationMinutes": 45, "description": "Slow and steady." }
  ]
}

If the whole plan will not fit in one reply, stop at the end of a week and tell me; I will ask for the rest. Keep the same startDate and keep counting weeks from 1.
        """.trimIndent()
    }

    /** "half marathon", "5K", or "8-mile race" for a distance with no common name. */
    internal fun raceName(miles: Double): String = when (RaceDistance.of(miles)) {
        RaceDistance.FIVE_K -> "5K"
        RaceDistance.TEN_K -> "10K"
        RaceDistance.HALF_MARATHON -> "half marathon"
        RaceDistance.MARATHON -> "marathon"
        null -> "${miles(miles)}-mile race"
    }

    /** Longer races need longer to freshen up for: a week for a 10K or less, three for a marathon. */
    internal fun taperWeeks(miles: Double): Int = when {
        miles <= 6.3 -> 1
        miles <= 13.2 -> 2
        else -> 3
    }

    /** 13.1 -> "13.1", 4.0 -> "4". */
    private fun miles(value: Double): String =
        String.format(Locale.US, "%.1f", value).removeSuffix(".0")
}
