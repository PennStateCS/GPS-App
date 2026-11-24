package app.surrealar.coords

import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.VerticalReference
import io.github.pennstatecs.geokit.geoid.GeoidAvailability
import io.github.pennstatecs.geokit.geoid.GeoidModelId
import io.github.pennstatecs.geokit.geoid.GeoidResult
import io.github.pennstatecs.geokit.geoid.GeoidTilePack
import io.github.pennstatecs.geokit.geoid.GeoidUnavailableReason
import io.github.pennstatecs.geokit.geoid.GlobalGeoidModels
import io.github.pennstatecs.geokit.geoid.TiledGeoidModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** EGM lookups never load the packs on the main thread (review finding MEDIUM-1). */
class NonBlockingGeoidModelTest {

    private var loads = 0
    private var failLoad = false
    private var onMain = true
    private val queued = mutableListOf<Runnable>()

    private val egm96Bytes by lazy {
        checkNotNull(javaClass.getResourceAsStream("/" + GlobalGeoidModels.EGM96_RESOURCE)).use { it.readBytes() }
    }
    private val raw = TiledGeoidModel(GlobalGeoidModels.EGM96_INFO) {
        loads++
        if (failLoad) throw IllegalStateException("pack unreadable")
        GeoidTilePack.fromBytes(egm96Bytes, GlobalGeoidModels.EGM96_SHA256)
    }
    private val model = NonBlockingGeoidModel(raw, isMainThread = { onMain }, loadInBackground = { queued += it })

    private fun runQueued() { val q = queued.toList(); queued.clear(); q.forEach { it.run() } }

    @Test fun `main-thread lookup before load returns loading and does not load synchronously`() {
        val r = model.undulation(40.0, -77.0) as GeoidResult.Unavailable
        assertEquals(GeoidUnavailableReason.DATA_LOADING, r.reason)
        assertEquals(GeoidModelId.EGM96, r.model)
        assertEquals(0, loads)
        assertEquals(GeoidAvailability.NOT_LOADED, model.availability)
        assertEquals("one background load queued", 1, queued.size)
        model.undulation(41.0, -77.0)
        assertEquals("no second load while one is queued", 1, queued.size)
        assertEquals("EGM96 data is loading", AppGeoidModels.unavailableText(GeoidModelId.EGM96, r.reason))
    }

    @Test fun `after the background load the main thread gets the model's value`() {
        model.undulation(40.0, -77.0)
        onMain = false
        runQueued()
        onMain = true
        assertEquals(1, loads)
        assertEquals(GeoidAvailability.AVAILABLE, model.availability)
        val v = model.undulation(41.3477, -76.0226) as GeoidResult.Value
        assertEquals((raw.undulation(41.3477, -76.0226) as GeoidResult.Value).undulationM, v.undulationM, 0.0)
        assertEquals(1, loads)
        assertTrue(queued.isEmpty())
    }

    @Test fun `background-thread lookup loads synchronously`() {
        onMain = false
        val v = model.undulation(41.3477, -76.0226)
        assertTrue(v is GeoidResult.Value)
        assertEquals(1, loads)
        assertTrue(queued.isEmpty())
    }

    @Test fun `failed data on the main thread reports LOAD_FAILED without loading there`() {
        failLoad = true
        onMain = false
        assertEquals(GeoidUnavailableReason.LOAD_FAILED, (model.undulation(40.0, -77.0) as GeoidResult.Unavailable).reason)
        assertEquals(1, loads)
        onMain = true
        val r = model.undulation(40.0, -77.0) as GeoidResult.Unavailable
        assertEquals(GeoidUnavailableReason.LOAD_FAILED, r.reason)
        assertEquals("no load on the main thread", 1, loads)
        assertEquals("a background attempt is queued", 1, queued.size)
    }

    @Test fun `converter shows the loading reason in place of the EGM height`() {
        val converter = CoordinateConverter({ _, _ -> null }) { id -> if (id == GeoidModelId.EGM96) model else null }
        val s = CoordinateSystemSettings(vertical = VerticalReference.EGM96, unit = LinearUnit.METERS)
        val src = SourcePosition(40.0, -77.0, 100.0, null, isNad83 = false, epochYear = 2026.5)
        val p = converter.convert(src, s)
        assertNull(p.height)
        assertEquals("EGM96 data is loading", p.heightUnavailableReason)
        assertEquals("EGM96", p.verticalLabel)
        assertEquals(0, loads)

        onMain = false
        runQueued()
        onMain = true
        val loaded = converter.convert(src, s)
        assertEquals(100.0 - (raw.undulation(40.0, -77.0) as GeoidResult.Value).undulationM, loaded.height!!, 0.0)
        assertNull(loaded.heightUnavailableReason)
    }

    @Test fun `warm-up is marked done only after a successful load and retried after a failure`() {
        var result: GeoidAvailability? = GeoidAvailability.FAILED
        var calls = 0
        val tracker = GeoidWarmUpTracker { calls++; result }
        assertEquals(GeoidAvailability.FAILED, tracker.warmUp(GeoidModelId.EGM2008)!!.getOrThrow())
        assertTrue(!tracker.isWarm(GeoidModelId.EGM2008))
        val thrown = GeoidWarmUpTracker { throw IllegalStateException("io") }
        assertTrue(thrown.warmUp(GeoidModelId.EGM96)!!.isFailure)
        assertTrue(!thrown.isWarm(GeoidModelId.EGM96))
        result = GeoidAvailability.PARTIAL
        assertEquals(GeoidAvailability.PARTIAL, tracker.warmUp(GeoidModelId.EGM2008)!!.getOrThrow())
        assertTrue(tracker.isWarm(GeoidModelId.EGM2008))
        assertNull("skipped once warm", tracker.warmUp(GeoidModelId.EGM2008))
        assertEquals(2, calls)
    }
}
