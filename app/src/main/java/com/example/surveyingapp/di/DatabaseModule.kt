package com.example.surveyingapp.di

import android.content.Context
import com.example.surveyingapp.data.local.dao.CoordinateDao
import com.example.surveyingapp.data.local.dao.ModelDao
import com.example.surveyingapp.data.local.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Room database and DAO providers.
 *
 * These stay as [Provides] (not [dagger.Binds]) because the database needs the Android
 * [Context] and the DAOs are obtained via factory calls on the database instance.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Delegates to the [AppDatabase] static singleton so the Hilt-injected instance and
     * any direct [AppDatabase.getDatabase] calls in legacy Fragment code share the exact
     * same Room connection. Using a separate builder here would open a second connection
     * to the same SQLite file and bypass the registered migrations.
     */
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.getDatabase(context)

    @Provides
    fun provideCoordinateDao(db: AppDatabase): CoordinateDao = db.coordinateDao()

    @Provides
    fun provideModelDao(db: AppDatabase): ModelDao = db.modelDao()
}
