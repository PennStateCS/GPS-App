package app.surrealar.ui.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReplaceableScopeTest {

    @Test fun `switching category cancels the old collectors, so they don't pile up`() = runTest {
        val scopes = ReplaceableScope()
        val ticks = MutableSharedFlow<Int>(extraBufferCapacity = 64)
        var received = 0
        repeat(5) {                                   // visit 5 categories, each starting a collector
            scopes.next(backgroundScope.coroutineContext).launch { ticks.collect { received++ } }
            runCurrent()
        }
        ticks.emit(1); runCurrent()
        assertEquals("only the current category's collector is alive", 1, received)
    }

    @Test fun `the category scope dies with its parent (view destroyed)`() = runTest {
        val scopes = ReplaceableScope()
        val parent = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.Job(backgroundScope.coroutineContext[kotlinx.coroutines.Job]))
        val child = scopes.next(parent.coroutineContext).launch { awaitCancellation() }
        runCurrent(); assertTrue(child.isActive)
        parent.coroutineContext[kotlinx.coroutines.Job]!!.cancel(); runCurrent()
        assertFalse(child.isActive)
    }
}