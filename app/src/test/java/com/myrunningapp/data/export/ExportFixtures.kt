package com.myrunningapp.data.export

import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.Run
import com.myrunningapp.domain.model.RunPoint
import com.myrunningapp.domain.model.Split
import java.time.Instant

/** A short two-segment run shared by the export tests. */
object ExportFixtures {

    val START: Instant = Instant.parse("2026-09-08T07:30:00Z")

    val run = Run(
        id = 7L,
        startedAt = START,
        endedAt = START.plusSeconds(600),
        activityType = ActivityType.RUN,
        distanceMeters = 1609.344,
        movingDurationSec = 540,
        elapsedDurationSec = 600,
        avgPaceSecPerMile = 540.0,
        calories = 118,
        weightKgAtRun = 75.0,
    )

    /** Two points before the pause, one after — so the export has two segments. */
    val points = listOf(
        point(id = 1, seconds = 0, lat = 47.6205000, lon = -122.3493000, ele = 12.5, segment = 0),
        point(id = 2, seconds = 30, lat = 47.6210000, lon = -122.3490000, ele = 13.0, segment = 0),
        point(id = 3, seconds = 90, lat = 47.6215000, lon = -122.3487000, ele = 13.5, segment = 1),
    )

    val splits = listOf(
        Split(
            id = 1,
            runId = 7L,
            splitNumber = 1,
            distanceMeters = 1609.344,
            durationSec = 540,
            paceSecPerMile = 540.0,
        ),
    )

    private fun point(
        id: Long,
        seconds: Long,
        lat: Double,
        lon: Double,
        ele: Double,
        segment: Int,
    ) = RunPoint(
        id = id,
        runId = 7L,
        timestamp = START.plusSeconds(seconds),
        latitude = lat,
        longitude = lon,
        altitudeMeters = ele,
        accuracyMeters = 6f,
        segmentIndex = segment,
    )
}
