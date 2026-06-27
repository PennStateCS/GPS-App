package app.surrealar.di

import android.content.Context
import app.surrealar.data.local.dao.CoordinateDao
import app.surrealar.data.local.dao.ModelDao
import app.surrealar.data.local.db.AppDatabase
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
     * Delegates to the [AppDatabase] singleton so every Hilt-injected database access shares the
     * exact same Room connection. Using a separate Room builder here would open a second
     * connection to the same SQLite file and bypass the registered migrations. UI classes should
     * inject [AppDatabase]/the DAOs/repositories rather than calling [AppDatabase.getDatabase].
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
