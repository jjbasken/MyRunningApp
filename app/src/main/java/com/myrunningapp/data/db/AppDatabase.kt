package com.myrunningapp.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.myrunningapp.data.db.dao.HealthSyncDao
import com.myrunningapp.data.db.dao.ProfileDao
import com.myrunningapp.data.db.dao.RunDao
import com.myrunningapp.data.db.dao.RunPointDao
import com.myrunningapp.data.db.dao.SplitDao
import com.myrunningapp.data.db.dao.TrainingPlanDao
import com.myrunningapp.data.db.entity.HealthDeletionEntity
import com.myrunningapp.data.db.entity.ProfileEntity
import com.myrunningapp.data.db.entity.RunEntity
import com.myrunningapp.data.db.entity.RunPointEntity
import com.myrunningapp.data.db.entity.PlanWorkoutEntity
import com.myrunningapp.data.db.entity.SplitEntity
import com.myrunningapp.data.db.entity.TrainingPlanEntity
import com.myrunningapp.domain.plan.WorkoutCoach

@Database(
    entities = [
        RunEntity::class,
        RunPointEntity::class,
        SplitEntity::class,
        ProfileEntity::class,
        HealthDeletionEntity::class,
        TrainingPlanEntity::class,
        PlanWorkoutEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun runDao(): RunDao
    abstract fun runPointDao(): RunPointDao
    abstract fun splitDao(): SplitDao
    abstract fun profileDao(): ProfileDao
    abstract fun healthSyncDao(): HealthSyncDao
    abstract fun trainingPlanDao(): TrainingPlanDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE runs ADD COLUMN isInProgress INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE runs ADD COLUMN wasRecovered INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Adds the Health Connect outbox. Deliberately leaves every run
         * NOT_SYNCED: nothing may be published before the user switches the
         * feature on, and switching it on is what queues the backfill.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE runs ADD COLUMN healthSyncState TEXT NOT NULL DEFAULT 'NOT_SYNCED'",
                )
                db.execSQL(
                    "ALTER TABLE runs ADD COLUMN healthSyncVersion INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS health_deletions (
                        runId INTEGER NOT NULL PRIMARY KEY,
                        requestedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Adds the training plan. Purely additive: no existing table changes,
         * and the app has no plan until the user imports one. The SQL mirrors
         * what Room generates for the two entities, which Room checks on open.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `training_plans` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `raceDate` INTEGER, " +
                        "`raceDistanceMeters` REAL, `importedAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plan_workouts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`planId` INTEGER NOT NULL, `date` INTEGER NOT NULL, " +
                        "`type` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                        "`distanceMeters` REAL, `durationSec` INTEGER, " +
                        "`paceFastSecPerMile` REAL, `paceSlowSecPerMile` REAL, " +
                        "`status` TEXT NOT NULL, `completedRunId` INTEGER, " +
                        "FOREIGN KEY(`planId`) REFERENCES `training_plans`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`completedRunId`) REFERENCES `runs`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE SET NULL )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_plan_workouts_planId_date` " +
                        "ON `plan_workouts` (`planId`, `date`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_plan_workouts_completedRunId` " +
                        "ON `plan_workouts` (`completedRunId`)",
                )
            }
        }

        /**
         * Runs only when the process opens its singleton database, before any new run starts.
         *
         * A salvaged run is a finished run, so it joins the Health Connect
         * outbox here the way [com.myrunningapp.data.repository.RunRepository.finishRun]
         * would have: `finishRun` never ran for these rows, and nothing else
         * would ever queue them, so without this a run interrupted by a process
         * death is silently never published while the settings screen reports
         * everything up to date. `PENDING` regardless of the toggle matches
         * `finishRun` — the engine simply leaves the queue alone while sync is
         * off. Only rows still `NOT_SYNCED` are touched, so this cannot undo a
         * state the engine already reached.
         */
        val RECOVER_INTERRUPTED_RUNS = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                // Coached runs first, while isInProgress still marks which runs were
                // cut short: a long enough one has done its workout (as finish would
                // have recorded), and any other workout still holding a run in
                // progress is let go, since after recovery no run is in progress.
                // Re-running after a death part-way through is harmless.
                db.execSQL(
                    """
                    UPDATE plan_workouts SET status = 'COMPLETED'
                    WHERE status = 'PLANNED' AND completedRunId IN (
                        SELECT id FROM runs
                        WHERE isInProgress = 1 AND distanceMeters >= ${WorkoutCoach.MIN_COMPLETION_METERS}
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "UPDATE plan_workouts SET completedRunId = NULL " +
                        "WHERE status = 'PLANNED' AND completedRunId IS NOT NULL",
                )
                db.execSQL(
                    """
                    UPDATE runs SET
                        isInProgress = 0,
                        wasRecovered = 1,
                        healthSyncState = CASE healthSyncState
                            WHEN 'NOT_SYNCED' THEN 'PENDING' ELSE healthSyncState END,
                        healthSyncVersion = CASE healthSyncState
                            WHEN 'NOT_SYNCED' THEN healthSyncVersion + 1 ELSE healthSyncVersion END
                    WHERE isInProgress = 1
                    """.trimIndent(),
                )
            }
        }

        const val NAME = "myrunningapp.db"
    }
}
