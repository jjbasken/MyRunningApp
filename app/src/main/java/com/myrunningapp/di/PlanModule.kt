package com.myrunningapp.di

import com.myrunningapp.data.plan.TrainingPlanRepository
import com.myrunningapp.data.plan.WorkoutLog
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PlanModule {

    @Binds
    abstract fun bindWorkoutLog(impl: TrainingPlanRepository): WorkoutLog
}
