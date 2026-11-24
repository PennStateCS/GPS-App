package io.github.pennstatecs.geokit.geoid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A failed geoid load is retried after the back-off instead of lasting for the process (review LOW-2). */
class LazyLoadRetryTest {

    private var now = 1_000L
    private var calls = 0
    private var failuresLeft = 1

    private fun loader(): String {
        calls++
        if (failuresLeft-- > 0) throw IllegalStateException("transient")
        return "data"
    }

    @Test fun `failure is remembered during the back-off, then the loader runs again and succeeds`() {
        val lazy = LazyLoad(::loader, retryAfterNanos = 30_000L, nanoClock = { now })
        assertTrue(lazy.get().isFailure)
        assertTrue(lazy.isFailed)
        assertFalse(lazy.isLoaded)
        assertEquals(1, calls)

        now += 29_999L
        assertTrue(lazy.get().isFailure)
        assertEquals("no reload within the back-off", 1, calls)

        now += 1L
        assertEquals("data", lazy.get().getOrThrow())
        assertEquals(2, calls)
        assertTrue(lazy.isLoaded)
        assertFalse(lazy.isFailed)

        now += 1_000_000L
        assertEquals("data", lazy.get().getOrThrow())
        assertEquals("loaded once, never again", 2, calls)
    }

    @Test fun `repeated failures restart the back-off from the latest attempt`() {
        failuresLeft = 2
        val lazy = LazyLoad(::loader, retryAfterNanos = 100L, nanoClock = { now })
        assertTrue(lazy.get().isFailure)
        now += 100L
        assertTrue(lazy.get().isFailure)
        assertEquals(2, calls)
        now += 99L
        assertTrue(lazy.get().isFailure)
        assertEquals(2, calls)
        now += 1L
        assertEquals("data", lazy.get().getOrThrow())
        assertEquals(3, calls)
    }

    @Test fun `default back-off is 30 s`() {
        assertEquals(30_000_000_000L, LazyLoad.DEFAULT_RETRY_AFTER_NANOS)
    }

    @Test fun `a geoid pack whose first load failed opens on the retry`() {
        // Real clock; with a zero back-off every get after a failure retries.
        var fail = true
        val bytes = javaClass.getResourceAsStream("/" + GlobalGeoidModels.EGM96_RESOURCE)!!.use { it.readBytes() }
        val lazy = LazyLoad({ if (fail) throw IllegalStateException("io") else GeoidTilePack.fromBytes(bytes) },
            retryAfterNanos = 0L)
        assertTrue(lazy.get().isFailure)
        fail = false
        assertEquals(GeoidModelId.EGM96, lazy.get().getOrThrow().spec.model)
    }
}
