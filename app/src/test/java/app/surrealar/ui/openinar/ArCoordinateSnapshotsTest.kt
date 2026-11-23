package app.surrealar.ui.openinar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArCoordinateSnapshotsTest {

    /** Stand-in for a point: anchor inputs (id, position) plus metadata the anchors must not depend on. */
    private data class Pt(
        val id: String, val lat: Double, val lng: Double, val alt: Double?,
        val name: String = "P", val scale: Float = 1f, val updatedAt: Long = 0L, val modelPath: String? = null,
    )

    private fun store() = ArCoordinateSnapshots<Pt> { listOf(it.id, it.lat, it.lng, it.alt, it.modelPath) }

    /** GL-thread build: snapshot, then (optionally, mid-build) something else, then markBuilt. */
    private fun ArCoordinateSnapshots<Pt>.build(midBuild: () -> Unit = {}): Boolean {
        val snap = current
        midBuild()
        return markBuilt(snap)
    }

    @Test fun `placement or metadata edit publishes the new snapshot without an anchor rebuild`() {
        val s = store()
        assertTrue(s.publish(listOf(Pt("a", 41.0, -75.0, 200.0))))
        assertTrue(s.build())
        s.takeUpdate()
        val anchorRev = s.current.anchorRevision

        val edited = Pt("a", 41.0, -75.0, 200.0, name = "Renamed", scale = 2f, updatedAt = 99L)
        assertFalse(s.publish(listOf(edited)))                  // no anchor change
        assertSame(edited, s.current.items.single())            // but the snapshot is the new one
        assertEquals(anchorRev, s.current.anchorRevision)
        assertFalse(s.anchorsOutdated())
        assertSame(edited, s.takeUpdate()!!.items.single())     // live anchors get the metadata once
        assertNull(s.takeUpdate())
    }

    @Test fun `identical repeat emissions change nothing for the anchors`() {
        val s = store()
        val list = listOf(Pt("a", 41.0, -75.0, 200.0))
        s.publish(list); s.build()
        assertFalse(s.publish(list.map { it.copy() }))
        assertFalse(s.anchorsOutdated())
    }

    @Test fun `a position, height or model change rebuilds anchors`() {
        val s = store()
        s.publish(listOf(Pt("a", 41.0, -75.0, 200.0))); s.build()
        assertTrue(s.publish(listOf(Pt("a", 41.0, -75.0, 201.0))))
        assertTrue(s.anchorsOutdated())
        s.build()
        assertTrue(s.publish(listOf(Pt("a", 41.0, -75.0, 201.0, modelPath = "/m/new.glb"))))   // relink, same point
        assertTrue(s.anchorsOutdated())
    }

    @Test fun `rapid coordinate updates during an anchor build keep the newest`() {
        val s = store()
        s.publish(listOf(Pt("a", 41.0, -75.0, 200.0)))
        val newest = listOf(Pt("a", 41.0001, -75.0, 200.0), Pt("b", 41.0002, -75.0, 200.0))
        val done = s.build {
            s.publish(listOf(Pt("a", 41.00005, -75.0, 200.0)))
            s.publish(newest)
        }
        assertFalse(done)                       // the build used an older list
        assertTrue(s.anchorsOutdated())         // so a rebuild stays pending
        assertSame(newest, s.current.items)
        assertTrue(s.build())                   // the next build takes the newest list
        assertFalse(s.anchorsOutdated())
    }

    @Test fun `a metadata-only publish during a build does not force another rebuild`() {
        val s = store()
        s.publish(listOf(Pt("a", 41.0, -75.0, 200.0)))
        val done = s.build { s.publish(listOf(Pt("a", 41.0, -75.0, 200.0, name = "New name"))) }
        assertTrue(done)
        assertFalse(s.anchorsOutdated())
        assertEquals("New name", s.takeUpdate()!!.items.single().name)
    }

    @Test fun `deleting a point is an anchor change and the snapshot no longer has it`() {
        val s = store()
        s.publish(listOf(Pt("a", 41.0, -75.0, 200.0), Pt("b", 41.1, -75.0, 200.0))); s.build()
        assertTrue(s.publish(listOf(Pt("b", 41.1, -75.0, 200.0))))
        assertEquals(listOf("b"), s.takeUpdate()!!.items.map { it.id })
        assertTrue(s.anchorsOutdated())
    }
}
