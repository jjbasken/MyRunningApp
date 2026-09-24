package com.myrunningapp.data.export

import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.tracking.GpsFix
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.StringReader
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

/** What [GpxReader] found in a file: the track's points, one list per `<trkseg>`. */
data class GpxTrack(
    /** Read from the track's `<type>`; null when the file does not say or says something unknown. */
    val activityType: ActivityType?,
    val segments: List<List<GpsFix>>,
)

/** Why a file could not become an activity. Each case gets its own message on screen. */
enum class GpxImportFailure {
    /** Not XML, or XML that is not GPX. */
    NOT_GPX,

    /** Too large to be a single activity; refused rather than risk running out of memory. */
    TOO_LARGE,

    /** The file could not be opened or read at all. */
    UNREADABLE,

    /** A GPX file with no track points at all — often a set of waypoints. */
    NO_TRACK,

    /** Track points without times: a planned route, not a recorded activity. */
    NO_TIMESTAMPS,

    /** Too few usable points to cover any distance. */
    TOO_SHORT,

    /** It covers the same time as an activity already in history — most likely imported before. */
    ALREADY_IMPORTED,

    /** Anything else — a database error, say. Reported rather than crashing the app. */
    FAILED,
}

class GpxImportException(val failure: GpxImportFailure) : Exception(failure.name)

/**
 * Reads the tracks out of a GPX 1.0 or 1.1 file — [GpxWriter]'s output, and
 * what Strava, Garmin and most other apps export.
 *
 * Only track points (`<trkpt>`) are read. Routes and waypoints describe where
 * someone meant to go rather than when they got there, and without times there
 * is no pace, moving time or splits to show. Every track in the file is read in
 * order; its segments are kept apart, since a segment break is how GPX marks a
 * pause and the import turns it back into one.
 *
 * Elements are matched on their local name, ignoring the namespace, so a GPX 1.0
 * file or one missing its namespace declaration still reads. Extensions (heart
 * rate, cadence) are ignored — this app has nowhere to keep them.
 *
 * Pure apart from the platform XML parser, so it is pinned by unit tests.
 */
object GpxReader {

    fun read(xml: String): GpxTrack = read(InputSource(StringReader(xml)))

    /** Reads raw file bytes, leaving the encoding to the file's XML declaration. */
    fun read(bytes: ByteArray): GpxTrack = read(InputSource(ByteArrayInputStream(bytes)))

    private fun read(source: InputSource): GpxTrack {
        val handler = Handler()
        try {
            parserFactory().newSAXParser().xmlReader.apply {
                contentHandler = handler
                errorHandler = handler
                // Stop at the DOCTYPE itself, before an internal subset can
                // define entities, on parsers that ignore disallow-doctype-decl.
                runCatching { setProperty("http://xml.org/sax/properties/lexical-handler", handler) }
                // Nothing a GPX file needs lives outside it, so never go fetching.
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
            }.parse(source)
        } catch (e: SAXException) {
            throw GpxImportException(GpxImportFailure.NOT_GPX)
        } catch (e: IOException) {
            throw GpxImportException(GpxImportFailure.NOT_GPX)
        }

        if (!handler.sawGpxRoot) throw GpxImportException(GpxImportFailure.NOT_GPX)
        if (handler.pointCount == 0) throw GpxImportException(GpxImportFailure.NO_TRACK)
        val segments = handler.segments.filter { it.isNotEmpty() }
        if (segments.isEmpty()) throw GpxImportException(GpxImportFailure.NO_TIMESTAMPS)
        return GpxTrack(activityType = activityType(handler.trackType), segments = segments)
    }

    /**
     * Maps the track's free-text `<type>` onto the three activities tracked
     * here. Covers [GpxWriter]'s own words, the variants Garmin and Komoot use,
     * and Strava's numeric codes (1 ride, 4 hike, 9 run, 10 walk).
     */
    internal fun activityType(type: String?): ActivityType? {
        val value = type?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            value == "9" || "run" in value || "jog" in value -> ActivityType.RUN
            value == "4" || value == "10" || "walk" in value || "hik" in value -> ActivityType.WALK
            value == "1" || "cycl" in value || "bik" in value || "ride" in value -> ActivityType.BIKE
            else -> null
        }
    }

    /**
     * GPX times are meant to be UTC with a `Z`, but offsets and zone-less times
     * both turn up in the wild; a zone-less time is read as UTC, as the spec says
     * it should have been written.
     */
    internal fun parseTime(text: String): Instant? {
        val value = text.trim()
        return try {
            OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (e: DateTimeParseException) {
            try {
                LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC)
            } catch (e: DateTimeParseException) {
                null
            }
        }
    }

    private fun parserFactory(): SAXParserFactory = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        // No DTDs or external entities: a GPX file has no use for either, and
        // honouring them is how a hostile XML file reads local files. Not every
        // platform parser knows every feature, so each is best-effort; the
        // entity resolver above is the backstop.
        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        ).forEach { (feature, value) -> runCatching { setFeature(feature, value) } }
    }

    private class Handler : DefaultHandler2() {
        var sawGpxRoot = false
        val segments = mutableListOf<MutableList<GpsFix>>()
        var trackType: String? = null
        /** Every `<trkpt>` with a position, timed or not — to tell "no track" from "no times". */
        var pointCount = 0

        private val path = ArrayDeque<String>()
        private val text = StringBuilder()
        private var latitude: Double? = null
        private var longitude: Double? = null
        private var elevation: Double? = null
        private var time: Instant? = null

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            val name = localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')
            if (path.isEmpty() && name == "gpx") sawGpxRoot = true
            path.addLast(name)
            text.setLength(0)
            when (name) {
                "trkseg" -> segments += mutableListOf<GpsFix>()
                "trkpt" -> {
                    latitude = attributes.getValue("lat")?.trim()?.toDoubleOrNull()
                    longitude = attributes.getValue("lon")?.trim()?.toDoubleOrNull()
                    elevation = null
                    time = null
                }
            }
        }

        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
            throw SAXException("GPX has no use for a DTD")
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = path.removeLastOrNull() ?: return
            val parent = path.lastOrNull()
            when {
                name == "ele" && parent == "trkpt" -> elevation = text.toString().trim().toDoubleOrNull()
                name == "time" && parent == "trkpt" -> time = parseTime(text.toString())
                // The first track's type stands for the file; a file mixing
                // activities is rare enough to not second-guess.
                name == "type" && parent == "trk" && trackType == null -> trackType = text.toString()
                name == "trkpt" -> endPoint()
            }
            text.setLength(0)
        }

        private fun endPoint() {
            val lat = latitude?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return
            val lon = longitude?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return
            pointCount++
            val at = time ?: return
            // A <trkpt> outside any <trkseg> is invalid GPX, but keep it rather than lose it.
            if (segments.isEmpty()) segments += mutableListOf<GpsFix>()
            segments.last() += GpsFix(
                timestamp = at,
                latitude = lat,
                longitude = lon,
                altitudeMeters = elevation?.takeIf { it.isFinite() } ?: 0.0,
                // GPX carries no accuracy radius. Zero means unknown, as it does on
                // android.location.Location: the filter judges on speed alone, and
                // Health Connect is not told a made-up figure.
                accuracyMeters = 0f,
            )
        }
    }
}
