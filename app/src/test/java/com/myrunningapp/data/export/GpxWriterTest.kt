package com.myrunningapp.data.export

import com.myrunningapp.domain.model.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class GpxWriterTest {

    private val gpx = GpxWriter.write(ExportFixtures.run, ExportFixtures.points)

    @Test
    fun `is well-formed XML`() {
        // Parsing is the real assertion: a hand-built string is easy to get
        // subtly wrong, and no other app would open it.
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(gpx.byteInputStream())
        assertEquals("gpx", document.documentElement.tagName)
        assertEquals("1.1", document.documentElement.getAttribute("version"))
    }

    @Test
    fun `a pause becomes a second track segment`() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(gpx.byteInputStream())
        val segments = document.getElementsByTagName("trkseg")
        assertEquals(2, segments.length)
        // All three points survive the split; only their grouping changes.
        assertEquals(3, document.getElementsByTagName("trkpt").length)
    }

    @Test
    fun `points carry position, elevation and time`() {
        assertTrue(gpx.contains("""<trkpt lat="47.6205000" lon="-122.3493000">"""))
        assertTrue(gpx.contains("<ele>12.5</ele>"))
        assertTrue(gpx.contains("<time>2026-09-08T07:30:00Z</time>"))
    }

    @Test
    fun `timestamps are whole-second UTC`() {
        // Nothing like "07:30:00.123Z" — GPX readers vary on sub-second precision.
        assertTrue(Regex("""<time>2026-09-08T07:31:30Z</time>""").containsMatchIn(gpx))
    }

    @Test
    fun `a walk is typed as walking`() {
        val walk = GpxWriter.write(
            ExportFixtures.run.copy(activityType = ActivityType.WALK),
            ExportFixtures.points,
        )
        assertTrue(walk.contains("<type>walking</type>"))
        assertTrue(gpx.contains("<type>running</type>"))
    }

    @Test
    fun `a run with no points still produces a valid file`() {
        val empty = GpxWriter.write(ExportFixtures.run, emptyList())
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(empty.byteInputStream())
        assertEquals("gpx", document.documentElement.tagName)
        assertEquals(0, document.getElementsByTagName("trkseg").length)
    }
}
