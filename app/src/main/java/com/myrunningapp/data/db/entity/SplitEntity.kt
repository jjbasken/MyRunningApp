package com.myrunningapp.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.myrunningapp.domain.model.Split

@Entity(
    tableName = "splits",
    foreignKeys = [
        ForeignKey(
            entity = RunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("runId")],
)
data class SplitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val splitNumber: Int,
    val distanceMeters: Double,
    val durationSec: Long,
    val paceSecPerMile: Double,
) {
    fun toDomain(): Split = Split(
        id = id,
        runId = runId,
        splitNumber = splitNumber,
        distanceMeters = distanceMeters,
        durationSec = durationSec,
        paceSecPerMile = paceSecPerMile,
    )
}
