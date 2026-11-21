package app.surrealar.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import app.surrealar.util.DiagnosticsLogger
import kotlinx.coroutines.CoroutineExceptionHandler
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
 *
 * [LogUncaught] catches anything a child coroutine fails to handle and writes it to the diagnostics
 * log instead of crashing the app (a field session must survive one bad message or race).
 */
@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {

    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + LogUncaught)

    /** Logs an uncaught coroutine failure (never a cancellation) instead of crashing. */
    val LogUncaught: CoroutineExceptionHandler = CoroutineExceptionHandler { _, e ->
        DiagnosticsLogger.e("Coroutine", "Uncaught exception in a background coroutine: ${e.javaClass.simpleName}", e)
    }
}
