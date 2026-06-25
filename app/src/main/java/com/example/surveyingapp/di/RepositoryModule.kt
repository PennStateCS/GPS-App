package com.example.surveyingapp.di

import com.example.surveyingapp.data.repository.impl.CoordinateRepositoryImpl
import com.example.surveyingapp.data.repository.impl.ModelRepositoryImpl
import com.example.surveyingapp.domain.repository.CoordinateRepository
import com.example.surveyingapp.domain.repository.ModelRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds repository interfaces to their implementations.
 *
 * Both impls have an `@Inject` constructor taking only their DAO (provided by [DatabaseModule]),
 * so [Binds] is used instead of [dagger.Provides] — no factory logic is needed. The [Singleton]
 * scope keeps a single instance per interface, matching the previous `@Provides @Singleton` setup.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindCoordinateRepository(impl: CoordinateRepositoryImpl): CoordinateRepository

    @Binds
    @Singleton
    abstract fun bindModelRepository(impl: ModelRepositoryImpl): ModelRepository
}
