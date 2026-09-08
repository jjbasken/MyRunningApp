package com.myrunningapp.di

import android.content.Context
import android.os.SystemClock
import com.myrunningapp.domain.tracking.MonotonicClock
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.myrunningapp.data.location.FusedLocationClient
import com.myrunningapp.data.location.LocationClient
import com.myrunningapp.data.location.RunRecorder
import com.myrunningapp.data.repository.RunRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LocationModule {

    @Provides
    @Singleton
    fun provideFusedLocationProviderClient(
        @ApplicationContext context: Context,
    ): FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    /**
     * Injected rather than read from `Instant.now()` so the tracking pipeline can
     * be driven by a test clock.
     */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    @Provides
    @Singleton
    fun provideMonotonicClock(): MonotonicClock = MonotonicClock { SystemClock.elapsedRealtime() }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LocationBindings {

    @Binds
    abstract fun bindLocationClient(impl: FusedLocationClient): LocationClient

    @Binds
    abstract fun bindRunRecorder(impl: RunRepository): RunRecorder
}
