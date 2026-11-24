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
import com.myrunningapp.data.db.entity.PlanWorkoutEntity
import com.myrunningapp.data.db.entity.SplitEntity
import com.myrunningapp.data.db.entity.TrainingPlanEntity
import com.myrunningapp.domain.plan.WorkoutStatus
import com.myrunningapp.domain.plan.WorkoutType
import java.time.LocalDate
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.HealthSyncState
import com.myrunningapp.domain.model.Sex
import io.mockk.mockk
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
            ProfileRepository(db.profileDao()), db.healthSyncDao(), mockk(relaxed = true))
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
                ProfileRepository(db.profileDao()), db.healthSyncDao(), mockk(relaxed = true))
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
            // finishRun never ran for this row, so recovery is the only thing
            // that can queue it; without that it would never reach Health Connect.
            assertEquals(HealthSyncState.PENDING, recovered.healthSyncState)
            assertEquals(1L, recovered.healthSyncVersion)
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
            ProfileRepository(db.profileDao()), db.healthSyncDao(), mockk(relaxed = true))
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
                .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
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
                            val entity = entities.getJSONObject(i)
                            val table = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                            // Indices too: Room validates them on migration, so a
                            // table built from createSql alone fails the upgrade.
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
                "INSERT INTO runs VALUES (1, 1000, 31000, 'RUN', 100.0, 30, 30, 482.8, 7, 70.0, 0, 0)",
            )
            helper.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
                .addCallback(AppDatabase.RECOVER_INTERRUPTED_RUNS).build()

            val run = db.runDao().getById(1)!!
            assertEquals(HealthSyncState.NOT_SYNCED, run.healthSyncState)
            assertEquals(0L, run.healthSyncVersion)
            assertEquals(100.0, run.distanceMeters, 0.0)
            assertTrue(db.healthSyncDao().pendingDeletions().isEmpty())
        } finally {
            helper.close()
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `migrating to 4 keeps runs and starts with no plan`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-3-4-test.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val schema = JSONObject(
                            javaClass.classLoader!!
                                .getResourceAsStream("com.myrunningapp.data.db.AppDatabase/3.json")!!
                                .reader().readText(),
                        )
                        val entities = schema.getJSONObject("database").getJSONArray("entities")
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
                "INSERT INTO runs VALUES (1, 1000, 31000, 'RUN', 100.0, 30, 30, 482.8, 7, 70.0, 0, 0, 'SYNCED', 2)",
            )
            helper.close()
            // Room validates the migrated tables against the entities on open, so
            // reaching the DAO at all proves MIGRATION_3_4 matches them.
            db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
                .addCallback(AppDatabase.RECOVER_INTERRUPTED_RUNS).build()

            assertNull(db.trainingPlanDao().getActivePlan())
            val run = db.runDao().getById(1)!!
            assertEquals(HealthSyncState.SYNCED, run.healthSyncState)
            assertEquals(100.0, run.distanceMeters, 0.0)
        } finally {
            helper.close()
            db.close()
            context.deleteDatabase(name)
        }
    }

    // --- training plan ------------------------------------------------------------

    private fun plan(name: String = "Half") = TrainingPlanEntity(
        name = name, raceDate = LocalDate.of(2027, 11, 14), raceDistanceMeters = 21_097.5,
        importedAt = Instant.ofEpochSecond(5_000),
    )

    private fun workout(date: LocalDate, title: String = "Easy run", status: WorkoutStatus = WorkoutStatus.PLANNED) =
        PlanWorkoutEntity(
            planId = 0, date = date, type = WorkoutType.EASY, title = title, description = "",
            distanceMeters = 4828.0, durationSec = null, paceFastSecPerMile = 690.0,
            paceSlowSecPerMile = 750.0, status = status, completedRunId = null,
        )

    @Test
    fun `importing a plan replaces the previous one whole`() = runTest {
        val dao = db.trainingPlanDao()
        val first = dao.replacePlan(plan("Old"), listOf(workout(LocalDate.of(2026, 12, 1))))
        val second = dao.replacePlan(
            plan("New"),
            listOf(workout(LocalDate.of(2026, 12, 2)), workout(LocalDate.of(2026, 12, 4))),
        )

        assertEquals("New", dao.getActivePlan()!!.name)
        assertTrue(dao.observeWorkouts(first).first().isEmpty())
        val workouts = dao.observeWorkouts(second).first()
        assertEquals(listOf(LocalDate.of(2026, 12, 2), LocalDate.of(2026, 12, 4)), workouts.map { it.workout.date })
    }

    @Test
    fun `merging more weeks keeps finished workouts and replaces planned ones`() = runTest {
        val dao = db.trainingPlanDao()
        val planId = dao.replacePlan(
            plan(),
            listOf(
                workout(LocalDate.of(2026, 12, 1), "Done", WorkoutStatus.COMPLETED),
                workout(LocalDate.of(2026, 12, 3), "Old version"),
            ),
        )
        val added = dao.mergeIntoPlan(
            planId,
            listOf(
                workout(LocalDate.of(2026, 12, 1), "Chunk day one"),
                workout(LocalDate.of(2026, 12, 3), "New version"),
                workout(LocalDate.of(2026, 12, 8), "Week two"),
            ),
        )

        // The finished day keeps its one workout; the chunk's copy of it is not added.
        val titles = dao.observeWorkouts(planId).first().map { it.workout.title }
        assertEquals(listOf("Done", "New version", "Week two"), titles)
        assertEquals(2, added)
    }

    @Test
    fun `re-importing the same weeks leaves finished and skipped days with one workout each`() = runTest {
        val dao = db.trainingPlanDao()
        val days = (1..4).map { LocalDate.of(2026, 12, it) }
        val planId = dao.replacePlan(plan(), days.map { workout(it) })
        val ids = dao.observeWorkouts(planId).first().map { it.workout.id }
        dao.setStatus(ids[0], WorkoutStatus.COMPLETED, null)
        dao.setStatus(ids[1], WorkoutStatus.SKIPPED, null)

        dao.mergeIntoPlan(planId, days.map { workout(it, "Again") })

        val workouts = dao.observeWorkouts(planId).first().map { it.workout }
        assertEquals(days, workouts.map { it.date })
        assertEquals(
            listOf(WorkoutStatus.COMPLETED, WorkoutStatus.SKIPPED, WorkoutStatus.PLANNED, WorkoutStatus.PLANNED),
            workouts.map { it.status },
        )
        assertEquals(listOf("Easy run", "Easy run", "Again", "Again"), workouts.map { it.title })
    }

    @Test
    fun `a merged double day keeps the finished run and adds only the other session`() = runTest {
        val dao = db.trainingPlanDao()
        val day = LocalDate.of(2026, 12, 1)
        val planId = dao.replacePlan(plan(), listOf(workout(day, "Morning easy", WorkoutStatus.COMPLETED)))

        val added = dao.mergeIntoPlan(
            planId,
            listOf(
                workout(day, "Strength").copy(type = WorkoutType.CROSS_TRAIN),
                workout(day, "Morning easy again"),
            ),
        )

        // The chunk's easy run matches the finished one by type; the strength session is new.
        assertEquals(1, added)
        assertEquals(
            listOf("Morning easy", "Strength"),
            dao.observeWorkouts(planId).first().map { it.workout.title },
        )
    }

    @Test
    fun `a finished day whose type the chunk changed still is not doubled`() = runTest {
        val dao = db.trainingPlanDao()
        val day = LocalDate.of(2026, 12, 1)
        val planId = dao.replacePlan(plan(), listOf(workout(day, "Easy", WorkoutStatus.COMPLETED)))

        val added = dao.mergeIntoPlan(planId, listOf(workout(day, "Now a tempo").copy(type = WorkoutType.TEMPO)))

        assertEquals(0, added)
        assertEquals(listOf("Easy"), dao.observeWorkouts(planId).first().map { it.workout.title })
    }

    @Test
    fun `a completed workout reports its run and outlives that run being deleted`() = runTest {
        val dao = db.trainingPlanDao()
        val planId = dao.replacePlan(plan(), listOf(workout(LocalDate.of(2026, 12, 1))))
        val workoutId = dao.observeWorkouts(planId).first().single().workout.id
        val runId = db.runDao().insert(
            RunEntity(
                startedAt = Instant.ofEpochSecond(1_000), endedAt = Instant.ofEpochSecond(3_000),
                activityType = ActivityType.RUN, distanceMeters = 5000.0, movingDurationSec = 1900,
                elapsedDurationSec = 2000, avgPaceSecPerMile = 611.0, calories = 400, weightKgAtRun = 70.0,
            ),
        )

        dao.setStatus(workoutId, WorkoutStatus.COMPLETED, runId)
        val linked = dao.observeWorkouts(planId).first().single()
        assertEquals(runId, linked.workout.completedRunId)
        assertEquals(5000.0, linked.runDistanceMeters!!, 0.0)
        assertEquals(611.0, linked.runPaceSecPerMile!!, 0.0)

        db.runDao().deleteById(runId)
        val orphaned = dao.observeWorkouts(planId).first().single()
        assertNull(orphaned.workout.completedRunId)
        assertEquals(WorkoutStatus.COMPLETED, orphaned.workout.status)
        assertNull(orphaned.runDistanceMeters)
    }

    @Test
    fun `deleting the plan takes its workouts but leaves runs alone`() = runTest {
        val dao = db.trainingPlanDao()
        val runId = db.runDao().insert(
            RunEntity(
                startedAt = Instant.ofEpochSecond(1_000), endedAt = Instant.ofEpochSecond(3_000),
                activityType = ActivityType.RUN, distanceMeters = 5000.0, movingDurationSec = 1900,
                elapsedDurationSec = 2000, avgPaceSecPerMile = 611.0, calories = 400, weightKgAtRun = 70.0,
            ),
        )
        val planId = dao.replacePlan(plan(), listOf(workout(LocalDate.of(2026, 12, 1))))
        val workoutId = dao.observeWorkouts(planId).first().single().workout.id
        dao.setStatus(workoutId, WorkoutStatus.COMPLETED, runId)

        dao.deleteAllPlans()

        assertNull(dao.getActivePlan())
        assertNull(dao.getWorkout(workoutId))
        assertEquals(5000.0, db.runDao().getById(runId)!!.distanceMeters, 0.0)
    }
}
