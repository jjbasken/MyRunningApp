package com.myrunningapp.di

import com.myrunningapp.data.announce.TextToSpeechAnnouncer
import com.myrunningapp.domain.announce.Announcer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * A coroutine scope that lives as long as the process — for singletons that must
 * keep observing something (preferences, say) with no ViewModel or service to
 * hang their lifetime on.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AnnouncementModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AnnouncementBindings {

    @Binds
    abstract fun bindAnnouncer(impl: TextToSpeechAnnouncer): Announcer
}
