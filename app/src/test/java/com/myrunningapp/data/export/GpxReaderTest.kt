package com.myrunningapp.data.export

import com.myrunningapp.domain.model.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class GpxReaderTest {

    @Test
    fun `reads back what GpxWriter writes`() {
        val track = GpxReader.read(GpxWriter.write(ExportFixtures.run, ExportFixtures.points))

        assertEquals(ActivityType.RUN, track.activityType)
        assertEquals(listOf(2, 1), track.segments.map { it.size })
        val first = track.segments[0][0]
        assertEquals(ExportFixtures.START, first.timestamp)
        assertEquals(47.6205, first.latitude, 1e-9)
        assertEquals(-122.3493, first.longitude, 1e-9)
        assertEquals(12.5, first.altitudeMeters, 1e-9)
        assertEquals(ExportFixtures.START.plusSeconds(90), track.segments[1][0].timestamp)
    }

    @Test
    fun `reads a Strava-style file with extensions and a numeric type`() {
        val track = GpxReader.read(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx creator="StravaGPX" version="1.1" xmlns="http://www.topografix.com/GPX/1/1"
                 xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
             <metadata><time>2026-09-01T06:00:00Z</time></metadata>
             <trk>
              <name>Morning Ride</name>
              <type>1</type>
              <trkseg>
               <trkpt lat="40.0" lon="-105.0">
                <ele>1600.2</ele>
                <time>2026-09-01T06:00:00Z</time>
                <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>120</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>
               </trkpt>
               <trkpt lat="40.0001" lon="-105.0"><time>2026-09-01T06:00:01Z</time></trkpt>
              </trkseg>
             </trk>
            </gpx>
            """.trimIndent(),
        )

        assertEquals(ActivityType.BIKE, track.activityType)
        assertEquals(1, track.segments.size)
        assertEquals(1600.2, track.segments[0][0].altitudeMeters, 1e-9)
        // No <ele>: sea level rather than a lost point.
        assertEquals(0.0, track.segments[0][1].altitudeMeters, 0.0)
    }

    @Test
    fun `reads GPX 1_0 and files with no namespace at all`() {
        listOf(
            """<gpx version="1.0" xmlns="http://www.topografix.com/GPX/1/0">""",
            """<gpx version="1.1">""",
        ).forEach { root ->
            val track = GpxReader.read(
                """
                $root<trk><trkseg>
                <trkpt lat="1" lon="2"><time>2026-01-01T00:00:00Z</time></trkpt>
                </trkseg></trk></gpx>
                """.trimIndent(),
            )
            assertEquals(1, track.segments.single().size)
            assertNull(track.activityType)
        }
    }

    @Test
    fun `honours the encoding the file declares`() {
        val xml = """
            <?xml version="1.0" encoding="ISO-8859-1"?>
            <gpx version="1.1"><trk><name>Café</name><type>Laufen running</type><trkseg>
            <trkpt lat="1" lon="2"><time>2026-01-01T00:00:00Z</time></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()

        val track = GpxReader.read(xml.toByteArray(Charsets.ISO_8859_1))

        assertEquals(ActivityType.RUN, track.activityType)
    }

    @Test
    fun `tolerates whitespace ahead of the XML declaration`() {
        val xml = "\n  <?xml version=\"1.0\" encoding=\"UTF-8\"?>" + gpx(
            """<trkpt lat="1" lon="2"><time>2026-01-01T00:00:00Z</time></trkpt>""",
        )

        assertEquals(1, GpxReader.read(xml.toByteArray()).segments.single().size)
        assertEquals(1, GpxReader.read(xml).segments.single().size)
    }

    @Test
    fun `skips untimed and out-of-range points but keeps the rest`() {
        val track = GpxReader.read(
            gpx(
                """<trkpt lat="1" lon="2"><time>2026-01-01T00:00:00Z</time></trkpt>
                   <trkpt lat="1" lon="2"></trkpt>
                   <trkpt lat="91" lon="2"><time>2026-01-01T00:00:02Z</time></trkpt>
                   <trkpt lat="1.1" lon="2"><time>2026-01-01T00:00:03Z</time></trkpt>""",
            ),
        )

        assertEquals(2, track.segments.single().size)
    }

    @Test
    fun `reads offsets, fractions and zone-less times`() {
        assertEquals(Instant.parse("2026-01-01T05:00:00Z"), GpxReader.parseTime("2026-01-01T06:00:00+01:00"))
        assertEquals(Instant.parse("2026-01-01T00:00:00.250Z"), GpxReader.parseTime(" 2026-01-01T00:00:00.250Z "))
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), GpxReader.parseTime("2026-01-01T00:00:00"))
        assertNull(GpxReader.parseTime("yesterday"))
    }

    @Test
    fun `maps the type vocabularies other apps use`() {
        assertEquals(ActivityType.RUN, GpxReader.activityType("running"))
        assertEquals(ActivityType.RUN, GpxReader.activityType("trail_running"))
        assertEquals(ActivityType.RUN, GpxReader.activityType("9"))
        assertEquals(ActivityType.WALK, GpxReader.activityType("walking"))
        assertEquals(ActivityType.WALK, GpxReader.activityType("Hiking"))
        assertEquals(ActivityType.WALK, GpxReader.activityType("10"))
        assertEquals(ActivityType.BIKE, GpxReader.activityType("cycling"))
        assertEquals(ActivityType.BIKE, GpxReader.activityType("mountain_biking"))
        assertEquals(ActivityType.BIKE, GpxReader.activityType("Ride"))
        assertNull(GpxReader.activityType("swimming"))
        assertNull(GpxReader.activityType("  "))
        assertNull(GpxReader.activityType(null))
    }

    @Test
    fun `a file of untimed points is a route, not an activity`() {
        assertFails(GpxImportFailure.NO_TIMESTAMPS) {
            GpxReader.read(gpx("""<trkpt lat="1" lon="2"/><trkpt lat="1.1" lon="2"/>"""))
        }
    }

    @Test
    fun `a file of waypoints has no track`() {
        assertFails(GpxImportFailure.NO_TRACK) {
            GpxReader.read("""<gpx version="1.1"><wpt lat="1" lon="2"><name>Car</name></wpt></gpx>""")
        }
    }

    @Test
    fun `anything that is not GPX is refused`() {
        assertFails(GpxImportFailure.NOT_GPX) { GpxReader.read("not xml at all") }
        assertFails(GpxImportFailure.NOT_GPX) { GpxReader.read("<kml><Document/></kml>") }
        assertFails(GpxImportFailure.NOT_GPX) { GpxReader.read("<gpx><trk>") }
    }

    @Test
    fun `refuses a DTD rather than resolving its entities`() {
        assertFails(GpxImportFailure.NOT_GPX) {
            GpxReader.read(
                """
                <?xml version="1.0"?>
                <!DOCTYPE gpx [<!ENTITY xxe SYSTEM "file:///etc/hostname">]>
                <gpx version="1.1"><trk><name>&xxe;</name><trkseg>
                <trkpt lat="1" lon="2"><time>2026-01-01T00:00:00Z</time></trkpt>
                </trkseg></trk></gpx>
                """.trimIndent(),
            )
        }
    }

    private fun gpx(points: String) =
        """<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>$points</trkseg></trk></gpx>"""

    private fun assertFails(expected: GpxImportFailure, block: () -> Unit) {
        try {
            block()
            fail("expected $expected")
        } catch (e: GpxImportException) {
            assertEquals(expected, e.failure)
        }
    }
}
