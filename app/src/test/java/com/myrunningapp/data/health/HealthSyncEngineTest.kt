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
    fun `a run with nothing in it is marked failed rather than retried forever`() = runTest {
        val id = pendingRun(distanceMeters = 0.0)

        engine.sync()

        assertEquals(HealthSyncState.FAILED, stateOf(id))
        assertTrue(gateway.written.isEmpty())
    }
}
