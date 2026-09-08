package com.myrunningapp.data.db

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
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun runDao(): RunDao
    abstract fun runPointDao(): RunPointDao
    abstract fun splitDao(): SplitDao
    abstract fun profileDao(): ProfileDao

    companion object {
        const val NAME = "myrunningapp.db"
    }
}
