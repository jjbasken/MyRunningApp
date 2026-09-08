package com.myrunningapp.data.repository

import com.myrunningapp.data.db.dao.ProfileDao
import com.myrunningapp.data.db.entity.ProfileEntity
import com.myrunningapp.domain.model.Profile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single source of truth for the user's [Profile].
 *
 * Emits [Profile.DEFAULT] until the user saves their own, so callers never have
 * to handle a null profile.
 */
@Singleton
class ProfileRepository @Inject constructor(
    private val profileDao: ProfileDao,
) {
    val profile: Flow<Profile> =
        profileDao.observe().map { it?.toDomain() ?: Profile.DEFAULT }

    /** True once the user has saved a profile at least once. */
    val hasSavedProfile: Flow<Boolean> =
        profileDao.observe().map { it != null }

    suspend fun get(): Profile = profileDao.get()?.toDomain() ?: Profile.DEFAULT

    suspend fun save(profile: Profile) {
        profileDao.upsert(ProfileEntity.fromDomain(profile))
    }
}
