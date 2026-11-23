package app.surrealar.ui.openinar

import app.surrealar.ui.openinar.ModelLoadTracker.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLoadTrackerTest {

    private fun ModelLoadTracker.start(key: String, path: String): ModelLoadTracker.Ticket {
        val r = request(key, path)
        assertTrue("expected a load to start for $key", r is Request.Start)
        return (r as Request.Start).ticket
    }

    @Test fun `a normal load publishes and frees the key`() {
        val t = ModelLoadTracker()
        val a = t.start("p1", "/m/a.glb")
        assertTrue(t.isLoading("p1"))
        assertTrue(t.isCurrent(a))
        assertNull(t.ended(a))
        assertFalse(t.isLoading("p1"))
    }

    @Test fun `load then delete discards the finished load`() {
        val t = ModelLoadTracker()
        val a = t.start("p1", "/m/a.glb")
        t.forget("p1")                       // point deleted while the file was being read
        assertFalse(t.isCurrent(a))          // → never published
        assertNull(t.ended(a))               // nothing to reload
        assertFalse(t.isLoading("p1"))
    }

    @Test fun `retain drops keys whose first load is still in flight`() {
        val t = ModelLoadTracker()
        val a = t.start("p1", "/m/a.glb")
        val b = t.start("p2", "/m/b.glb")
        t.retain(setOf("p2"))                // p1 unlinked
        assertFalse(t.isCurrent(a))
        assertTrue(t.isCurrent(b))
    }

    @Test fun `load, relink, then the old load completes - old file never wins`() {
        val t = ModelLoadTracker()
        val old = t.start("p1", "/m/old.glb")
        assertSame(Request.Deferred, t.request("p1", "/m/new.glb"))   // one load per key at a time
        assertFalse(t.isCurrent(old))
        val next = t.ended(old)                                       // the newer request is re-issued
        assertEquals("/m/new.glb", next!!.filePath)
        val fresh = t.start("p1", "/m/new.glb")
        assertTrue(t.isCurrent(fresh))
        assertTrue(fresh.generation > old.generation)
    }

    @Test fun `delete then re-add of the same file while loading still reloads once`() {
        val t = ModelLoadTracker()
        val a = t.start("p1", "/m/a.glb")
        t.forget("p1")
        assertSame(Request.Deferred, t.request("p1", "/m/a.glb"))
        assertFalse(t.isCurrent(a))                       // the old generation is stale
        assertEquals("/m/a.glb", t.ended(a)!!.filePath)   // the new request runs next
        assertTrue(t.isCurrent(t.start("p1", "/m/a.glb")))
    }

    @Test fun `cancelled load does not block a later request`() {
        val t = ModelLoadTracker()
        val a = t.start("p1", "/m/a.glb")
        t.ended(a)                                        // scope cancelled
        val again = t.start("p1", "/m/a.glb")
        assertTrue(t.isCurrent(again))
    }

    @Test fun `failed load does not block a retry`() {
        val t = ModelLoadTracker()
        val a = t.start("p1", "/m/broken.glb")
        assertNull(t.ended(a))                            // parse failure: no automatic retry loop
        assertFalse(t.isLoading("p1"))
        val retry = t.start("p1", "/m/broken.glb")        // e.g. file replaced, next emission
        assertTrue(t.isCurrent(retry))
    }

    @Test fun `duplicate requests for the same file do not invalidate the running load`() {
        val t = ModelLoadTracker()
        val a = t.start("p1", "/m/a.glb")
        assertSame(Request.Deferred, t.request("p1", "/m/a.glb"))
        assertSame(Request.Deferred, t.request("p1", "/m/a.glb"))
        assertTrue(t.isCurrent(a))                        // still publishes
        assertNull(t.ended(a))                            // and nothing newer is waiting
    }

    @Test fun `same file shared by two keys loads independently`() {
        val t = ModelLoadTracker()
        val a = t.start("p1", "/m/shared.glb")
        val b = t.start("p2", "/m/shared.glb")
        t.forget("p1")
        assertFalse(t.isCurrent(a))
        assertTrue(t.isCurrent(b))
        t.ended(a)
        assertTrue(t.isLoading("p2"))
        assertNull(t.ended(b))
    }

    @Test fun `model file change without a coordinate change starts a new generation`() {
        val t = ModelLoadTracker()
        val a = t.start("p1", "/m/v1.glb")
        t.ended(a)
        val b = t.start("p1", "/m/v2.glb")                // relinked after the first load finished
        assertTrue(t.isCurrent(b))
        assertFalse(t.isCurrent(a))
        assertTrue(b.generation > a.generation)
    }
}
