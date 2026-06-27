package app.surrealar.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * App-wide coroutine scope used by the long-lived GNSS streams and NMEA parsing pipeline.
 *
 * A [SupervisorJob] keeps one failing child stream from cancelling the others, and
 * [Dispatchers.Default] keeps parsing work off the main thread. Kept as [Provides] because
 * the scope is assembled from a job + dispatcher rather than constructor-injected.
 */
@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {

    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
