package com.myrunningapp.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.myrunningapp.data.db.entity.ProfileEntity
import com.myrunningapp.data.db.entity.RunEntity
import com.myrunningapp.data.db.entity.RunPointEntity
import com.myrunningapp.data.db.entity.SplitEntity
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.Sex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class AppDatabaseTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `profile upsert replaces the single row`() = runTest {
        val dao = db.profileDao()
        dao.upsert(ProfileEntity(weightKg = 70.0, heightCm = 175.0, age = 30, sex = Sex.MALE))
        dao.upsert(ProfileEntity(weightKg = 68.0, heightCm = 175.0, age = 31, sex = Sex.FEMALE))

        val stored = dao.observe().first()
        assertEquals(68.0, stored!!.weightKg, 0.0)
        assertEquals(31, stored.age)
        assertEquals(Sex.FEMALE, stored.sex)
    }

    @Test
    fun `deleting a run cascades to its points and splits`() = runTest {
        val runId = db.runDao().insert(
            RunEntity(
                startedAt = Instant.ofEpochSecond(1_000),
                endedAt = Instant.ofEpochSecond(2_000),
                activityType = ActivityType.RUN,
                distanceMeters = 3000.0,
                movingDurationSec = 900,
                elapsedDurationSec = 1000,
                avgPaceSecPerMile = 480.0,
                calories = 300,
                weightKgAtRun = 70.0,
            ),
        )
        db.runPointDao().insert(
            RunPointEntity(
                runId = runId,
                timestamp = Instant.ofEpochSecond(1_001),
                latitude = 40.0,
                longitude = -105.0,
                altitudeMeters = 1600.0,
                accuracyMeters = 5f,
                segmentIndex = 0,
            ),
        )
        db.splitDao().insert(
            SplitEntity(
                runId = runId,
                splitNumber = 1,
                distanceMeters = 1609.344,
                durationSec = 480,
                paceSecPerMile = 480.0,
            ),
        )

        db.runDao().deleteById(runId)

        assertEquals(0, db.runPointDao().countForRun(runId))
        assertEquals(emptyList<SplitEntity>(), db.splitDao().getForRun(runId))
        assertNull(db.runDao().getById(runId))
    }

    @Test
    fun `runs are observed newest first`() = runTest {
        val older = RunEntity(
            startedAt = Instant.ofEpochSecond(1_000),
            endedAt = Instant.ofEpochSecond(2_000),
            activityType = ActivityType.RUN,
            distanceMeters = 1000.0,
            movingDurationSec = 300,
            elapsedDurationSec = 300,
            avgPaceSecPerMile = 480.0,
            calories = 100,
            weightKgAtRun = 70.0,
        )
        val newer = older.copy(startedAt = Instant.ofEpochSecond(9_000))
        db.runDao().insert(older)
        db.runDao().insert(newer)

        val ordered = db.runDao().observeAll().first()
        assertEquals(Instant.ofEpochSecond(9_000), ordered.first().startedAt)
    }
}
