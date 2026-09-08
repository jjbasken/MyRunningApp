package com.myrunningapp.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.myrunningapp.data.db.dao.ProfileDao
import com.myrunningapp.data.db.dao.RunDao
import com.myrunningapp.data.db.dao.RunPointDao
import com.myrunningapp.data.db.dao.SplitDao
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
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun runDao(): RunDao
    abstract fun runPointDao(): RunPointDao
    abstract fun splitDao(): SplitDao
    abstract fun profileDao(): ProfileDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE runs ADD COLUMN isInProgress INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE runs ADD COLUMN wasRecovered INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Runs only when the process opens its singleton database, before any new run starts. */
        val RECOVER_INTERRUPTED_RUNS = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE runs SET isInProgress = 0, wasRecovered = 1 WHERE isInProgress = 1")
            }
        }

        const val NAME = "myrunningapp.db"
    }
}
