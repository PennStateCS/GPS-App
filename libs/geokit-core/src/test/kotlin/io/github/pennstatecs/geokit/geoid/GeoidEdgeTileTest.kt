package io.github.pennstatecs.geokit.geoid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Points exactly on the outer south/west edge of an unbundled tile are read from the bundled neighbour
 * that stores them on its north/east edge (review finding LOW-1).
 */
class GeoidEdgeTileTest {

    private fun GeoidResult.value(): Double = (this as? GeoidResult.Value)?.undulationM ?: error("no value: $this")

    private fun pack(resource: String): GeoidTilePack {
        val bytes = javaClass.getResourceAsStream("/$resource")!!.use { it.readBytes() }
        return GeoidTilePack.fromBytes(bytes, GlobalGeoidModels.EGM2008_BUNDLED_PACKS.getValue(resource))
    }

    /** A source that hides some tiles of [inner]. */
    private class Without(private val inner: GeoidTileSource, private val hidden: Set<GeoidTileKey>) : GeoidTileSource {
        override val spec: GeoidGridSpec get() = inner.spec
        override val tileCount: Int get() = inner.tileCount - hidden.count { inner.contains(it) }
        override fun contains(key: GeoidTileKey): Boolean = key !in hidden && inner.contains(key)
        override fun loadTile(key: GeoidTileKey): IntArray? = if (key in hidden) null else inner.loadTile(key)
    }

    /** N at the node stored in [key] at node row [i], column [j] (millimetres → metres). */
    private fun node(p: GeoidTilePack, key: GeoidTileKey, i: Int, j: Int): Double {
        val s = p.spec.tileSize
        return p.loadTile(key)!![i * s + j] / 1000.0
    }

    @Test fun `CONUS north edge 50 N is the bundled edge node, no longer TILE_NOT_AVAILABLE`() {
        val conus = pack("geoid/egm2008_conus.srgp")
        val spec = conus.spec
        val above = spec.tileOf(50.0, -100.0)
        val below = GeoidTileKey(above.row - 1, above.col)
        assertFalse("precondition: 50-51 N tile not bundled", conus.contains(above))
        assertTrue(conus.contains(below))

        val model = GlobalGeoidModels.egm2008()
        val n = model.undulation(50.0, -100.0).value()
        assertEquals(node(conus, below, spec.tileSize - 1, 0), n, 0.0)
        assertEquals(model.undulation(49.9999999999, -100.0).value(), n, 1e-6)
        assertEquals(-23.72, n, 0.01)
        assertTrue(model.isTileAvailable(50.0, -100.0))
        // Just north of the edge is still unavailable (no substitution, no extrapolation).
        assertEquals(GeoidUnavailableReason.TILE_NOT_AVAILABLE,
            (model.undulation(50.0000001, -100.0) as GeoidResult.Unavailable).reason)
        assertFalse(model.isTileAvailable(50.0000001, -100.0))
    }

    @Test fun `Alaska north edge 72 N and the east edges of CONUS and Alaska`() {
        val model = GlobalGeoidModels.egm2008()
        val conus = pack("geoid/egm2008_conus.srgp")
        val alaska = pack("geoid/egm2008_alaska.srgp")
        val s = conus.spec.tileSize
        data class Case(val lat: Double, val lon: Double, val p: GeoidTilePack, val dr: Int, val dc: Int, val i: Int, val j: Int)
        for (c in listOf(
            Case(72.0, -150.0, alaska, -1, 0, s - 1, 0),     // north edge: from the tile below
            Case(60.0, -129.0, alaska, 0, -1, 0, s - 1),     // east edge: from the tile to the west
            Case(40.0, -66.0, conus, 0, -1, 0, s - 1),
            Case(45.0, -66.0, conus, 0, -1, 0, s - 1),
            Case(50.0, -66.0, conus, -1, -1, s - 1, s - 1),  // north-east corner: diagonal neighbour
        )) {
            val primary = c.p.spec.tileOf(c.lat, c.lon)
            val neighbour = GeoidTileKey(primary.row + c.dr, primary.col + c.dc)
            assertFalse("precondition ${c.lat},${c.lon}", c.p.contains(primary))
            assertTrue("precondition ${c.lat},${c.lon}", c.p.contains(neighbour))
            assertEquals("${c.lat},${c.lon}", node(c.p, neighbour, c.i, c.j), model.undulation(c.lat, c.lon).value(), 0.0)
        }
    }

    @Test fun `edges of a missing tile equal the full model exactly, also across 180`() {
        val alaska = pack("geoid/egm2008_alaska.srgp")
        val spec = alaska.spec
        val full = TiledGeoidModel(GlobalGeoidModels.EGM2008_INFO) { alaska }
        // Hide the tile 61-62 N, 150-149 W: its south (61 N) and west (150 W) edges come from neighbours.
        val gap = spec.tileOf(61.5, -149.5)
        val noGap = TiledGeoidModel(GlobalGeoidModels.EGM2008_INFO) { Without(alaska, setOf(gap)) }
        for ((lat, lon) in listOf(61.0 to -149.5, 61.0 to -149.123, 61.7 to -150.0, 61.0 to -150.0, 61.0 to -149.0)) {
            assertEquals("$lat,$lon", full.undulation(lat, lon).value(), noGap.undulation(lat, lon).value(), 0.0)
        }
        assertEquals(GeoidUnavailableReason.TILE_NOT_AVAILABLE,
            (noGap.undulation(61.5, -149.5) as GeoidResult.Unavailable).reason)

        // Hide the column east of −180 (col 0): points on −180 come from the tile ending at +180.
        val col0 = (0 until spec.tileRows).map { GeoidTileKey(it, 0) }.toSet()
        val noCol0 = TiledGeoidModel(GlobalGeoidModels.EGM2008_INFO) { Without(alaska, col0) }
        for (lat in listOf(51.3, 52.0, 53.97)) {
            val expected = full.undulation(lat, -180.0).value()
            assertEquals("$lat", expected, noCol0.undulation(lat, -180.0).value(), 0.0)
            assertEquals("$lat", expected, noCol0.undulation(lat, 180.0).value(), 0.0)
            assertEquals(GeoidUnavailableReason.TILE_NOT_AVAILABLE,
                (noCol0.undulation(lat, -179.5) as GeoidResult.Unavailable).reason)
        }
    }

    @Test fun `plus and minus 180 unchanged when both sides are bundled`() {
        val model = GlobalGeoidModels.egm2008()
        val alaska = pack("geoid/egm2008_alaska.srgp")
        val spec = alaska.spec
        for (lat in listOf(51.3, 52.0, 53.97)) {
            val key = spec.tileOf(lat, -180.0)
            assertEquals(0, key.col)
            assertTrue(alaska.contains(key))
            val fr = (lat - (-90.0 + key.row)) * spec.nodesPerDegree
            val i = kotlin.math.floor(fr).toInt()
            val t = fr - i
            val expected = node(alaska, key, i, 0) * (1 - t) + node(alaska, key, i + 1, 0) * t
            assertEquals(expected, model.undulation(lat, -180.0).value(), 1e-12)
            assertEquals(model.undulation(lat, -180.0).value(), model.undulation(lat, 180.0).value(), 0.0)
        }
    }
}
