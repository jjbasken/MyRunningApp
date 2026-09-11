package com.myrunningapp.di

import com.myrunningapp.data.db.dao.HealthSyncDao
import com.myrunningapp.data.db.dao.RunPointDao
import com.myrunningapp.data.db.dao.SplitDao
import com.myrunningapp.data.health.HealthConnectGateway
import com.myrunningapp.data.health.HealthConnectGatewayImpl
import com.myrunningapp.data.health.HealthSyncEngine
import com.myrunningapp.data.prefs.PreferencesRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class HealthModule {

    @Binds
    @Singleton
    abstract fun bindHealthConnectGateway(impl: HealthConnectGatewayImpl): HealthConnectGateway

    companion object {

        @Provides
        @Singleton
        fun provideHealthSyncEngine(
            healthSyncDao: HealthSyncDao,
            runPointDao: RunPointDao,
            splitDao: SplitDao,
            gateway: HealthConnectGateway,
            preferences: PreferencesRepository,
        ): HealthSyncEngine = HealthSyncEngine(
            healthSyncDao = healthSyncDao,
            runPointDao = runPointDao,
            splitDao = splitDao,
            gateway = gateway,
            // Read fresh on every drain: the user may switch the feature off
            // while a backfill is in flight.
            syncEnabled = { preferences.preferences.map { it.healthSyncEnabled }.first() },
        )
    }
}
