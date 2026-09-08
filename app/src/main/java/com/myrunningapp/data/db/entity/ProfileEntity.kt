package com.myrunningapp.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.myrunningapp.domain.model.Profile
import com.myrunningapp.domain.model.Sex

/** Single-row table: [id] is always [SINGLETON_ID]. */
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val weightKg: Double,
    val heightCm: Double,
    val age: Int,
    val sex: Sex,
) {
    fun toDomain(): Profile = Profile(
        weightKg = weightKg,
        heightCm = heightCm,
        age = age,
        sex = sex,
    )

    companion object {
        const val SINGLETON_ID = 0

        fun fromDomain(profile: Profile): ProfileEntity = ProfileEntity(
            id = SINGLETON_ID,
            weightKg = profile.weightKg,
            heightCm = profile.heightCm,
            age = profile.age,
            sex = profile.sex,
        )
    }
}
