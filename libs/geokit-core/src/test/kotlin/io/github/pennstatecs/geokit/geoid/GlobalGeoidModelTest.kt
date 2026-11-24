package io.github.pennstatecs.geokit.geoid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

/**
 * EGM2008 / EGM96 against PROJ 9.8.1 (vgridshift on the PROJ-data grids,
 * libs/geokit-core/tools/geoid/geoid_reference_vectors.py), plus tiles, cache, wraparound and metadata.
 */
class GlobalGeoidModelTest {

    private data class Vec(val lat: Double, val lon: Double, val n08: Double, val n96: Double, val tile: String, val label: String)

    private val vectors: List<Vec> by lazy {
        val csv = checkNotNull(javaClass.getResourceAsStream("/geoid/global_geoid_vectors.csv")) { "global_geoid_vectors.csv missing" }
        csv.bufferedReader().use { it.readLines() }.drop(1).filter { it.isNotBlank() }.map {
            val p = it.split(",")
            Vec(p[0].toDouble(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4], p[5])
        }
    }

    private fun testPackFile(): File = File(javaClass.getResource("/geoid/egm2008_testtiles.srgp")!!.toURI())

    private fun testPack(): GeoidTilePack = GeoidTilePack.fromFile(testPackFile(), TEST_PACK_SHA256)

    private fun GeoidResult.value(): Double = (this as? GeoidResult.Value)?.undulationM ?: error("no value: $this")

    @Test fun `EGM2008 matches PROJ within 1 mm (bundled and installed tiles)`() {
        assertTrue(vectors.size >= 200)
        val model = GlobalGeoidModels.egm2008(extraSources = listOf(testPack()))
        var maxErr = 0.0
        var checked = 0
        for (v in vectors) {
            val r = model.undulation(v.lat, v.lon)
            if (v.tile == "none") {
                assertEquals("${v.lat},${v.lon}", GeoidUnavailableReason.TILE_NOT_AVAILABLE, (r as GeoidResult.Unavailable).reason)
                assertEquals(GeoidModelId.EGM2008, r.model)
                continue
            }
            val err = abs(r.value() - v.n08)
            maxErr = maxOf(maxErr, err)
            assertEquals("EGM2008 at ${v.lat},${v.lon} (${v.label})", v.n08, r.value(), TOLERANCE_M)
            assertEquals(GeoidModelId.EGM2008, r.model)
            checked++
        }
        println("EGM2008 vs PROJ: $checked points, max |error| = ${"%.4f".format(maxErr * 1000)} mm")
        assertTrue(checked >= 250)
        for (label in listOf("antimeridian", "north-pole", "south-pole", "aleutians", "prvi", "hawaii", "alaska", "corner", "edge", "node")) {
            assertTrue(label, vectors.any { it.label == label && it.tile != "none" })
        }
    }

    @Test fun `EGM96 matches PROJ within 1 mm everywhere`() {
        val model = GlobalGeoidModels.egm96()
        var maxErr = 0.0
        for (v in vectors) {
            val r = model.undulation(v.lat, v.lon)
            maxErr = maxOf(maxErr, abs(r.value() - v.n96))
            assertEquals("EGM96 at ${v.lat},${v.lon} (${v.label})", v.n96, r.value(), TOLERANCE_M)
            assertEquals(GeoidModelId.EGM96, r.model)
        }
        println("EGM96 vs PROJ: ${vectors.size} points, max |error| = ${"%.4f".format(maxErr * 1000)} mm")
        assertEquals(GeoidAvailability.AVAILABLE, model.availability)
    }

    @Test fun `bundled EGM2008 has no tile outside the US packs and never substitutes EGM96`() {
        val model = GlobalGeoidModels.egm2008()
        assertEquals(GeoidAvailability.NOT_LOADED, model.availability)
        for ((lat, lon) in listOf(51.5 to -0.1, 89.5 to 0.5, -33.9 to 151.2, 0.0 to 0.0, 60.0 to -100.0)) {
            val r = model.undulation(lat, lon) as GeoidResult.Unavailable
            assertEquals(GeoidUnavailableReason.TILE_NOT_AVAILABLE, r.reason)
            assertEquals(GeoidModelId.EGM2008, r.model)
            assertTrue(model.covers(lat, lon))
            assertFalse(model.isTileAvailable(lat, lon))
        }
        assertEquals(GeoidAvailability.PARTIAL, model.availability)
        assertTrue(model.isTileAvailable(40.0, -77.0))
        assertTrue(model.isTileAvailable(52.0, 179.5))
        assertTrue(model.isTileAvailable(52.0, -179.5))
        assertTrue(model.isTileAvailable(21.3, -157.8))
        assertTrue(model.isTileAvailable(13.4, 144.8))
        val (pr, us) = model.undulation(18.4655, -66.1057) to model.undulation(40.0, -77.0)
        assertEquals(GeoidModelId.EGM2008, pr.model)
        assertEquals("EGM2008", (us as GeoidResult.Value).verticalDatum)
    }

    @Test fun `antimeridian wraps for both models`() {
        val e08 = GlobalGeoidModels.egm2008()
        val e96 = GlobalGeoidModels.egm96()
        for (m in listOf<GeoidModel>(e08, e96)) {
            for (lat in listOf(51.3, 52.0, 53.97)) {
                val a = m.undulation(lat, 180.0).value()
                assertEquals(a, m.undulation(lat, -180.0).value(), 0.0)
                assertEquals(a, m.undulation(lat, 540.0).value(), 1e-9)
                assertEquals(a, m.undulation(lat, -540.0).value(), 1e-9)
                assertEquals(a, m.undulation(lat, 179.999999999).value(), 1e-6)
                assertEquals(a, m.undulation(lat, -179.999999999).value(), 1e-6)
                assertEquals(m.undulation(lat, -170.0).value(), m.undulation(lat, 190.0).value(), 1e-9)
            }
        }
    }

    @Test fun `continuous across tile edges and corners`() {
        val e08 = GlobalGeoidModels.egm2008()
        val e96 = GlobalGeoidModels.egm96()
        val eps = 1e-10
        for ((m, edges) in listOf(e08 to listOf(40.0 to -77.0, 33.0 to -117.0, 61.0 to -150.0, 47.0 to -100.0),
                                  e96 to listOf(40.0 to -75.0, 45.0 to 0.0, -30.0 to 150.0, 0.0 to -180.0))) {
            for ((lat, lon) in edges) {
                val c = m.undulation(lat, lon).value()
                for ((dl, dn) in listOf(-eps to 0.0, 0.0 to -eps, -eps to -eps, eps to eps, eps to -eps, -eps to eps)) {
                    assertEquals("${m.info.label} $lat,$lon", c, m.undulation(lat + dl, lon + dn).value(), 1e-6)
                }
            }
        }
        // Poles: one value along the whole pole row.
        val pack = testPack()
        val withTest = GlobalGeoidModels.egm2008(extraSources = listOf(pack))
        assertEquals(withTest.undulation(90.0, 179.5).value(), withTest.undulation(90.0, -179.5).value(), 1e-9)
        assertEquals(e96.undulation(-90.0, 10.0).value(), e96.undulation(-90.0, -123.0).value(), 1e-9)
        pack.close()
    }

    @Test fun `tiles repeat identical edge nodes`() {
        val pack = testPack()
        val s = pack.spec.tileSize
        val east = pack.loadTile(GeoidTileKey(179, 0))!!     // 89..90 N, -180..-179
        val west = pack.loadTile(GeoidTileKey(179, 359))!!   // 89..90 N, 179..180
        for (i in 0 until s) assertEquals(west[i * s + s - 1], east[i * s])
        val a = pack.loadTile(GeoidTileKey(89, 179))!!       // -1..0, -1..0
        val b = pack.loadTile(GeoidTileKey(90, 179))!!       // 0..1, -1..0
        for (j in 0 until s) assertEquals(a[(s - 1) * s + j], b[j])
        pack.close()
    }

    @Test fun `cache respects tile and byte limits and reloads evicted tiles identically`() {
        val reference = GlobalGeoidModels.egm2008()
        val small = GlobalGeoidModels.egm2008(cacheLimits = GeoidTileCacheLimits(maxTiles = 3))
        val pts = (0 until 10).map { 30.5 + it to -100.5 + it }
        val expected = pts.map { reference.undulation(it.first, it.second).value() }
        repeat(2) { pts.forEachIndexed { k, p -> assertEquals(expected[k], small.undulation(p.first, p.second).value(), 0.0) } }
        val st = small.cacheStats()
        assertEquals(3, st.tiles)
        assertEquals(17L, st.evictions)
        assertEquals(20L, st.misses)
        assertEquals(3L * 625 * 4, st.bytes)

        val bytes = GlobalGeoidModels.egm2008(cacheLimits = GeoidTileCacheLimits(maxTiles = 100, maxBytes = 6000))
        pts.forEach { bytes.undulation(it.first, it.second) }
        assertEquals(2, bytes.cacheStats().tiles)
        assertTrue(bytes.cacheStats().bytes <= 6000)

        val tiny = GlobalGeoidModels.egm2008(cacheLimits = GeoidTileCacheLimits(maxTiles = 1, maxBytes = 1))
        pts.forEachIndexed { k, p -> assertEquals(expected[k], tiny.undulation(p.first, p.second).value(), 0.0) }
        assertEquals(1, tiny.cacheStats().tiles)

        // Repeated lookups in one tile hit the cache.
        val hits = GlobalGeoidModels.egm2008()
        repeat(50) { hits.undulation(40.0 + it * 0.01, -77.5) }
        assertEquals(1L, hits.cacheStats().misses)
        assertEquals(49L, hits.cacheStats().hits)
        hits.clearCache()
        assertEquals(0, hits.cacheStats().tiles)
    }

    @Test fun `concurrent lookups agree with single-threaded results`() {
        val reference = GlobalGeoidModels.egm2008()
        val rnd = Random(42)
        val pts = List(4000) { rnd.nextDouble(35.0, 45.0) to rnd.nextDouble(-90.0, -75.0) }
        val expected = pts.map { reference.undulation(it.first, it.second).value() }
        val shared = GlobalGeoidModels.egm2008(cacheLimits = GeoidTileCacheLimits(maxTiles = 4))
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 8).map { t ->
                Callable {
                    var bad = 0
                    for (k in pts.indices) {
                        val idx = (k * 7 + t * 501) % pts.size
                        val (lat, lon) = pts[idx]
                        if (shared.undulation(lat, lon).value() != expected[idx]) bad++
                    }
                    bad
                }
            }
            val results = pool.invokeAll(tasks, 120, TimeUnit.SECONDS).map { it.get() }
            assertEquals(List(8) { 0 }, results)
        } finally {
            pool.shutdownNow()
        }
        assertTrue(shared.cacheStats().tiles <= 4)
    }

    @Test fun `model identification metadata`() {
        val e08 = GlobalGeoidModels.EGM2008_INFO
        val e96 = GlobalGeoidModels.EGM96_INFO
        assertEquals(GeoidModelId.EGM2008, e08.id)
        assertEquals("EGM2008", e08.label)
        assertEquals("WGS84", e08.horizontalFrame)
        assertEquals("EGM2008", e08.verticalDatum)
        assertEquals(3855, e08.verticalCrsEpsg)
        assertEquals(2.5, e08.resolutionArcMinutes, 0.0)
        assertFalse(e08.isFallback)
        assertEquals(GeoidModelId.EGM96, e96.id)
        assertEquals("EGM96", e96.label)
        assertEquals(5773, e96.verticalCrsEpsg)
        assertEquals(15.0, e96.resolutionArcMinutes, 0.0)
        assertTrue(e96.isFallback)
        for (info in listOf(e08, e96)) {
            assertNotEquals("NAVD88", info.verticalDatum)
            assertEquals(GeoidInterpolation.BILINEAR, info.interpolation)
            assertTrue(info.provenance.sourceUrl.startsWith("https://cdn.proj.org/"))
            assertTrue(info.provenance.licence.contains("Public Domain"))
        }
        assertEquals("EGM96", GlobalGeoidModels.egm96().verticalDatumName(40.0, -77.0))
        // Stored tokens never change spelling.
        assertEquals(listOf("GEOID18", "EGM2008", "EGM96"), GeoidModelId.entries.map { it.token })
        for (id in GeoidModelId.entries) assertEquals(id, GeoidModelId.fromToken(id.token))
        assertEquals(null, GeoidModelId.fromToken("NAVD88"))
        assertEquals(null, GeoidModelId.fromToken(null))
    }

    @Test fun `bundled resources match their pinned SHA-256`() {
        val all = GlobalGeoidModels.EGM2008_BUNDLED_PACKS + (GlobalGeoidModels.EGM96_RESOURCE to GlobalGeoidModels.EGM96_SHA256)
        var total = 0L
        for ((path, sha) in all) {
            val bytes = javaClass.getResourceAsStream("/$path")!!.use { it.readBytes() }
            total += bytes.size
            assertEquals(path, sha, GeoidTilePack.sha256Hex(bytes))
            val pack = GeoidTilePack.fromBytes(bytes)
            assertEquals(if (path.contains("egm96")) "us_nga_egm96_15.tif" else "us_nga_egm08_25.tif", pack.sourceFile)
        }
        assertTrue("bundled global geoid data $total bytes", total < 4_000_000)
    }

    @Test fun `load failures and wrong data are reported, not substituted`() {
        val failing = TiledGeoidModel(GlobalGeoidModels.EGM2008_INFO) { throw IllegalStateException("no data") }
        val r = failing.undulation(40.0, -77.0) as GeoidResult.Unavailable
        assertEquals(GeoidUnavailableReason.LOAD_FAILED, r.reason)
        assertEquals(GeoidAvailability.FAILED, failing.availability)

        val egm96Bytes = javaClass.getResourceAsStream("/" + GlobalGeoidModels.EGM96_RESOURCE)!!.use { it.readBytes() }
        val wrongModel = TiledGeoidModel(GlobalGeoidModels.EGM2008_INFO) { GeoidTilePack.fromBytes(egm96Bytes) }
        assertEquals(GeoidUnavailableReason.LOAD_FAILED, (wrongModel.undulation(40.0, -77.0) as GeoidResult.Unavailable).reason)

        val e = GlobalGeoidModels.egm2008()
        assertEquals(GeoidUnavailableReason.INVALID_POSITION, (e.undulation(Double.NaN, 0.0) as GeoidResult.Unavailable).reason)
        assertEquals(GeoidUnavailableReason.INVALID_POSITION, (e.undulation(90.5, 0.0) as GeoidResult.Unavailable).reason)
        assertFalse(e.covers(-91.0, 0.0))

        try {
            CompositeGeoidTileSource(listOf(GeoidTilePack.fromBytes(egm96Bytes), testPack())); fail("mixed grids accepted")
        } catch (expected: IllegalArgumentException) { }
    }

    @Test fun `pack files are verified before use`() {
        val dir = Files.createTempDirectory("geoidpacks").toFile()
        try {
            val good = File(dir, "egm2008_testtiles.srgp").also { testPackFile().copyTo(it) }
            try { GeoidTilePack.fromFile(good, "0".repeat(64)); fail("bad digest accepted") } catch (expected: IllegalArgumentException) { }

            val packs = GeoidTilePack.fromManifest(dir, "# installed tiles\n$TEST_PACK_SHA256  egm2008_testtiles.srgp\n")
            assertEquals(1, packs.size)
            assertEquals(11, packs[0].tileCount)
            val model = GlobalGeoidModels.egm2008(extraSources = packs)
            assertTrue(model.isTileAvailable(0.5, 0.5))
            packs.forEach { it.close() }

            for (bad in listOf("$TEST_PACK_SHA256  ../egm2008_testtiles.srgp", "$TEST_PACK_SHA256  sub/x.srgp", "nothex  x.srgp")) {
                try { GeoidTilePack.fromManifest(dir, bad); fail("accepted: $bad") } catch (expected: IllegalArgumentException) { }
            }

            // Corrupt the tile data after the header: the pack opens, the tile load fails as LOAD_FAILED.
            val bytes = good.readBytes()
            bytes[bytes.size - 20] = (bytes[bytes.size - 20].toInt() xor 0x5A).toByte()
            val corrupt = GeoidTilePack.fromBytes(bytes)
            val lastKey = corrupt.keys.maxWith(compareBy({ it.row }, { it.col }))
            val m = TiledGeoidModel(GlobalGeoidModels.EGM2008_INFO) { corrupt }
            val lat = -90.0 + lastKey.row + 0.5
            val lon = -180.0 + lastKey.col + 0.5
            assertEquals(GeoidUnavailableReason.LOAD_FAILED, (m.undulation(lat, lon) as GeoidResult.Unavailable).reason)

            try { GeoidTilePack.fromBytes(bytes.copyOf(40)); fail("truncated pack accepted") } catch (expected: IllegalArgumentException) { }
            try { GeoidTilePack.fromBytes("NOPE".toByteArray() + bytes); fail("bad magic accepted") } catch (expected: IllegalArgumentException) { }
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        /** 0.5 mm storage rounding + 0.5 mm; the task's limit is 1 mm beyond rounding. */
        const val TOLERANCE_M = 0.001
        const val TEST_PACK_SHA256 = "33cd8bbcc994295b3ece67322cc968da1524b8af70609d6d43d919b76864f26b"
    }
}
