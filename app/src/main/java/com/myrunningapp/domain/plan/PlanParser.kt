package com.myrunningapp.domain.plan

import com.myrunningapp.domain.Units
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToLong

/** Why a pasted plan could not be imported, for a message the user can act on. */
enum class PlanImportFailure {
    /** Nothing that parses as a JSON object — usually a reply pasted with no JSON in it. */
    NOT_JSON,

    /** Valid JSON, but no workouts in it. */
    NO_WORKOUTS,

    /** A workout with no usable date, or a date that is not YYYY-MM-DD. */
    BAD_DATE,

    /** A workout with a value outside anything a plan could mean. */
    BAD_WORKOUT,

    /** More workouts than any plan has — a sign this is not a plan at all. */
    TOO_MANY,
}

class PlanImportException(
    val failure: PlanImportFailure,
    /** Which workout, and what was wrong with it, when that is known. */
    val detail: String? = null,
) : Exception("${failure.name}${detail?.let { ": $it" } ?: ""}")

/**
 * Reads a training plan written by an AI chat assistant.
 *
 * The format is the one [PlanPrompt] asks for, but the reader is deliberately
 * forgiving, because the text arrives by copy and paste from a chat reply:
 *
 *  - the JSON may be wrapped in a Markdown code fence or a sentence of preamble;
 *  - trailing commas and comments are tolerated;
 *  - each workout gives either an explicit `date`, or a `week` and `day` counted
 *    from the plan's `startDate` (or back from its `raceDate`);
 *  - workouts may sit in one flat `workouts` list or be grouped under `weeks`;
 *  - distances come in miles, kilometres or metres, paces in `/mi` or `/km`,
 *    as one pace or a range.
 *
 * A pure function of the text and today's date, so every one of those cases is a
 * unit test rather than a trip through a chat app.
 */
object PlanParser {

    const val MAX_WORKOUTS = 1500
    private const val MAX_DISTANCE_MILES = 60.0
    private const val MAX_DURATION_SEC = 8 * 3600L
    private const val FASTEST_PACE_SEC_PER_MILE = 3 * 60.0
    private const val SLOWEST_PACE_SEC_PER_MILE = 30 * 60.0
    private const val MILES_PER_KM = 1000.0 / Units.METERS_PER_MILE

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        isLenient = true
        allowTrailingComma = true
        allowComments = true
    }

    /**
     * @param today anchors a plan that gives neither a start nor a race date: it
     *   starts on the coming Monday (today, if today is a Monday).
     * @throws PlanImportException when the text cannot become a plan.
     */
    fun parse(text: String, today: LocalDate): ParsedPlan {
        val element = extractJson(text)?.let(::unwrapBackup)
            ?: throw PlanImportException(PlanImportFailure.NOT_JSON)
        // A bare array of workouts is a plan too, just one with no name or dates.
        val root = element as? JsonObject
        val rawWorkouts = when (element) {
            is JsonObject -> collectWorkouts(element)
            is JsonArray -> flatten(element, week = null)
            else -> emptyList()
        }
        if (rawWorkouts.isEmpty()) throw PlanImportException(PlanImportFailure.NO_WORKOUTS)
        if (rawWorkouts.size > MAX_WORKOUTS) throw PlanImportException(PlanImportFailure.TOO_MANY)

        val raceDate = root?.string("raceDate", "race_date")?.let { parseDate(it, "raceDate") }
        val startDate = root?.string("startDate", "start_date")?.let { parseDate(it, "startDate") }
        val lastWeek = rawWorkouts.mapNotNull { it.week }.maxOrNull() ?: 1
        val weekOneMonday = when {
            startDate != null -> startDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            raceDate != null -> raceDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .minusWeeks((lastWeek - 1).toLong())
            else -> today.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        }

        val workouts = rawWorkouts.mapIndexed { index, raw ->
            readWorkout(raw.obj, raw.week, weekOneMonday, label = "workout ${index + 1}")
        }.filterNotNull().sortedBy { it.date }
        if (workouts.isEmpty()) throw PlanImportException(PlanImportFailure.NO_WORKOUTS)

        return ParsedPlan(
            name = root?.string("name", "title", "planName")?.trim()?.take(80)
                ?.takeIf { it.isNotEmpty() } ?: "Training plan",
            raceDate = raceDate,
            raceDistanceMeters = root?.let { readDistance(it, "raceDistance") },
            workouts = workouts,
        )
    }

    // --- finding the JSON ------------------------------------------------------

    /**
     * Tries the text as it is, then the inside of a code fence, then the span
     * from the first brace to the last — the shapes a chat reply arrives in.
     */
    private fun extractJson(text: String): JsonElement? {
        val candidates = buildList {
            add(text.trim())
            FENCE.find(text)?.groupValues?.get(1)?.let { add(it.trim()) }
            val open = text.indexOf('{')
            val close = text.lastIndexOf('}')
            if (open >= 0 && close > open) add(text.substring(open, close + 1))
            val openArray = text.indexOf('[')
            val closeArray = text.lastIndexOf(']')
            if (openArray >= 0 && closeArray > openArray) add(text.substring(openArray, closeArray + 1))
        }
        for (candidate in candidates) {
            val element = try {
                json.parseToJsonElement(candidate)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            if (element is JsonObject || element is JsonArray) return element
        }
        return null
    }

    /**
     * The app's own full backup keeps the plan under `plan`, in this format; opening
     * the backup file restores it. Anything else is read as it is.
     */
    private fun unwrapBackup(element: JsonElement): JsonElement {
        val root = element as? JsonObject ?: return element
        val plan = root["plan"] as? JsonObject ?: return element
        return if (root["runs"] is JsonArray && plan["workouts"] is JsonArray) plan else element
    }

    private val FENCE = Regex("```(?:json|JSON)?\\s*([\\s\\S]*?)```")

    private class RawWorkout(val obj: JsonObject, val week: Int?)

    private fun flatten(array: JsonArray?, week: Int?): List<RawWorkout> =
        array.orEmpty().filterIsInstance<JsonObject>().map { RawWorkout(it, it.int("week") ?: week) }

    /** Gathers `workouts` and `weeks[].workouts` into one list. */
    private fun collectWorkouts(root: JsonObject): List<RawWorkout> {
        val flat = flatten(root.array("workouts", "sessions", "days", "schedule"), week = null)
        val grouped = root.array("weeks").orEmpty().withIndex().flatMap { (index, element) ->
            if (element !is JsonObject) return@flatMap emptyList<RawWorkout>()
            val week = element.int("week", "weekNumber") ?: (index + 1)
            flatten(element.array("workouts", "sessions", "days"), week)
        }
        return flat + grouped
    }

    // --- one workout -------------------------------------------------------------

    private fun readWorkout(
        obj: JsonObject,
        week: Int?,
        weekOneMonday: LocalDate,
        label: String,
    ): ParsedWorkout? {
        val date = obj.string("date")?.let { parseDate(it, label) }
            ?: run {
                val day = obj["day"]?.let(::parseDay)
                    ?: obj["dayOfWeek"]?.let(::parseDay)
                if (week == null || day == null || week < 1) {
                    throw PlanImportException(PlanImportFailure.BAD_DATE, "$label has no date, or no week and day")
                }
                weekOneMonday.plusWeeks((week - 1).toLong()).plusDays((day.value - 1).toLong())
            }

        val typeText = obj.string("type", "kind", "workoutType")
        val title = obj.string("title", "name", "summary")?.trim()?.take(80)
        val type = parseType(typeText ?: title)
        val description = obj.string("description", "notes", "details", "instructions")
            ?.trim()?.take(500).orEmpty()

        val distance = readDistance(obj, "distance")
        if (distance != null && (distance <= 0.0 || Units.metersToMiles(distance) > MAX_DISTANCE_MILES)) {
            throw PlanImportException(PlanImportFailure.BAD_WORKOUT, "$label distance")
        }
        val duration = readDuration(obj)
        if (duration != null && (duration <= 0 || duration > MAX_DURATION_SEC)) {
            throw PlanImportException(PlanImportFailure.BAD_WORKOUT, "$label duration")
        }
        val pace = readPace(obj, label)

        return ParsedWorkout(
            date = date,
            type = type,
            title = title?.takeIf { it.isNotEmpty() } ?: defaultTitle(type),
            description = description,
            distanceMeters = distance.takeIf { type != WorkoutType.REST },
            durationSec = duration.takeIf { type != WorkoutType.REST },
            paceFastSecPerMile = pace?.first.takeIf { type.isRun },
            paceSlowSecPerMile = pace?.second.takeIf { type.isRun },
            status = parseStatus(obj.string("status")),
        )
    }

    /** "completed" and "skipped", as the app's backup writes them; anything else is still to do. */
    private fun parseStatus(text: String?): WorkoutStatus =
        when (text?.trim()?.lowercase()) {
            "completed", "complete", "done" -> WorkoutStatus.COMPLETED
            "skipped" -> WorkoutStatus.SKIPPED
            else -> WorkoutStatus.PLANNED
        }

    private fun parseDate(text: String, label: String): LocalDate = try {
        LocalDate.parse(text.trim().take(10))
    } catch (e: DateTimeParseException) {
        throw PlanImportException(PlanImportFailure.BAD_DATE, "$label: \"$text\"")
    }

    private fun parseDay(element: JsonElement): DayOfWeek? {
        val primitive = element as? JsonPrimitive ?: return null
        primitive.doubleOrNull?.let { number ->
            val n = number.toInt()
            return if (n in 1..7) DayOfWeek.of(n) else null
        }
        val text = primitive.contentOrNull?.trim()?.uppercase() ?: return null
        if (text.length < 2) return null
        return DayOfWeek.entries.firstOrNull { it.name.startsWith(text.take(3)) }
            ?: when (text.take(2)) {
                "TU" -> DayOfWeek.TUESDAY
                "TH" -> DayOfWeek.THURSDAY
                else -> null
            }
    }

    /** Maps the many names a chat assistant uses for a session onto [WorkoutType]. */
    internal fun parseType(text: String?): WorkoutType {
        val t = text?.uppercase()?.replace(Regex("[^A-Z]"), "") ?: return WorkoutType.EASY
        return when {
            t.isEmpty() -> WorkoutType.EASY
            "REST" in t || t == "OFF" || "DAYOFF" in t -> WorkoutType.REST
            // Checked before RACE: "pre-race shakeout" and "goal race pace" are not the race,
            // and coaching them as one would urge the runner on the day before it.
            "SHAKEOUT" in t || "PRERACE" in t -> WorkoutType.RECOVERY
            "RACEPACE" in t || "GOALPACE" in t -> WorkoutType.TEMPO
            "RACE" in t -> WorkoutType.RACE
            "CROSS" in t || "STRENGTH" in t || "YOGA" in t || "BIKE" in t ||
                "CYCL" in t || "SWIM" in t || t == "XT" -> WorkoutType.CROSS_TRAIN
            "LONG" in t -> WorkoutType.LONG
            "TEMPO" in t || "THRESHOLD" in t || "PROGRESSION" in t -> WorkoutType.TEMPO
            "INTERVAL" in t || "SPEED" in t || "REPEAT" in t || "FARTLEK" in t ||
                "HILL" in t || "TRACK" in t -> WorkoutType.INTERVALS
            "RECOVERY" in t || "SHAKEOUT" in t -> WorkoutType.RECOVERY
            "EASY" in t || "BASE" in t || "AEROBIC" in t || t == "RUN" -> WorkoutType.EASY
            else -> WorkoutType.OTHER
        }
    }

    private fun defaultTitle(type: WorkoutType): String = when (type) {
        WorkoutType.EASY -> "Easy run"
        WorkoutType.LONG -> "Long run"
        WorkoutType.TEMPO -> "Tempo run"
        WorkoutType.INTERVALS -> "Intervals"
        WorkoutType.RECOVERY -> "Recovery run"
        WorkoutType.RACE -> "Race day"
        WorkoutType.CROSS_TRAIN -> "Cross-training"
        WorkoutType.REST -> "Rest day"
        WorkoutType.OTHER -> "Run"
    }

    // --- quantities ----------------------------------------------------------------

    /**
     * `<prefix>Miles`, `<prefix>Km` or `<prefix>Meters`, in metres. A bare
     * `<prefix>` is read too, as assistants often write `"distance": "5 km"`:
     * in the unit it names, or miles, the unit the prompt asks for.
     */
    private fun readDistance(obj: JsonObject, prefix: String): Double? {
        obj.number("${prefix}Miles", "${prefix}_miles", "${prefix}Mi")?.let { return Units.milesToMeters(it) }
        obj.number("${prefix}Km", "${prefix}_km", "${prefix}Kilometers")?.let { return it * 1000.0 }
        obj.number("${prefix}Meters", "${prefix}_meters", "${prefix}M")?.let { return it }
        return readBareDistance(obj[prefix])
    }

    /**
     * `5`, `"5"`, `"5 km"`, `"5K"`, `"400m"`, `"3.1 miles"`. Only a lone number and
     * unit: `"6 x 400m"` describes a session, not a distance, and read as 6 metres
     * it would have the coach call the workout done at the start line.
     */
    private fun readBareDistance(element: JsonElement?): Double? {
        val primitive = (element as? JsonPrimitive)?.takeUnless { it is JsonNull } ?: return null
        if (!primitive.isString) return primitive.doubleOrNull?.let(Units::milesToMeters)
        val match = BARE_DISTANCE.matchEntire(primitive.content.trim().lowercase()) ?: return null
        val value = match.groupValues[1].toDouble()
        return when (match.groupValues[2]) {
            "", "mi", "mile", "miles" -> Units.milesToMeters(value)
            "k", "km", "kms", "kilometer", "kilometers", "kilometre", "kilometres" -> value * 1000.0
            "m", "meter", "meters", "metre", "metres" -> value
            else -> null
        }
    }

    private fun readDuration(obj: JsonObject): Long? {
        obj.number("durationMinutes", "duration_minutes", "minutes", "durationMin")
            ?.let { return (it * 60).roundToLong() }
        obj.number("durationSeconds", "duration_seconds")?.let { return it.roundToLong() }
        return null
    }

    /**
     * `"9:30"`, `"9:30/mi"`, `"5:45 /km"`, `"9:15-9:45"` and `"9:15–9:45 min/mile"`,
     * as a `(fast, slow)` pair in seconds per mile. A single pace is both ends.
     */
    private fun readPace(obj: JsonObject, label: String): Pair<Double, Double>? {
        val text = obj.string("targetPace", "pace", "target_pace", "paceRange") ?: return null
        val matches = PACE_TIME.findAll(text).toList()
        // "conversational", "easy effort" — fine, no number to coach to.
        val first = matches.firstOrNull() ?: return null

        // The second time is the other end of a range only when joined to the first
        // by a dash or "to"; "9:30/mi (5:54/km)" is one pace said twice, not a range.
        val second = matches.getOrNull(1)?.takeIf {
            RANGE_JOIN.matches(text.substring(first.range.last + 1, it.range.first))
        }
        val rangeEnd = (second ?: first).range.last + 1
        // The unit is the one written with this pace, up to any other pace that follows.
        val unitText = text.substring(0, matches.firstOrNull { it.range.first >= rangeEnd }?.range?.first ?: text.length)
        val perKm = Regex("(?i)km|kilomet").containsMatchIn(unitText)

        val perMile = listOfNotNull(first, second).map { m ->
            val seconds = m.groupValues[1].toInt() * 60.0 + m.groupValues[2].toInt()
            if (perKm) seconds / MILES_PER_KM else seconds
        }
        val fast = perMile.min()
        val slow = perMile.max()
        if (fast < FASTEST_PACE_SEC_PER_MILE || slow > SLOWEST_PACE_SEC_PER_MILE) {
            throw PlanImportException(PlanImportFailure.BAD_WORKOUT, "$label pace \"$text\"")
        }
        return fast to slow
    }

    private val PACE_TIME = Regex("(\\d{1,2}):([0-5]\\d)")
    private val RANGE_JOIN = Regex("\\s*(-|–|—|to)\\s*", RegexOption.IGNORE_CASE)

    // --- JSON helpers -----------------------------------------------------------------

    private fun JsonObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
    }

    /**
     * A number, or a string that starts with one — "3.5", "3.5 miles" and 3.5 are
     * the same distance. A string with no leading number is no number.
     */
    private fun JsonObject.number(vararg keys: String): Double? = keys.firstNotNullOfOrNull { key ->
        val primitive = this[key] as? JsonPrimitive ?: return@firstNotNullOfOrNull null
        if (primitive is JsonNull) return@firstNotNullOfOrNull null
        primitive.doubleOrNull
            ?: primitive.contentOrNull?.let { LEADING_NUMBER.find(it)?.value?.toDoubleOrNull() }
    }

    private val LEADING_NUMBER = Regex("^\\s*\\d+(\\.\\d+)?")
    private val BARE_DISTANCE = Regex("(\\d+(?:\\.\\d+)?)\\s*([a-z]*)\\.?")

    private fun JsonObject.int(vararg keys: String): Int? = number(*keys)?.toInt()

    private fun JsonObject.array(vararg keys: String): JsonArray? =
        keys.firstNotNullOfOrNull { this[it] as? JsonArray }
}
