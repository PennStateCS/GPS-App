package app.surrealar.di

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** An uncaught failure in the app-wide scope is logged to diagnostics instead of crashing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AppScopeExceptionHandlerTest {

    @Test fun `uncaught exception is logged and the scope keeps working`() = runBlocking {
        ShadowLog.clear()
        val scope = CoroutineModule.provideAppScope()
        val failed = scope.launch { throw IllegalStateException("boom in a collector") }
        failed.join()

        val stillAlive = CompletableDeferred<Int>()
        scope.launch { stillAlive.complete(42) }
        assertEquals("sibling coroutines still run", 42, withTimeout(5_000) { stillAlive.await() })

        // Same synchronous crash path as the global handler (logcat tag GlobalCrash), marked not fatal.
        val logged = ShadowLog.getLogsForTag("GlobalCrash")
        assertTrue("logged: $logged", logged.any { it.type == Log.ERROR && it.throwable is IllegalStateException && it.msg.contains("not fatal") })
        scope.cancel()
    }
}
