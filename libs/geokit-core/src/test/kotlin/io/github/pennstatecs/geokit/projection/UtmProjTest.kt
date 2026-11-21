package io.github.pennstatecs.geokit.projection

import io.github.pennstatecs.geokit.ellipsoid.Ellipsoid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos

/**
 * UTM against PROJ 9.8.1 (pyproj 3.8.0) vectors from libs/geokit-core/tools/utm/gen_utm_vectors.py:
 * 9 zones, both hemispheres, equator, 80°S..84°N, and the zone edges (central meridian ± 3°).
 */
class UtmProjTest {

    private data class V(val zone: Int, val hemi: Char, val lat: Double, val lon: Double, val e: Double, val n: Double)

    private val vectors: List<V> by lazy {
        File("src/test/resources/utm/utm_vectors.csv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("zone") }
            .map { l -> l.split(",").let { V(it[0].toInt(), it[1][0], it[2].toDouble(), it[3].toDouble(), it[4].toDouble(), it[5].toDouble()) } }
    }

    /** Metres between two lat/lon points (local scale; fine for sub-metre differences). */
    private fun groundM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dn = (lat1 - lat2) * Ellipsoid.WGS84.metersPerDegLat(lat1)
        var dLon = lon1 - lon2
        if (dLon > 180) dLon -= 360; if (dLon < -180) dLon += 360
        val de = dLon * Ellipsoid.WGS84.metersPerDegLon(lat1)
        return maxOf(abs(dn), abs(de))
    }

    @Test fun `vector file covers both hemispheres and the zone edges`() {
        assertEquals(1134, vectors.size)
        assertTrue(vectors.any { it.hemi == 'S' } && vectors.any { it.hemi == 'N' })
    }

    @Test fun `forward matches PROJ to under 1 mm`() {
        var worst = 0.0; var at = ""
        for (v in vectors) {
            val u = Utm.forward(v.lat, v.lon, v.zone)
            val err = maxOf(abs(u.eastingM - v.e), abs(u.northingM - v.n))
            if (err > worst) { worst = err; at = "$v" }
        }
        println("UTM forward vs PROJ: worst ${"%.3e".format(worst)} m at $at")
        assertTrue("worst $worst m at $at", worst < 0.001)
    }

    @Test fun `inverse of PROJ grid coordinates returns the PROJ lat-lon to under 1 mm`() {
        var worst = 0.0; var at = ""
        for (v in vectors.filter { abs(it.lat) < 84.0 }) {
            val (lat, lon) = Utm.inverse(v.e, v.n, v.zone, v.hemi)
            val err = groundM(lat, lon, v.lat, v.lon)
            if (err > worst) { worst = err; at = "$v" }
        }
        println("UTM inverse vs PROJ: worst ${"%.3e".format(worst)} m at $at")
        assertTrue("worst $worst m at $at", worst < 0.001)
    }

    @Test fun `round trip is under 1 mm`() {
        var worst = 0.0
        for (v in vectors.filter { abs(it.lat) < 84.0 }) {
            val u = Utm.forward(v.lat, v.lon, v.zone)
            val (lat, lon) = Utm.inverse(u.eastingM, u.northingM, u.zone, u.hemisphere)
            worst = maxOf(worst, groundM(lat, lon, v.lat, v.lon))
        }
        println("UTM round trip: worst ${"%.3e".format(worst)} m")
        assertTrue("worst $worst m", worst < 0.001)
    }

    @Test fun `zone and band selection`() {
        assertEquals(18, Utm.forward(41.3477, -76.0226).zone)
        assertEquals("18T", Utm.forward(41.3477, -76.0226).zoneLabel)
        assertEquals('S', Utm.forward(-33.8688, 151.2093).hemisphere)
        assertEquals(1, Utm.zoneFor(-180.0)); assertEquals(60, Utm.zoneFor(180.0))
    }
}
