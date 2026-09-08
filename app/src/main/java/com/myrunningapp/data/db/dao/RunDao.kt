package com.myrunningapp.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import com.myrunningapp.data.db.entity.RunPointEntity
import com.myrunningapp.data.db.entity.SplitEntity
import com.myrunningapp.data.db.entity.RunEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RunDao {

    @Query("SELECT * FROM runs WHERE isInProgress = 0 ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs WHERE id = :runId")
    fun observeById(runId: Long): Flow<RunEntity?>

    @Query("SELECT * FROM runs WHERE id = :runId")
    suspend fun getById(runId: Long): RunEntity?

    @Insert
    suspend fun insert(run: RunEntity): Long

    @Update
    suspend fun update(run: RunEntity)

    suspend fun delete(run: RunEntity) = deleteById(run.id)

    @Query("DELETE FROM runs WHERE id = :runId AND isInProgress = 0")
    suspend fun deleteById(runId: Long)
    @Insert
    suspend fun insertCheckpointPoints(points: List<RunPointEntity>)

    @Insert
    suspend fun insertCheckpointSplits(splits: List<SplitEntity>)

    @Query("DELETE FROM splits WHERE runId = :runId")
    suspend fun clearCheckpointSplits(runId: Long)

    /** The route, splits and summary must describe the same instant after a crash. */
    @Transaction
    suspend fun checkpoint(run: RunEntity, points: List<RunPointEntity>, splits: List<SplitEntity>) {
        insertCheckpointPoints(points)
        clearCheckpointSplits(run.id)
        insertCheckpointSplits(splits)
        update(run)
    }
}
