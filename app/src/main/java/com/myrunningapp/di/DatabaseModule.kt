package com.myrunningapp.di

import android.content.Context
import androidx.room.Room
import com.myrunningapp.data.db.AppDatabase
import com.myrunningapp.data.db.dao.ProfileDao
import com.myrunningapp.data.db.dao.RunDao
import com.myrunningapp.data.db.dao.RunPointDao
import com.myrunningapp.data.db.dao.SplitDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .addCallback(AppDatabase.RECOVER_INTERRUPTED_RUNS)
            .build()

    @Provides
    fun provideRunDao(db: AppDatabase): RunDao = db.runDao()

    @Provides
    fun provideRunPointDao(db: AppDatabase): RunPointDao = db.runPointDao()

    @Provides
    fun provideSplitDao(db: AppDatabase): SplitDao = db.splitDao()

    @Provides
    fun provideProfileDao(db: AppDatabase): ProfileDao = db.profileDao()
}
