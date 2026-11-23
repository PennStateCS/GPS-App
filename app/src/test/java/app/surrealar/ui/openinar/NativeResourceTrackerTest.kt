package app.surrealar.ui.openinar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeResourceTrackerTest {

    private enum class R { ENGINE, RENDERER, SCENE, VIEW, LOADER, UI_HELPER }

    /**
     * Mirrors ArFilamentRenderer's pattern: init creates resources in order and marks each; any throw
     * runs destroy and rethrows; destroy releases whatever is live in its own fixed order.
     */
    private class FakeRenderer(private val failAt: R? = null, private val failRelease: R? = null) {
        val tracker = NativeResourceTracker<R>()
        val released = mutableListOf<R>()
        val failures = mutableListOf<R>()

        fun init() {
            try {
                for (r in R.values()) {
                    if (r == failAt) throw IllegalStateException("create $r failed")
                    tracker.created(r)
                }
            } catch (t: Throwable) {
                destroy()
                throw t
            }
        }

        fun destroy() {
            if (tracker.isEmpty) return
            for (r in listOf(R.LOADER, R.UI_HELPER, R.RENDERER, R.VIEW, R.SCENE, R.ENGINE)) {
                tracker.release(r, { res, _ -> failures += res }) {
                    released += r
                    if (r == failRelease) throw IllegalStateException("release $r failed")
                }
            }
        }
    }

    @Test
    fun fullInit_destroyReleasesEverythingOnce() {
        val f = FakeRenderer()
        f.init()
        assertEquals(R.values().toList(), f.tracker.liveResources)
        f.destroy()
        assertEquals(listOf(R.LOADER, R.UI_HELPER, R.RENDERER, R.VIEW, R.SCENE, R.ENGINE), f.released)
        assertTrue(f.tracker.isEmpty)
    }

    @Test
    fun partialInit_releasesOnlyWhatWasCreated_andRethrows() {
        for (failAt in R.values()) {
            val f = FakeRenderer(failAt = failAt)
            val thrown = runCatching { f.init() }.exceptionOrNull()
            assertTrue("init must rethrow when $failAt fails", thrown is IllegalStateException)
            val created = R.values().takeWhile { it != failAt }.toSet()
            assertEquals("failAt=$failAt", created, f.released.toSet())
            assertEquals("each released once, failAt=$failAt", created.size, f.released.size)
            assertTrue(f.tracker.isEmpty)
        }
    }

    @Test
    fun destroyIsIdempotent_afterDestroyAndAfterFailedInit() {
        val f = FakeRenderer()
        f.init()
        f.destroy()
        val count = f.released.size
        f.destroy()
        assertEquals(count, f.released.size)

        val failed = FakeRenderer(failAt = R.SCENE)
        runCatching { failed.init() }
        val afterInit = failed.released.size
        failed.destroy()   // the fragment's extra destroy() after a failed init
        assertEquals(afterInit, failed.released.size)
    }

    @Test
    fun destroyBeforeInit_isNoOp() {
        val f = FakeRenderer()
        f.destroy()
        assertTrue(f.released.isEmpty())
    }

    @Test
    fun failingRelease_isReportedOnce_othersStillReleased_neverRetried() {
        val f = FakeRenderer(failRelease = R.RENDERER)
        f.init()
        f.destroy()
        assertEquals(listOf(R.RENDERER), f.failures)
        assertEquals(R.values().size, f.released.size)
        assertTrue(f.tracker.isEmpty)
        f.destroy()
        assertEquals(listOf(R.RENDERER), f.failures)
    }

    @Test
    fun release_ofNonLiveResource_doesNotRunAction() {
        val t = NativeResourceTracker<R>()
        var ran = false
        assertFalse(t.release(R.ENGINE, { _, _ -> }) { ran = true })
        assertFalse(ran)
        t.created(R.ENGINE)
        assertTrue(t.isLive(R.ENGINE))
        assertTrue(t.release(R.ENGINE, { _, _ -> }) { ran = true })
        assertTrue(ran)
        assertFalse(t.isLive(R.ENGINE))
    }

    @Test
    fun errors_areNotSwallowed() {
        val t = NativeResourceTracker<R>()
        t.created(R.ENGINE)
        val thrown = runCatching { t.release(R.ENGINE, { _, _ -> }) { throw OutOfMemoryError("x") } }.exceptionOrNull()
        assertTrue(thrown is OutOfMemoryError)
        assertFalse(t.isLive(R.ENGINE))
    }
}
