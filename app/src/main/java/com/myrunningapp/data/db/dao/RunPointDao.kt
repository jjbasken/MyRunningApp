package com.myrunningapp.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.myrunningapp.data.db.entity.RunPointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RunPointDao {

    @Query("SELECT * FROM run_points WHERE runId = :runId ORDER BY timestamp ASC")
    fun observeForRun(runId: Long): Flow<List<RunPointEntity>>

    @Query("SELECT * FROM run_points WHERE runId = :runId ORDER BY timestamp ASC")
    suspend fun getForRun(runId: Long): List<RunPointEntity>

    @Insert
    suspend fun insert(point: RunPointEntity): Long

    @Insert
    suspend fun insertAll(points: List<RunPointEntity>)

    @Query("SELECT COUNT(*) FROM run_points WHERE runId = :runId")
    suspend fun countForRun(runId: Long): Int
}
