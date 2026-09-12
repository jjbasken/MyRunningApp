package com.myrunningapp.data.export

import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.Run
import com.myrunningapp.domain.model.RunPoint
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Turns a saved run into GPX 1.1 — the format every other running app reads, so
 * a run recorded here can still be pushed to Strava or opened in a map viewer.
 *
 * Each pause/resume becomes its own `<trkseg>`: readers draw a break between
 * segments rather than a straight line across the gap, which is exactly what
 * [RunPoint.segmentIndex] means here.
 *
 * A pure function over the run's rows, so its output is pinned by unit tests
 * rather than eyeballed in a text editor.
 */
object GpxWriter {

    private const val CREATOR = "MyRunningApp"

    fun write(run: Run, points: List<RunPoint>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"").append(CREATOR).append("\"")
        append(" xmlns=\"http://www.topografix.com/GPX/1/1\"")
        append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
        append(" xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1")
        append(" http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        append("  <metadata>\n")
        append("    <name>").append(escape(name(run))).append("</name>\n")
        append("    <time>").append(timestamp(run.startedAt)).append("</time>\n")
        append("  </metadata>\n")
        append("  <trk>\n")
        append("    <name>").append(escape(name(run))).append("</name>\n")
        // The GPX type vocabulary is free text; these are the values other
        // running apps recognise for the two activities recorded here.
        append("    <type>").append(type(run.activityType)).append("</type>\n")
        // groupBy keeps insertion order and points arrive ordered by time, so
        // segments come out in the order they were run.
        points.groupBy { it.segmentIndex }.values.forEach { segment ->
            append("    <trkseg>\n")
            segment.forEach { point ->
                append("      <trkpt lat=\"").append(coordinate(point.latitude))
                append("\" lon=\"").append(coordinate(point.longitude)).append("\">\n")
                append("        <ele>").append(elevation(point.altitudeMeters)).append("</ele>\n")
                append("        <time>").append(timestamp(point.timestamp)).append("</time>\n")
                append("      </trkpt>\n")
            }
            append("    </trkseg>\n")
        }
        append("  </trk>\n")
        append("</gpx>\n")
    }

    private fun name(run: Run): String =
        type(run.activityType).replaceFirstChar(Char::uppercase) + " " + timestamp(run.startedAt)

    private fun type(activityType: ActivityType): String = when (activityType) {
        ActivityType.RUN -> "running"
        ActivityType.WALK -> "walking"
        ActivityType.BIKE -> "cycling"
    }

    /** GPX wants whole-second UTC; sub-second GPS timestamps carry no meaning here. */
    private fun timestamp(value: Instant): String =
        DateTimeFormatter.ISO_INSTANT.format(value.truncatedTo(ChronoUnit.SECONDS))

    /** Seven decimals is about a centimetre — well past what any GPS resolves. */
    private fun coordinate(value: Double): String = String.format(Locale.US, "%.7f", value)

    private fun elevation(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
