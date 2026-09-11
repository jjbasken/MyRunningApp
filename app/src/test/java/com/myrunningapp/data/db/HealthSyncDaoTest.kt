package com.myrunningapp.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.myrunningapp.data.db.entity.HealthDeletionEntity
import com.myrunningapp.data.db.entity.RunEntity
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.HealthSyncCounts
import com.myrunningapp.domain.model.HealthSyncState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class HealthSyncDaoTest {

    private lateinit var db: AppDatabase
    private val t0: Instant = Instant.parse("2026-09-09T12:00:00Z")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insertRun(
        state: HealthSyncState = HealthSyncState.NOT_SYNCED,
        inProgress: Boolean = false,
        startedAt: Instant = t0,
        version: Long = 0,
    ): Long = db.runDao().insert(
        RunEntity(
            startedAt = startedAt, endedAt = startedAt.plusSeconds(600),
            activityType = ActivityType.RUN,
            distanceMeters = 1609.34, movingDurationSec = 600, elapsedDurationSec = 600,
            avgPaceSecPerMile = 600.0, calories = 120, weightKgAtRun = 70.0,
            isInProgress = inProgress, healthSyncState = state, healthSyncVersion = version,
        ),
    )

    @Test
    fun `a new run starts out unsynced`() = runTest {
        val id = insertRun()

        assertEquals(HealthSyncState.NOT_SYNCED, db.runDao().getById(id)!!.healthSyncState)
    }

    @Test
    fun `pending runs are returned oldest first and nothing else is`() = runTest {
        val pending = insertRun(HealthSyncState.PENDING)
        insertRun(HealthSyncState.SYNCED)
        insertRun(HealthSyncState.FAILED)

        val rows = db.healthSyncDao().pendingRuns()

        assertEquals(listOf(pending), rows.map { it.id })
    }

    @Test
    fun `an in-progress run is never pending, even if marked`() = runTest {
        insertRun(HealthSyncState.PENDING, inProgress = true)

        assertTrue(db.healthSyncDao().pendingRuns().isEmpty())
    }

    @Test
    fun `marking a state replaces the old one`() = runTest {
        val id = insertRun(HealthSyncState.PENDING)

        db.healthSyncDao().markState(id, HealthSyncState.SYNCED)

        assertEquals(HealthSyncState.SYNCED, db.runDao().getById(id)!!.healthSyncState)
    }

    @Test
    fun `backfill marks every finished unsynced run pending and leaves synced ones alone`() = runTest {
        insertRun(HealthSyncState.NOT_SYNCED)
        insertRun(HealthSyncState.NOT_SYNCED)
        val synced = insertRun(HealthSyncState.SYNCED)
        insertRun(HealthSyncState.NOT_SYNCED, inProgress = true)

        val marked = db.healthSyncDao().markAllPending()

        assertEquals(2, marked)
        assertEquals(HealthSyncState.SYNCED, db.runDao().getById(synced)!!.healthSyncState)
    }

    @Test
    fun `a queued deletion survives the run row disappearing`() = runTest {
        val id = insertRun(HealthSyncState.SYNCED)
        db.healthSyncDao().queueDeletion(HealthDeletionEntity(runId = id, requestedAt = t0))
        db.runDao().deleteById(id)

        assertEquals(listOf(id), db.healthSyncDao().pendingDeletions().map { it.runId })
    }

    @Test
    fun `clearing a deletion removes it from the queue`() = runTest {
        db.healthSyncDao().queueDeletion(HealthDeletionEntity(runId = 7, requestedAt = t0))

        db.healthSyncDao().clearDeletion(7)

        assertTrue(db.healthSyncDao().pendingDeletions().isEmpty())
    }

    @Test
    fun `queueing the same deletion twice leaves one row`() = runTest {
        db.healthSyncDao().queueDeletion(HealthDeletionEntity(runId = 7, requestedAt = t0))
        db.healthSyncDao().queueDeletion(HealthDeletionEntity(runId = 7, requestedAt = t0.plusSeconds(5)))

        val deletions = db.healthSyncDao().pendingDeletions()
        assertEquals(1, deletions.size)
        assertEquals(t0.plusSeconds(5), deletions[0].requestedAt)
    }

    @Test
    fun `retrying failed runs puts them back in the queue`() = runTest {
        insertRun(HealthSyncState.FAILED)

        assertEquals(1, db.healthSyncDao().retryFailed())
        assertEquals(1, db.healthSyncDao().pendingRuns().size)
    }

    @Test
    fun `counts are zero when there are no runs at all`() = runTest {
        // SUM over no rows is NULL in SQLite, which Room cannot read into a
        // non-null Int: the query has to COALESCE.
        assertEquals(HealthSyncCounts(0, 0, 0), db.healthSyncDao().observeCounts().first())
    }

    @Test
    fun `counts describe the queue`() = runTest {
        insertRun(HealthSyncState.PENDING)
        insertRun(HealthSyncState.SYNCED)
        insertRun(HealthSyncState.SYNCED)
        insertRun(HealthSyncState.FAILED)

        val counts = db.healthSyncDao().observeCounts().first()

        assertEquals(1, counts.pending)
        assertEquals(2, counts.synced)
        assertEquals(1, counts.failed)
    }

    @Test
    fun `an in-progress run is not counted in any sync state`() = runTest {
        insertRun(HealthSyncState.PENDING, inProgress = true)
        insertRun(HealthSyncState.SYNCED, inProgress = true)
        insertRun(HealthSyncState.FAILED, inProgress = true)

        val counts = db.healthSyncDao().observeCounts().first()

        assertEquals(0, counts.pending)
        assertEquals(0, counts.synced)
        assertEquals(0, counts.failed)
    }

    @Test
    fun `an in-progress failed run is not promoted by retryFailed`() = runTest {
        insertRun(HealthSyncState.FAILED, inProgress = true)

        assertEquals(0, db.healthSyncDao().retryFailed())
        assertTrue(db.healthSyncDao().pendingRuns().isEmpty())
    }

    @Test
    fun `queueing a run bumps its version so the rewrite outranks what is stored`() = runTest {
        val id = insertRun(HealthSyncState.SYNCED, version = 3)

        db.healthSyncDao().markPending(id)

        val row = db.runDao().getById(id)!!
        assertEquals(HealthSyncState.PENDING, row.healthSyncState)
        assertEquals(4L, row.healthSyncVersion)
    }

    @Test
    fun `the backfill and Sync now bump versions too`() = runTest {
        val fresh = insertRun(HealthSyncState.NOT_SYNCED, version = 1)
        val failed = insertRun(HealthSyncState.FAILED, version = 7)

        db.healthSyncDao().markAllPending()
        db.healthSyncDao().retryFailed()

        assertEquals(2L, db.runDao().getById(fresh)!!.healthSyncVersion)
        assertEquals(8L, db.runDao().getById(failed)!!.healthSyncVersion)
    }

    @Test
    fun `an outcome lands when the run is still the one that was written`() = runTest {
        val id = insertRun(HealthSyncState.PENDING, version = 2)

        db.healthSyncDao().markOutcome(id, HealthSyncState.SYNCED, version = 2)

        assertEquals(HealthSyncState.SYNCED, db.runDao().getById(id)!!.healthSyncState)
    }

    @Test
    fun `an outcome is refused once an edit has re-queued the run`() = runTest {
        val id = insertRun(HealthSyncState.PENDING, version = 2)
        // The edit that landed while the write was in flight.
        db.healthSyncDao().markPending(id)

        db.healthSyncDao().markOutcome(id, HealthSyncState.SYNCED, version = 2)

        // Still queued, so the edit gets published rather than buried.
        assertEquals(HealthSyncState.PENDING, db.runDao().getById(id)!!.healthSyncState)
        assertEquals(3L, db.runDao().getById(id)!!.healthSyncVersion)
    }

    @Test
    fun `a run deleted mid-write takes its outcome with it`() = runTest {
        val id = insertRun(HealthSyncState.PENDING, version = 1)
        db.runDao().deleteById(id)

        db.healthSyncDao().markOutcome(id, HealthSyncState.SYNCED, version = 1)

        assertNull(db.runDao().getById(id))
    }

    @Test
    fun `paging past a stuck page reaches the runs behind it`() = runTest {
        val stuck = insertRun(HealthSyncState.PENDING, startedAt = t0)
        val newer = insertRun(HealthSyncState.PENDING, startedAt = t0.plusSeconds(3_600))

        // One run per page, and the first one never leaves PENDING: asking again
        // for "the oldest pending run" would return it forever.
        assertEquals(listOf(stuck), db.healthSyncDao().pendingRuns(limit = 1).map { it.id })
        val next = db.healthSyncDao().pendingRunsAfter(
            afterStartedAt = t0.toEpochMilli(), afterId = stuck, limit = 1,
        )

        assertEquals(listOf(newer), next.map { it.id })
    }

    @Test
    fun `runs sharing a start time are still paged past one at a time`() = runTest {
        val first = insertRun(HealthSyncState.PENDING, startedAt = t0)
        val second = insertRun(HealthSyncState.PENDING, startedAt = t0)

        val next = db.healthSyncDao().pendingRunsAfter(
            afterStartedAt = t0.toEpochMilli(), afterId = first, limit = 10,
        )

        assertEquals(listOf(second), next.map { it.id })
    }
}
