package io.github.pennstatecs.geokit.geoid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The GEOID18 [GeoidModel] adapter returns exactly [Geoid18Grid]'s numbers. */
class Geoid18ModelTest {

    private val grid: Geoid18Grid by lazy { Geoid18Grid.fromClasspath() }

    @Test fun `identical to direct Geoid18Grid calls (delta 0) at many points`() {
        val model = Geoid18Model(grid)
        val rnd = Random(18)
        val points = ArrayList<Pair<Double, Double>>()
        repeat(4000) { points += rnd.nextDouble(23.5, 58.5) to rnd.nextDouble(-130.5, -59.5) }
        repeat(500) { points += rnd.nextDouble(14.5, 21.5) to rnd.nextDouble(-69.5, -63.5) }
        // The NGS-checked grid vectors, plus exact grid nodes and region edges.
        javaClass.getResourceAsStream("/geoid/geoid18_vectors.csv")!!.bufferedReader().use { it.readLines() }
            .drop(1).filter { it.isNotBlank() }.forEach { val p = it.split(","); points += p[0].toDouble() to p[1].toDouble() }
        for (lat in listOf(24.0, 58.0, 40.0, 15.0, 21.0)) for (lon in listOf(-130.0, -60.0, -77.0, -69.0, -64.0)) points += lat to lon
        var values = 0
        for ((lat, lon) in points) {
            val direct = grid.undulation(lat, lon)
            val r = model.undulation(lat, lon)
            if (direct == null) {
                assertTrue("$lat,$lon", r is GeoidResult.Unavailable && r.reason == GeoidUnavailableReason.OUTSIDE_COVERAGE)
            } else {
                r as GeoidResult.Value
                assertEquals("$lat,$lon", direct, r.undulationM, 0.0)
                assertEquals(GeoidModelId.GEOID18, r.model)
                assertEquals(Geoid18Grid.verticalDatumName(lat, lon), r.verticalDatum)
                values++
            }
        }
        assertTrue(values > 4000)
    }

    @Test fun `classpath loader is lazy and reports availability`() {
        val model = GlobalGeoidModels.geoid18()
        assertEquals(GeoidAvailability.NOT_LOADED, model.availability)
        val r = model.undulation(40.7934, -77.8600) as GeoidResult.Value
        assertEquals(grid.undulation(40.7934, -77.8600)!!, r.undulationM, 0.0)
        assertEquals(GeoidAvailability.AVAILABLE, model.availability)
        assertEquals("NAVD88", r.verticalDatum)
    }

    @Test fun `outside coverage, invalid input and load failure are typed`() {
        val model = Geoid18Model(grid)
        assertEquals(GeoidUnavailableReason.OUTSIDE_COVERAGE, (model.undulation(61.2, -149.9) as GeoidResult.Unavailable).reason)
        assertEquals(GeoidUnavailableReason.OUTSIDE_COVERAGE, (model.undulation(51.5, -0.1) as GeoidResult.Unavailable).reason)
        assertEquals(GeoidUnavailableReason.INVALID_POSITION, (model.undulation(Double.NaN, -77.0) as GeoidResult.Unavailable).reason)
        assertEquals(GeoidUnavailableReason.INVALID_POSITION, (model.undulation(91.0, -77.0) as GeoidResult.Unavailable).reason)
        assertFalse(model.covers(61.2, -149.9))
        assertTrue(model.covers(40.0, -77.0))
        assertTrue(model.covers(18.4, -66.1))

        val broken = Geoid18Model { throw IllegalStateException("missing") }
        val r = broken.undulation(40.0, -77.0) as GeoidResult.Unavailable
        assertEquals(GeoidUnavailableReason.LOAD_FAILED, r.reason)
        assertEquals(GeoidAvailability.FAILED, broken.availability)
    }

    @Test fun `covers agrees with the grid away from the exact boundary`() {
        val model = Geoid18Model(grid)
        val rnd = Random(7)
        repeat(3000) {
            val lat = rnd.nextDouble(10.0, 62.0); val lon = rnd.nextDouble(-135.0, -55.0)
            assertEquals("$lat,$lon", grid.undulation(lat, lon) != null, model.covers(lat, lon))
        }
    }

    @Test fun `metadata and labels`() {
        val info = Geoid18Model(grid).info
        assertEquals(GeoidModelId.GEOID18, info.id)
        assertEquals("GEOID18", info.id.token)
        assertEquals("GEOID18", info.label)
        assertEquals("NAD83(2011)", info.horizontalFrame)
        assertEquals(1.0, info.resolutionArcMinutes, 0.0)
        assertFalse(info.isFallback)
        assertEquals(Geoid18Model.RESOURCE_SHA256, info.provenance.bundledSha256[Geoid18Grid.RESOURCE_PATH])
        val bytes = javaClass.getResourceAsStream("/" + Geoid18Grid.RESOURCE_PATH)!!.use { it.readBytes() }
        assertEquals(Geoid18Model.RESOURCE_SHA256, GeoidTilePack.sha256Hex(bytes))
        val m = Geoid18Model(grid)
        assertEquals("NAVD88", m.verticalDatumName(40.0, -77.0))
        assertEquals("PRVD02", m.verticalDatumName(18.4, -66.1))
        assertEquals("VIVD09", m.verticalDatumName(18.34, -64.93))
    }
}
