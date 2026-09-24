package com.myrunningapp.data.export

import com.myrunningapp.data.repository.RunRepository
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.tracking.TrackReplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a GPX file from another app into an activity in history — the
 * counterpart to [RunExporter.exportRun].
 *
 * Like the exporter it stops short of the filesystem: the screen reads the
 * picked `Uri` and hands over the text.
 */
@Singleton
class RunImporter @Inject constructor(
    private val runRepository: RunRepository,
) {

    /**
     * @param fallbackType used when the file does not say what the activity
     *   was. The user can correct it afterwards from the run's own screen.
     * @return the new run's id.
     * @throws GpxImportException when the file cannot become an activity.
     */
    suspend fun importGpx(
        gpx: ByteArray,
        fallbackType: ActivityType = ActivityType.RUN,
    ): Long = withContext(Dispatchers.Default) {
        val track = GpxReader.read(gpx)
        val activityType = track.activityType ?: fallbackType
        val replay = TrackReplay.replay(track.segments, activityType)
            ?: throw GpxImportException(GpxImportFailure.TOO_SHORT)
        if (runRepository.overlapsExisting(replay.startedAt, replay.endedAt)) {
            throw GpxImportException(GpxImportFailure.ALREADY_IMPORTED)
        }
        runRepository.importRun(
            activityType = activityType,
            snapshot = replay.snapshot,
            points = replay.points,
            startedAt = replay.startedAt,
            endedAt = replay.endedAt,
        )
    }
}
