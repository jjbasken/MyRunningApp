package com.myrunningapp.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.myrunningapp.data.db.entity.SplitEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SplitDao {

    @Query("SELECT * FROM splits WHERE runId = :runId ORDER BY splitNumber ASC")
    fun observeForRun(runId: Long): Flow<List<SplitEntity>>

    @Query("SELECT * FROM splits WHERE runId = :runId ORDER BY splitNumber ASC")
    suspend fun getForRun(runId: Long): List<SplitEntity>

    @Insert
    suspend fun insert(split: SplitEntity): Long
}
