package io.github.pennstatecs.geokit.geoid

import io.github.pennstatecs.geokit.status.ProductStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * GEOID2022 (NAPGD2022 beta_v0a) against
 * - NGS's own published test values (NAPGD2022TestCases.beta_v0a.csv, SGEOID2022 to 0.1 mm, DGEOID2022 to
 *   0.01 mm/yr; https://beta.ngs.noaa.gov/NAPGD2022/files/NAPGD2022TestCases.beta_v0a.csv), and
 * - an independent Python interpolation of the raw official .bin grids at 256 points
 *   (libs/geokit-core/tools/geoid/geoid2022_reference_vectors.py, which itself first reproduces every NGS
 *   test value to within NGS's rounding).
 *
 * Tolerances: packs store integer mm (≤ 0.5 mm per node); the bicubic weights' absolute sum is ≤ 1.5625, so
 * storage moves a value by ≤ 0.79 mm. Against NGS that plus NGS's own 0.05 mm rounding: 0.85 mm. Against the
 * same interpolation over the mm-rounded nodes (what the packs hold): 1 µm (pure implementation check).
 */
class Geoid2022ModelTest {

    private data class Ngs(val name: String, val lat: Double, val lon: Double, val n: Double, val ndotMmPerYr: Double)

    private data class Vec(val kind: String, val region: String, val lat: Double, val lon: Double, val nRaw: Double,
                           val nMm: Double, val ndotRaw: Double?, val ndotUm: Double?)

    private val ngs: List<Ngs> by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream("/geoid/NAPGD2022TestCases.beta_v0a.csv")).use {
            String(it.readBytes(), Charsets.ISO_8859_1)
        }
        val rows = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(rows[0].startsWith("Name,Category,Latitude_decdeg,Longitude_decdeg,Ellipsoid_ht_m,SGEOID2022_m,DGEOID2022_mmperyear"))
        rows.drop(1).map { val p = it.split(","); Ngs(p[0], p[2].toDouble(), p[3].toDouble(), p[5].toDouble(), p[6].toDouble()) }
    }

    private val vectors: List<Vec> by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream("/geoid/geoid2022_vectors.csv")).bufferedReader().use { it.readText() }
        text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.drop(1).map {
            val p = it.split(",")
            Vec(p[0], p[1], p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble(), p[6].toDoubleOrNull(), p[7].toDoubleOrNull())
        }
    }

    private fun resourceFile(name: String): File = File(javaClass.getResource("/geoid/$name")!!.toURI())

    private fun testPack(region: String): GeoidTilePack =
        GeoidTilePack.fromFile(resourceFile("geoid2022_testtiles_$region.srgp"), TEST_PACK_SHA256.getValue(region))

    private val withTestPacks: Geoid2022Model by lazy { Geoid2022Model.bundled(extraPacks = listOf("NA", "AS", "GC").map(::testPack)) }

    private fun GeoidResult.value(): Double = (this as? GeoidResult.Value)?.undulationM ?: error("no value: $this")

    private fun GeoidResult.reason(): GeoidUnavailableReason = (this as? GeoidResult.Unavailable)?.reason ?: error("value: $this")

    @Test fun `matches NGS published test values (SGEOID2022 and DGEOID2022)`() {
        val model = Geoid2022Model.bundled()
        var checked = 0
        val distinct = HashSet<Pair<Double, Double>>()
        var maxN = 0.0
        var maxRate = 0.0
        for (p in ngs) {
            val r = model.undulation(p.lat, p.lon)
            assertEquals(GeoidModelId.GEOID2022, r.model)
            if (r is GeoidResult.Unavailable) {
                assertTrue("${p.name}: ${r.reason}", r.reason == GeoidUnavailableReason.TILE_NOT_AVAILABLE)
                assertTrue(p.name, model.covers(p.lat, p.lon))
                continue
            }
            val n = r.value()
            maxN = maxOf(maxN, abs(n - p.n))
            assertEquals("SGEOID2022 at ${p.name}", p.n, n, NGS_TOLERANCE_M)
            assertEquals("NAPGD2022", (r as GeoidResult.Value).verticalDatum)
            val v = model.velocity(p.lat, p.lon) as Geoid2022VelocityResult.Value
            maxRate = maxOf(maxRate, abs(v.metresPerYear * 1000.0 - p.ndotMmPerYr))
            assertEquals("DGEOID2022 at ${p.name}", p.ndotMmPerYr, v.metresPerYear * 1000.0, NGS_RATE_TOLERANCE_MM)
            checked++
            distinct += p.lat to p.lon
        }
        println("GEOID2022 vs NGS test cases: $checked of ${ngs.size} rows (${distinct.size} distinct points) in bundled packs, " +
            "max |dN| ${"%.4f".format(maxN * 1000)} mm, max |dNdot| ${"%.4f".format(maxRate)} mm/yr")
        assertTrue("only ${distinct.size} distinct NGS points in the bundled packs", distinct.size >= 50)
    }

    @Test fun `matches the independent interpolation of the raw NGS grids`() {
        assertTrue(vectors.size >= 200)
        val model = withTestPacks
        var maxImpl = 0.0
        var maxRaw = 0.0
        var rates = 0
        for (v in vectors) {
            val r = model.undulation(v.lat, v.lon)
            val n = r.value()
            maxImpl = maxOf(maxImpl, abs(n - v.nMm))
            maxRaw = maxOf(maxRaw, abs(n - v.nRaw))
            assertEquals("${v.kind} ${v.lat},${v.lon} (mm nodes)", v.nMm, n, 1e-6)
            assertEquals("${v.kind} ${v.lat},${v.lon} (raw grid)", v.nRaw, n, STORAGE_TOLERANCE_M)
            if (v.ndotUm != null) {
                val rate = (model.velocity(v.lat, v.lon) as Geoid2022VelocityResult.Value).metresPerYear
                assertEquals("rate ${v.lat},${v.lon}", v.ndotUm, rate, 1e-12)
                assertEquals("rate ${v.lat},${v.lon} (raw)", v.ndotRaw!!, rate, 0.8e-6)
                rates++
            }
        }
        println("GEOID2022 vs independent interpolation: ${vectors.size} points, max ${"%.6f".format(maxImpl * 1000)} mm (mm nodes), " +
            "${"%.4f".format(maxRaw * 1000)} mm (raw grid); $rates rates")
        assertTrue(rates >= 200)
        // Every kind of edge case is represented.
        for (k in listOf("random", "node", "corner", "edge", "cross", "boundary", "antimeridian", "gridedge")) {
            assertTrue(k, vectors.any { it.kind.startsWith("$k:") })
        }
    }

    @Test fun `window reaching a missing tile is TILE_NOT_AVAILABLE, the pack boundary itself is not`() {
        val model = Geoid2022Model.bundled()
        // Exactly on the outer boundary: only the boundary row / column carries weight.
        for ((lat, lon) in listOf(50.0 to -100.0, 24.0 to -80.0, 37.5 to -125.0, 50.0 to -66.0)) {
            assertTrue("$lat,$lon", model.undulation(lat, lon) is GeoidResult.Value)
        }
        // Within 1' inside the boundary: the 4x4 window needs the neighbouring (missing) tile.
        for ((lat, lon) in listOf(49.995 to -100.0, 24.005 to -80.0, 37.5 to -124.99, 37.5 to -66.005, 49.99 to -66.01)) {
            val r = model.undulation(lat, lon)
            assertEquals("$lat,$lon", GeoidUnavailableReason.TILE_NOT_AVAILABLE, r.reason())
            assertEquals(GeoidModelId.GEOID2022, r.model)
        }
        // Covered by the NA grid but not bundled (Mexico City, Midway, Nuuk, Manitoba).
        for ((lat, lon) in listOf(19.433333 to -99.133333, 28.2 to -177.37, 64.18 to -51.75, 55.0 to -100.0)) {
            assertTrue(model.covers(lat, lon))
            assertEquals("$lat,$lon", GeoidUnavailableReason.TILE_NOT_AVAILABLE, model.undulation(lat, lon).reason())
        }
        // DGEOID tiles are bundled only around the SGEOID packs.
        assertEquals(GeoidUnavailableReason.TILE_NOT_AVAILABLE,
            (model.velocity(-40.0, 20.0) as Geoid2022VelocityResult.Unavailable).reason)
    }

    @Test fun `coverage and invalid positions`() {
        val model = Geoid2022Model.bundled()
        for ((lat, lon) in listOf(51.5 to 0.0, -33.9 to 151.2, 10.0 to 100.0, -30.0 to -60.0, 4.0 to 140.0, -23.0 to 180.0)) {
            assertFalse("$lat,$lon", model.covers(lat, lon))
            val r = model.undulation(lat, lon)
            assertEquals("$lat,$lon", GeoidUnavailableReason.OUTSIDE_COVERAGE, r.reason())
            assertEquals(GeoidModelId.GEOID2022, r.model)
        }
        // Official grid extents, incl. their corners (lon given either way round).
        for ((lat, lon) in listOf(0.0 to 170.0, 90.0 to -10.0, 0.0 to 350.0, 22.0 to 130.0, 5.0 to 170.0, -22.0 to 172.0, 0.0 to -167.0)) {
            assertTrue("$lat,$lon", model.covers(lat, lon))
        }
        assertFalse(model.covers(-0.001, -166.99))
        for ((lat, lon) in listOf(Double.NaN to 0.0, 91.0 to -100.0, 40.0 to Double.POSITIVE_INFINITY)) {
            assertEquals(GeoidUnavailableReason.INVALID_POSITION, model.undulation(lat, lon).reason())
            assertFalse(model.covers(lat, lon))
            assertEquals(GeoidUnavailableReason.INVALID_POSITION,
                (model.velocity(lat, lon) as Geoid2022VelocityResult.Unavailable).reason)
        }
    }

    @Test fun `regions are chosen geometrically and never substituted`() {
        val na = vectors.single { it.kind == "gridedge:NA" && it.lat == 10.5 && it.lon == 170.0 }
        assertEquals("NA", na.region)
        // On the NA/GC shared edge NA is used: with only the GC tile installed, no value (not GC's).
        val gcOnly = Geoid2022Model { listOf(testPack("GC")) }
        assertEquals(GeoidUnavailableReason.TILE_NOT_AVAILABLE, gcOnly.undulation(10.5, 170.0).reason())
        assertEquals(vectors.single { it.kind == "gridedge:GC" && it.lon == 169.997 }.nMm, gcOnly.undulation(10.5, 169.997).value(), 1e-6)
        assertEquals(na.nMm, withTestPacks.undulation(10.5, 170.0).value(), 1e-6)
        // No pack of a region at all.
        val noAs = Geoid2022Model { listOf(testPack("NA")) }
        assertEquals(GeoidUnavailableReason.TILE_NOT_AVAILABLE, noAs.undulation(-14.27, -170.69).reason())
        assertEquals(GeoidUnavailableReason.TILE_NOT_AVAILABLE,
            (noAs.velocity(40.0, -77.0) as Geoid2022VelocityResult.Unavailable).reason)
        // Never a GEOID18 value (different datum and frame: they differ by decimetres in CONUS).
        val g18 = GlobalGeoidModels.geoid18().undulation(40.0, -77.0).value()
        val g22 = Geoid2022Model.bundled().undulation(40.0, -77.0)
        assertEquals(GeoidModelId.GEOID2022, g22.model)
        println("40 N 77 W: GEOID18 $g18 m, GEOID2022 ${g22.value()} m")
        assertTrue(abs(g22.value() - g18) > 0.1)
    }

    @Test fun `epoch-dependent undulation uses the NGS formula`() {
        assertEquals(58849, Geoid2022Model.modifiedJulianDay(2020, 1, 1))
        assertEquals(51544, Geoid2022Model.modifiedJulianDay(2000, 1, 1))
        assertEquals(60676, Geoid2022Model.modifiedJulianDay(2025, 1, 1))
        assertEquals(59000, Geoid2022Model.modifiedJulianDay(2020, 5, 31))
        try { Geoid2022Model.modifiedJulianDay(2020, 13, 1); fail("month 13 accepted") } catch (expected: IllegalArgumentException) { }
        val model = withTestPacks
        val anchorage = vectors.filter { it.ndotUm != null && it.region == "NA" && it.lat > 55 }.first()
        val static = model.undulation(anchorage.lat, anchorage.lon).value()
        assertEquals(static, model.undulationAt(anchorage.lat, anchorage.lon, 2020, 1, 1).value(), 0.0)
        val dt = (Geoid2022Model.modifiedJulianDay(2026, 11, 24) - 58849) / 365.25
        assertEquals(anchorage.nMm + anchorage.ndotUm!! * dt, model.undulationAt(anchorage.lat, anchorage.lon, 2026, 11, 24).value(), 1e-6)
        // A missing rate makes the epoch value unavailable (never the static value instead).
        val noRate = Geoid2022Model { listOf(testPack("NA")) }
        val r = noRate.undulationAt(10.5, 170.5, 2026, 1, 1)
        assertEquals(GeoidUnavailableReason.TILE_NOT_AVAILABLE, r.reason())
        assertTrue(noRate.undulation(10.5, 170.5) is GeoidResult.Value)
    }

    @Test fun `loading, failures and caching`() {
        val model = Geoid2022Model.bundled(cacheLimits = GeoidTileCacheLimits(maxTiles = 8))
        assertEquals(GeoidAvailability.NOT_LOADED, model.availability)
        assertEquals(0, model.cacheStats().tiles)
        model.undulation(40.4406, -79.9959).value()
        assertEquals(GeoidAvailability.PARTIAL, model.availability)
        val s1 = model.cacheStats()
        assertTrue(s1.tiles in 1..8)
        model.undulation(40.4406, -79.9959)
        assertTrue(model.cacheStats().hits > s1.hits)
        for (lat in 25..48) model.undulation(lat + 0.5, -100.5)
        val s2 = model.cacheStats()
        assertTrue(s2.tiles <= 8 + 1 && s2.evictions > 0)
        model.clearCache()
        assertEquals(0, model.cacheStats().tiles)
        assertEquals(-33.8874, model.undulation(39.96518095, -75.18037257).value(), NGS_TOLERANCE_M)   // Philadelphia (NGS)

        val failing = Geoid2022Model { throw IllegalStateException("disk gone") }
        assertEquals(GeoidUnavailableReason.LOAD_FAILED, failing.undulation(40.0, -77.0).reason())
        assertEquals(GeoidAvailability.FAILED, failing.availability)
        // Outside coverage is decided without loading.
        assertEquals(GeoidUnavailableReason.OUTSIDE_COVERAGE, failing.undulation(51.5, 0.0).reason())

        val egm96 = GeoidTilePack.fromBytes(resourceBytes("/geoid/egm96_global.srgp"))
        val wrong = Geoid2022Model { listOf(egm96) }
        val r = wrong.undulation(40.0, -77.0)
        assertEquals(GeoidUnavailableReason.LOAD_FAILED, r.reason())
        assertTrue((r as GeoidResult.Unavailable).detail.contains("EGM96"))
    }

    @Test fun `SRGP v2 header, pinned SHA-256 and corrupt packs`() {
        var total = 0L
        for ((path, sha) in Geoid2022Model.BUNDLED_PACKS) {
            val bytes = resourceBytes("/$path")
            assertEquals(path, sha, GeoidTilePack.sha256Hex(bytes))
            val p = GeoidTilePack.fromBytes(bytes, sha)
            assertEquals(2, p.formatVersion)
            assertEquals(GeoidModelId.GEOID2022, p.spec.model)
            if (path.endsWith("dgeoid.srgp")) {
                assertEquals("GL", p.gridRegion); assertEquals("geoidVelocity", p.quantity); assertEquals(1_000_000, p.unitsPerValue)
                assertEquals(4, p.spec.nodesPerDegree); assertEquals(5, p.spec.tileDegrees)
                assertEquals("DGEOID2022.GL.Ndot.beta_v0a.bin", p.sourceFile)
            } else {
                assertTrue(p.gridRegion in setOf("NA", "GC", "AS")); assertEquals("geoidHeight", p.quantity); assertEquals(1000, p.unitsPerValue)
                assertEquals(60, p.spec.nodesPerDegree); assertEquals(1, p.spec.tileDegrees)
                assertEquals("SGEOID2022.${p.gridRegion}.N.beta_v0a.bin", p.sourceFile)
            }
            assertEquals(Geoid2022Model.SOURCE_SHA256.getValue(p.sourceFile), p.sourceSha256)
            total += bytes.size
        }
        assertTrue("bundled GEOID2022 packs $total B", total < 6_000_000)
        // v1 packs read as before.
        val v1 = GeoidTilePack.fromBytes(resourceBytes("/geoid/egm96_global.srgp"), GlobalGeoidModels.EGM96_SHA256)
        assertEquals(1, v1.formatVersion); assertEquals("", v1.gridRegion); assertEquals("geoidHeight", v1.quantity); assertEquals(1000, v1.unitsPerValue)
        // Corrupt v2 header: units-per-value 0.
        val bytes = resourceBytes("/geoid/geoid2022_testtiles_GC.srgp")
        val b = ByteBuffer.wrap(bytes)
        b.position(16)
        repeat(5) { val n = b.short.toInt() and 0xFFFF; b.position(b.position() + n) }
        val unitsAt = b.position()
        assertEquals(1000, b.int)
        val bad = bytes.copyOf().also { ByteBuffer.wrap(it).putInt(unitsAt, 0) }
        try { GeoidTilePack.fromBytes(bad); fail("units 0 accepted") } catch (expected: IllegalArgumentException) { }
        val v3 = bytes.copyOf().also { ByteBuffer.wrap(it).putInt(4, 3) }
        try { GeoidTilePack.fromBytes(v3); fail("v3 accepted") } catch (expected: IllegalArgumentException) { }
    }

    @Test fun `metadata and status`() {
        val info = Geoid2022Model.INFO
        assertEquals(GeoidModelId.GEOID2022, info.id)
        assertEquals("GEOID2022", info.id.token)
        assertEquals(GeoidModelId.GEOID2022, GeoidModelId.fromToken("GEOID2022"))
        assertEquals(ProductStatus.VERIFIED_BETA, info.status)
        assertTrue(info.label.contains("beta"))
        assertTrue(info.dataVersion.contains("beta_v0a"))
        assertEquals("NAPGD2022", info.verticalDatum)
        assertEquals("itrf2020", info.horizontalFrameId)
        assertEquals("ITRF2020", info.horizontalFrame)
        assertEquals(2020.0, info.referenceEpoch!!, 0.0)
        assertEquals(GeoidInterpolation.BICUBIC, info.interpolation)
        assertEquals(1.0, info.resolutionArcMinutes, 0.0)
        assertNull(info.verticalCrsEpsg)
        assertFalse(info.isFallback)
        assertEquals(Geoid2022Model.BUNDLED_PACKS, info.provenance.bundledSha256)
        assertEquals("NAPGD2022", Geoid2022Model.bundled().verticalDatumName(40.0, -77.0))
        // The legacy models keep their status and have no frame id / epoch.
        for (legacy in listOf(Geoid18Model.INFO, GlobalGeoidModels.EGM2008_INFO, GlobalGeoidModels.EGM96_INFO)) {
            assertEquals(ProductStatus.VERIFIED_LEGACY, legacy.status)
            assertNull(legacy.horizontalFrameId)
            assertNull(legacy.referenceEpoch)
            assertEquals(GeoidInterpolation.BILINEAR, legacy.interpolation)
        }
    }

    @Test fun `bicubic weights`() {
        for (k in 0..100) {
            val w = Geoid2022Model.catmullRom(k / 100.0)
            assertEquals(1.0, w.sum(), 1e-15)
            assertTrue(w.sumOf { abs(it) } <= 1.25 + 1e-15)
        }
        assertEquals(listOf(true, false, true, true), Geoid2022Model.catmullRom(0.0).map { it == 0.0 })
        assertEquals(1.0, Geoid2022Model.catmullRom(0.0)[1], 0.0)
        assertEquals(listOf(true, true, false, true), Geoid2022Model.catmullRom(1.0).map { it == 0.0 })
    }

    private fun resourceBytes(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)) { "$path missing" }.use { it.readBytes() }

    private companion object {
        const val STORAGE_TOLERANCE_M = 0.00079
        const val NGS_TOLERANCE_M = 0.00085
        const val NGS_RATE_TOLERANCE_MM = 0.006
        val TEST_PACK_SHA256 = mapOf(
            "AS" to "be49c01f334ef5f0f359c28455d96114919379c68149b40156f2b05e9495a493",
            "NA" to "9f54ce4ba948006895ea929597e5f52afd4510acbec9105b3fb68e8cec089c00",
            "GC" to "dd646a66fd09e8a0dd3aaffed0d11b4294f6f1aac4292b2eaf1c7214d077ad50",
        )
    }

    @Test fun `impossible calendar dates are rejected, not silently computed`() {
        for ((y, m, d) in listOf(Triple(2026, 2, 30), Triple(2026, 4, 31), Triple(2025, 2, 29), Triple(2026, 13, 1))) {
            val r = runCatching { Geoid2022Model.modifiedJulianDay(y, m, d) }
            org.junit.Assert.assertTrue("$y-$m-$d", r.exceptionOrNull() is IllegalArgumentException)
        }
        org.junit.Assert.assertEquals(58849, Geoid2022Model.modifiedJulianDay(2020, 1, 1))
        org.junit.Assert.assertEquals(58908, Geoid2022Model.modifiedJulianDay(2020, 2, 29))   // leap day is valid
    }
}
