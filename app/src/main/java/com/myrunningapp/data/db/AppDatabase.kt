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
import com.myrunningapp.data.db.entity.HealthDeletionEntity
import com.myrunningapp.data.db.entity.ProfileEntity
import com.myrunningapp.data.db.entity.RunEntity
import com.myrunningapp.data.db.entity.RunPointEntity
import com.myrunningapp.data.db.entity.SplitEntity

@Database(
    entities = [
        RunEntity::class,
        RunPointEntity::class,
        SplitEntity::class,
        ProfileEntity::class,
        HealthDeletionEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun runDao(): RunDao
    abstract fun runPointDao(): RunPointDao
    abstract fun splitDao(): SplitDao
    abstract fun profileDao(): ProfileDao
    abstract fun healthSyncDao(): HealthSyncDao

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
