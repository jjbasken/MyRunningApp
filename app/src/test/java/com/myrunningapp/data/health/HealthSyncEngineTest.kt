package com.myrunningapp.data.health

import com.myrunningapp.data.FakeHealthSyncDao
import com.myrunningapp.data.FakeRunDao
import com.myrunningapp.data.FakeRunPointDao
import com.myrunningapp.data.FakeSplitDao
import com.myrunningapp.data.db.entity.HealthDeletionEntity
import com.myrunningapp.data.db.entity.RunEntity
import com.myrunningapp.data.db.entity.RunPointEntity
import com.myrunningapp.data.db.entity.SplitEntity
import com.myrunningapp.domain.health.HealthAvailability
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.HealthSyncCounts
import com.myrunningapp.domain.model.HealthSyncState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HealthSyncEngineTest {

    private val t0: Instant = Instant.parse("2026-09-09T12:00:00Z")
    private val runDao = FakeRunDao()
    private val healthSyncDao = FakeHealthSyncDao(runDao)
    private val runPointDao = FakeRunPointDao()
    private val splitDao = FakeSplitDao()
    private val gateway = FakeHealthConnectGateway()
    private var enabled = true

    private val engine = HealthSyncEngine(
        healthSyncDao = healthSyncDao,
        runPointDao = runPointDao,
        splitDao = splitDao,
        gateway = gateway,
        syncEnabled = { enabled },
    )

    private suspend fun pendingRun(
        id: Long = 0,
        state: HealthSyncState = HealthSyncState.PENDING,
        distanceMeters: Double = 1609.34,
    ): Long {
        val runId = runDao.insert(
            RunEntity(
                id = id, startedAt = t0, endedAt = t0.plusSeconds(600),
                activityType = ActivityType.RUN, distanceMeters = distanceMeters,
                movingDurationSec = 600, elapsedDurationSec = 600,
                avgPaceSecPerMile = 600.0, calories = 120, weightKgAtRun = 70.0,
                healthSyncState = state,
            ),
        )
        runPointDao.insertAll(
            listOf(
                RunPointEntity(runId = runId, timestamp = t0, latitude = 40.0, longitude = -105.0,
                    altitudeMeters = 1600.0, accuracyMeters = 5f, segmentIndex = 0),
                // Strictly before endedAt: WorkoutRecordBuilder excludes a route point that
                // lands exactly on it (see WorkoutRecordBuilderTest), and a fix landing on the
                // boundary is exactly what a GPS stream does at the end of a run.
                RunPointEntity(runId = runId, timestamp = t0.plusSeconds(599), latitude = 40.01,
                    longitude = -105.0, altitudeMeters = 1600.0, accuracyMeters = 5f, segmentIndex = 0),
            ),
        )
        splitDao.insert(
            SplitEntity(runId = runId, splitNumber = 1, distanceMeters = 1609.34,
                durationSec = 600, paceSecPerMile = 600.0),
        )
        return runId
    }

    private fun stateOf(id: Long) = runDao.rows[id]!!.healthSyncState

    @Test
    fun `a pending run is written and marked synced`() = runTest {
        val id = pendingRun()

        assertEquals(HealthSyncOutcome.COMPLETED, engine.sync())

        assertEquals(listOf("run-$id"), gateway.written.map { it.clientRecordId })
        assertEquals(HealthSyncState.SYNCED, stateOf(id))
    }

    @Test
    fun `nothing is written while the toggle is off`() = runTest {
        val id = pendingRun()
        enabled = false

        assertEquals(HealthSyncOutcome.DISABLED, engine.sync())

        assertTrue(gateway.written.isEmpty())
        assertEquals(HealthSyncState.PENDING, stateOf(id))
    }

    @Test
    fun `nothing is written when Health Connect is absent`() = runTest {
        pendingRun()
        gateway.availability = HealthAvailability.NOT_INSTALLED

        assertEquals(HealthSyncOutcome.UNAVAILABLE, engine.sync())

        assertTrue(gateway.written.isEmpty())
    }

    @Test
    fun `revoked permission leaves the queue untouched rather than failing it`() = runTest {
        val id = pendingRun()
        gateway.writePermissions = false

        assertEquals(HealthSyncOutcome.PERMISSION_MISSING, engine.sync())

        assertEquals(HealthSyncState.PENDING, stateOf(id))
    }

    @Test
    fun `permission revoked mid-drain stops without failing the rest`() = runTest {
        val first = pendingRun(id = 1)
        val second = pendingRun(id = 2)
        gateway.writeResults.addLast(HealthWriteResult.Success)
        gateway.writeResults.addLast(HealthWriteResult.PermissionMissing)

        assertEquals(HealthSyncOutcome.PERMISSION_MISSING, engine.sync())

        assertEquals(HealthSyncState.SYNCED, stateOf(first))
        assertEquals(HealthSyncState.PENDING, stateOf(second))
    }

    @Test
    fun `a retryable failure leaves the run pending and asks to be run again`() = runTest {
        val id = pendingRun()
        gateway.writeResults.addLast(HealthWriteResult.Retryable)

        assertEquals(HealthSyncOutcome.RETRY_LATER, engine.sync())

        assertEquals(HealthSyncState.PENDING, stateOf(id))
    }

    @Test
    fun `a rejection marks the run failed and does not block the next one`() = runTest {
        val bad = pendingRun(id = 1)
        val good = pendingRun(id = 2)
        gateway.writeResults.addLast(HealthWriteResult.Rejected("malformed"))

        assertEquals(HealthSyncOutcome.COMPLETED, engine.sync())

        assertEquals(HealthSyncState.FAILED, stateOf(bad))
        assertEquals(HealthSyncState.SYNCED, stateOf(good))
    }

    @Test
    fun `an edited run is rewritten under the same client id rather than duplicated`() = runTest {
        val id = pendingRun()
        engine.sync()
        healthSyncDao.markState(id, HealthSyncState.PENDING)

        engine.sync()

        assertEquals(listOf("run-$id", "run-$id"), gateway.written.map { it.clientRecordId })
    }

    @Test
    fun `queued deletions drain and clear`() = runTest {
        healthSyncDao.queueDeletion(HealthDeletionEntity(runId = 7, requestedAt = t0))

        assertEquals(HealthSyncOutcome.COMPLETED, engine.sync())

        assertEquals(listOf("run-7"), gateway.deleted)
        assertTrue(healthSyncDao.pendingDeletions().isEmpty())
    }

    @Test
    fun `deletions drain before writes so a delete cannot be undone by a stale write`() = runTest {
        pendingRun(id = 1)
        healthSyncDao.queueDeletion(HealthDeletionEntity(runId = 9, requestedAt = t0))

        engine.sync()

        assertEquals(1, gateway.deleted.size)
        assertEquals(1, gateway.written.size)
    }

    @Test
    fun `a failed deletion stays queued`() = runTest {
        healthSyncDao.queueDeletion(HealthDeletionEntity(runId = 7, requestedAt = t0))
        gateway.deleteResult = HealthWriteResult.Retryable

        assertEquals(HealthSyncOutcome.RETRY_LATER, engine.sync())

        assertEquals(listOf(7L), healthSyncDao.pendingDeletions().map { it.runId })
    }

    @Test
    fun `the route is left out when its permission is not granted`() = runTest {
        pendingRun()
        gateway.routePermission = false

        engine.sync()

        assertTrue(gateway.written.single().route.isEmpty())
    }

    @Test
    fun `the route is included when its permission is granted`() = runTest {
        pendingRun()

        engine.sync()

        assertEquals(2, gateway.written.single().route.size)
    }

    @Test
    fun `a run with nothing in it is marked not applicable rather than a permanent failure`() = runTest {
        val id = pendingRun(distanceMeters = 0.0)

        assertEquals(HealthSyncOutcome.COMPLETED, engine.sync())

        assertEquals(HealthSyncState.NOT_APPLICABLE, stateOf(id))
        assertTrue(gateway.written.isEmpty())
    }

    @Test
    fun `not-applicable runs do not count as failed and Sync now cannot resurrect them`() = runTest {
        pendingRun(distanceMeters = 0.0)

        engine.sync()

        var counts: HealthSyncCounts? = null
        healthSyncDao.observeCounts().collect { counts = it }
        assertEquals(0, counts!!.failed)
        assertEquals(0, counts!!.pending)
        // retryFailed's WHERE clause only matches FAILED — a not-applicable run
        // must not be promoted back to PENDING by "Sync now".
        assertEquals(0, healthSyncDao.retryFailed())
    }

    @Test
    fun `a backfill of more than one page fully drains rather than stopping at the first page`() = runTest {
        val ids = (1L..120L).map { pendingRun(id = it) }

        assertEquals(HealthSyncOutcome.COMPLETED, engine.sync())

        assertEquals(120, gateway.written.size)
        ids.forEach { assertEquals(HealthSyncState.SYNCED, stateOf(it)) }
    }

    @Test
    fun `a full page of retryable writes does not loop forever`() = runTest {
        // 51 pending runs (more than one page), every write Retryable: nothing
        // ever leaves PENDING. The cursor is what stops this from spinning on
        // the same page, and it also means every run is still attempted once.
        val ids = (1L..51L).map { pendingRun(id = it) }
        ids.forEach { _ -> gateway.writeResults.addLast(HealthWriteResult.Retryable) }

        assertEquals(HealthSyncOutcome.RETRY_LATER, engine.sync())

        ids.forEach { assertEquals(HealthSyncState.PENDING, stateOf(it)) }
        assertEquals(0, gateway.writeResults.size)
    }

    @Test
    fun `a page of stuck runs does not starve the runs behind it`() = runTest {
        // A page's worth of runs that always come back Retryable sit at the head
        // of the queue forever. Asking repeatedly for "the oldest pending runs"
        // would hand back only those, and nothing recorded afterwards could ever
        // be published; paging past them reaches the newer run in the same drain.
        val stuck = (1L..50L).map { pendingRun(id = it) }
        val newer = pendingRun(id = 51)
        stuck.forEach { _ -> gateway.writeResults.addLast(HealthWriteResult.Retryable) }

        assertEquals(HealthSyncOutcome.RETRY_LATER, engine.sync())

        stuck.forEach { assertEquals(HealthSyncState.PENDING, stateOf(it)) }
        assertEquals(HealthSyncState.SYNCED, stateOf(newer))
        assertEquals(listOf("run-$newer"), gateway.written.map { it.clientRecordId })
    }

    @Test
    fun `the published record version rises with every re-queue`() = runTest {
        val id = pendingRun(id = 1)
        healthSyncDao.markPending(id)

        engine.sync()
        val first = gateway.written.single().clientRecordVersion

        healthSyncDao.markPending(id)
        engine.sync()

        val second = gateway.written.last().clientRecordVersion
        // Health Connect keeps the higher-versioned copy of a client record id,
        // so a rewrite that reused the version could be discarded outright.
        assertTrue("$second should outrank $first", second > first)
    }

    @Test
    fun `an edit landing mid-write is not buried by the write it raced`() = runTest {
        val id = pendingRun(id = 1)
        healthSyncDao.markPending(id)
        // The gateway stands in for the moment the write is in flight: the user
        // corrects the run while it is out, which re-queues it at a new version.
        gateway.onWrite = { healthSyncDao.markPending(id) }

        engine.sync()

        // The verdict describes a copy that is already stale, so it must not
        // land - the run stays queued and the edit gets published next drain.
        assertEquals(HealthSyncState.PENDING, stateOf(id))
    }
}
