package com.myrunningapp.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.myrunningapp.data.db.entity.RunEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RunDao {

    @Query("SELECT * FROM runs ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs WHERE id = :runId")
    fun observeById(runId: Long): Flow<RunEntity?>

    @Query("SELECT * FROM runs WHERE id = :runId")
    suspend fun getById(runId: Long): RunEntity?

    @Insert
    suspend fun insert(run: RunEntity): Long

    @Update
    suspend fun update(run: RunEntity)

    @Delete
    suspend fun delete(run: RunEntity)

    @Query("DELETE FROM runs WHERE id = :runId")
    suspend fun deleteById(runId: Long)
}
