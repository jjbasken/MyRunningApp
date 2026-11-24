package com.myrunningapp.domain.plan

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Locale

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
        val start = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        val weeks = ((request.raceDate.toEpochDay() - start.toEpochDay()) / 7 + 1)
            .coerceAtLeast(1)
        val distance = miles(request.raceDistanceMiles)
        val notes = request.notes.trim().takeIf { it.isNotEmpty() }
            ?.let { "\n- Other notes: $it" }.orEmpty()

        return """
You are an experienced running coach. Write me a complete $distance-mile race training plan.

About me:
- Race date: ${request.raceDate} (race distance $distance miles)
- Plan starts: $start (a Monday) — that is $weeks weeks of training
- I can run ${request.runsPerWeek} days per week
- I currently run about ${miles(request.currentWeeklyMiles)} miles per week; my longest recent run is ${miles(request.longestRecentRunMiles)} miles$notes

Coaching guidelines:
- Build weekly mileage gradually (no more than about 10% per week) with a lighter recovery week every 3–4 weeks.
- Most runs should be easy. Include long runs, and tempo or interval sessions once I have a base.
- Taper over the last 2 weeks. The final workout is the race itself on ${request.raceDate}, type "race".
- Give target paces in minutes per mile as a range, e.g. "10:30-11:00". Leave targetPace out if a run should be by feel.

Output format — this is imported by an app, so follow it exactly:
- Reply with ONLY a single JSON object. No prose before or after it.
- Use "week" (1 = the week starting $start) and "day" ("Mon" … "Sun") for every workout. Do not use calendar dates.
- List only running days; leave rest days out.
- Keep "description" under 100 characters.
- "type" is one of: "easy", "long", "tempo", "intervals", "recovery", "race".

{
  "name": "Half marathon plan",
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

    /** 13.1 -> "13.1", 4.0 -> "4". */
    private fun miles(value: Double): String =
        String.format(Locale.US, "%.1f", value).removeSuffix(".0")
}
