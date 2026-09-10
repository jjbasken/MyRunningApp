package com.myrunningapp.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.json.JSONObject
import androidx.room.Room
import com.myrunningapp.data.repository.RunRepository
import com.myrunningapp.data.repository.ProfileRepository
import com.myrunningapp.domain.tracking.RunSnapshot
import com.myrunningapp.domain.tracking.TrackedPoint
import com.myrunningapp.domain.tracking.GpsFix
import com.myrunningapp.domain.model.RunSessionState
import androidx.test.core.app.ApplicationProvider
import com.myrunningapp.data.db.entity.ProfileEntity
import com.myrunningapp.data.db.entity.RunEntity
import com.myrunningapp.data.db.entity.RunPointEntity
import com.myrunningapp.data.db.entity.SplitEntity
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.HealthSyncState
import com.myrunningapp.domain.model.Sex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    @Test
    fun `active run is hidden and cannot be deleted`() = runTest {
        val repository = RunRepository(db.runDao(), db.runPointDao(), db.splitDao(),
            ProfileRepository(db.profileDao()), db.healthSyncDao())
        val id = repository.startRun(ActivityType.RUN, Instant.EPOCH, 70.0)
        assertTrue(db.runDao().observeAll().first().isEmpty())
        repository.deleteRun(id)
        db.runDao().delete(db.runDao().getById(id)!!)
        assertTrue(db.runDao().getById(id)!!.isInProgress)
        db.runPointDao().insert(RunPointEntity(runId = id, timestamp = Instant.EPOCH,
            latitude = 40.0, longitude = -105.0, altitudeMeters = 0.0,
            accuracyMeters = 5f, segmentIndex = 0))
        assertEquals(1, db.runPointDao().countForRun(id))
    }

    @Test
    fun `reopening recovers checkpoint summary route and partial split`() = runTest {
        db.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "recovery-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addCallback(AppDatabase.RECOVER_INTERRUPTED_RUNS).build()
        try {
            db = open()
            val repository = RunRepository(db.runDao(), db.runPointDao(), db.splitDao(),
                ProfileRepository(db.profileDao()), db.healthSyncDao())
            val id = repository.startRun(ActivityType.RUN, Instant.EPOCH, 70.0)
            val snapshot = RunSnapshot.idle(ActivityType.RUN).copy(
                state = RunSessionState.PAUSED, startedAt = Instant.EPOCH,
                distanceMeters = 100.0, movingDurationSec = 30,
                elapsedDurationSec = 60, avgPaceSecPerMile = 482.8032,
            )
            val fix = GpsFix(Instant.ofEpochSecond(30), 40.0, -105.0, 0.0, 5f)
            repository.checkpoint(id, listOf(TrackedPoint(fix, 0)), snapshot, Instant.ofEpochSecond(60))
            assertTrue(db.runDao().observeAll().first().isEmpty())
            db.close()
            db = open()
            val recovered = db.runDao().observeAll().first().single()
            assertEquals(id, recovered.id)
            assertFalse(recovered.isInProgress)
            assertTrue(recovered.wasRecovered)
            assertEquals(100.0, recovered.distanceMeters, 0.0)
            assertEquals(30L, recovered.movingDurationSec)
            assertEquals(60L, recovered.elapsedDurationSec)
            assertEquals(Instant.ofEpochSecond(60), recovered.endedAt)
            assertTrue(recovered.calories > 0)
            assertEquals(1, db.runPointDao().countForRun(id))
            val split = db.splitDao().getForRun(id).single()
            assertEquals(100.0, split.distanceMeters, 0.0)
            assertEquals(30L, split.durationSec)
            db.runDao().deleteById(id)
            assertNull(db.runDao().getById(id))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `failed checkpoint rolls back points splits and summary`() = runTest {
        val repository = RunRepository(db.runDao(), db.runPointDao(), db.splitDao(),
            ProfileRepository(db.profileDao()), db.healthSyncDao())
        val id = repository.startRun(ActivityType.RUN, Instant.EPOCH, 70.0)
        val run = db.runDao().getById(id)!!
        val point = RunPointEntity(id = 1, runId = id, timestamp = Instant.EPOCH,
            latitude = 40.0, longitude = -105.0, altitudeMeters = 0.0,
            accuracyMeters = 5f, segmentIndex = 0)
        val split = SplitEntity(id = 1, runId = id, splitNumber = 1,
            distanceMeters = 100.0, durationSec = 30, paceSecPerMile = 482.8)
        db.splitDao().insert(split)
        try {
            db.runDao().checkpoint(run.copy(distanceMeters = 200.0), listOf(point),
                listOf(split.copy(distanceMeters = 200.0), split))
            fail("Duplicate split IDs should fail the transaction")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            // The route insert and split replacement must both have rolled back.
        }
        assertEquals(0, db.runPointDao().countForRun(id))
        assertEquals(100.0, db.splitDao().getForRun(id).single().distanceMeters, 0.0)
        assertEquals(0.0, db.runDao().getById(id)!!.distanceMeters, 0.0)
    }

    @Test
    fun `version one migration preserves saved activities and their routes`() = runTest {
        db.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val schema = javaClass.classLoader!!.getResourceAsStream(
            "com.myrunningapp.data.db.AppDatabase/1.json",
        )!!.bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val entities = schema.getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val table = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                            val indices = entity.getJSONArray("indices")
                            for (j in 0 until indices.length()) {
                                db.execSQL(indices.getJSONObject(j).getString("createSql")
                                    .replace("\${TABLE_NAME}", table))
                            }
                        }
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            helper.writableDatabase.execSQL(
                "INSERT INTO runs VALUES (1, 1000, 31000, 'RUN', 100.0, 30, 30, 482.8, 7, 70.0)",
            )
            helper.writableDatabase.execSQL(
                "INSERT INTO run_points VALUES (1, 1, 1000, 40.0, -105.0, 0.0, 5.0, 0)",
            )
            helper.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_1_2)
                .addCallback(AppDatabase.RECOVER_INTERRUPTED_RUNS).build()
            val run = db.runDao().observeAll().first().single()
            assertEquals(100.0, run.distanceMeters, 0.0)
            assertEquals(30L, run.movingDurationSec)
            assertFalse(run.isInProgress)
            assertFalse(run.wasRecovered)
            assertEquals(1, db.runPointDao().countForRun(1))
        } finally {
            helper.close()
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `migrating to 3 leaves existing runs unsynced and adds the deletion queue`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-2-3-test.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // The v2 schema, as checked in at app/schemas/.../2.json.
                        val schema = JSONObject(
                            javaClass.classLoader!!
                                .getResourceAsStream("com.myrunningapp.data.db.AppDatabase/2.json")!!
                                .reader().readText(),
                        )
                        val entities = schema.getJSONObject("database").getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            db.execSQL(entities.getJSONObject(i).getString("createSql")
                                .replace("\${TABLE_NAME}", entities.getJSONObject(i).getString("tableName")))
                        }
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            helper.writableDatabase.execSQL(
                "INSERT INTO runs VALUES (1, 1000, 31000, 'RUN', 100.0, 30, 30, 482.8, 7, 70.0, 0, 0)",
            )
            helper.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
                .addCallback(AppDatabase.RECOVER_INTERRUPTED_RUNS).build()

            val run = db.runDao().getById(1)!!
            assertEquals(HealthSyncState.NOT_SYNCED, run.healthSyncState)
            assertEquals(100.0, run.distanceMeters, 0.0)
            assertTrue(db.healthSyncDao().pendingDeletions().isEmpty())
        } finally {
            helper.close()
            db.close()
            context.deleteDatabase(name)
        }
    }

}
