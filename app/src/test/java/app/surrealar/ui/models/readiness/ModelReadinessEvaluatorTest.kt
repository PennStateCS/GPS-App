package app.surrealar.ui.models.readiness

import app.surrealar.ui.openinar.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelReadinessEvaluatorTest {

    // ------------------------------------------------------------ builders

    /** A box w (x) × h (y) × d (z) whose bottom-centre sits at (cx, bottomY, cz). */
    private fun box(w: Double, h: Double, d: Double, bottomY: Double = 0.0, cx: Double = 0.0, cz: Double = 0.0) = Bounds(
        Vec3((cx - w / 2).toFloat(), bottomY.toFloat(), (cz - d / 2).toFloat()),
        Vec3((cx + w / 2).toFloat(), (bottomY + h).toFloat(), (cz + d / 2).toFloat()),
    )

    private fun gltf(
        bounds: Bounds? = box(1.0, 1.0, 1.0),
        tris: Long? = 12,
        textures: Int? = 1,
        maxSide: Int? = 2048,
        texBytes: Long? = 2048L * 1024 * 4 * 4 / 3,
        fileSize: Long = 122_276,
        required: List<String> = emptyList(),
        missing: List<String> = emptyList(),
        parseError: String? = null,
    ) = GltfFacts(
        fileSizeBytes = fileSize, bounds = bounds, triangleCount = tris, vertexCount = tris?.times(2), meshCount = 1,
        textureCount = textures, maxTextureSidePx = maxSide, estimatedTextureBytes = texBytes,
        extensionsRequired = required, extensionsUsed = required, missingResources = missing, parseError = parseError,
    )

    private fun link(
        id: String = "c1",
        name: String = "Corner",
        origin: String? = "BOTTOM_CENTER",
        scale: Double? = null,
        verticalOffset: Double? = null,
        pitch: Double? = null,
        rtk: String? = "FIX",
        hAcc: Double? = 0.02,
        lat: Double = 40.123456,
        lon: Double = -75.654321,
    ) = LinkFacts(
        coordinateId = id, coordinateName = name, placementOrigin = origin, scale = scale, yawDeg = null,
        pitchDeg = pitch, rollDeg = null, verticalOffsetM = verticalOffset, originOffsetXM = null, originOffsetYM = null,
        originOffsetZM = null, rtkStatus = rtk, horizontalAccuracyM = hAcc, latDeg = lat, lonDeg = lon,
    )

    private fun facts(
        gltf: GltfFacts = gltf(),
        links: List<LinkFacts> = listOf(link()),
        loaded: Boolean? = null,
        isValid: Boolean = true,
        errors: List<String> = emptyList(),
        embeddedLat: Double? = null,
        embeddedLon: Double? = null,
        defaultScale: Double? = null,
    ) = ModelFacts(
        modelId = "m1", modelName = "Cube", fileName = "cube.glb", gltf = gltf, loadedByRenderer = loaded,
        embeddedLatDeg = embeddedLat, embeddedLonDeg = embeddedLon, isValid = isValid, validationErrors = errors,
        units = "m", defaultScale = defaultScale, links = links,
    )

    private fun eval(f: ModelFacts) = ModelReadinessEvaluator.evaluate(f)
    private fun ReadinessReport.one(id: String) = checks.single { it.id == id }
    private fun ReadinessReport.all(id: String) = checks.filter { it.id == id }

    // ------------------------------------------------------------ the happy path

    @Test
    fun `1 m cube on Bottom Center with an RTK point is ready`() {
        val r = eval(facts(loaded = true))
        assertEquals(Verdict.READY, r.verdict)
        assertEquals("Ready for AR", r.summary)
        assertEquals("Loads in the viewer.", r.one("load").detail)
        assertEquals("About 1.00 × 1.00 × 1.00 m in AR (width × depth × height).", r.one("units").detail)
        assertEquals("It sits on the point.", r.one("placement").detail)
        assertEquals("12 triangles, 1 texture (max 2,048 px).", r.one("complexity").detail)
        assertEquals("Linked to 1 point: ‘Corner’.", r.one("linked").detail)
        assertEquals(CheckStatus.PASS, r.one("anchorAccuracy").status)
        assertEquals(
            "In AR the model's +Z axis points north; rotate with Yaw if it faces the wrong way.",
            r.one("north").detail,
        )
        assertTrue(r.all("validation").isEmpty())
        assertTrue(r.all("origin").isEmpty())
        assertTrue(r.all("upAxis").isEmpty())
        assertTrue(r.all("georeference").isEmpty())
    }

    // ------------------------------------------------------------ 1. load / 2. validation

    @Test
    fun `load fails on parse error, missing files or renderer failure`() {
        assertEquals(
            "Couldn't read the file: File is truncated.",
            eval(facts(gltf(bounds = null, parseError = "File is truncated"))).one("load").detail,
        )
        val missing = eval(facts(gltf(missing = listOf("geo.bin", "tex.png")))).one("load")
        assertEquals(CheckStatus.FAIL, missing.status)
        assertEquals("Missing files: geo.bin, tex.png.", missing.detail)
        assertEquals("The viewer couldn't load it.", eval(facts(loaded = false)).one("load").detail)
    }

    @Test
    fun `unknown required extension may not load, supported ones pass`() {
        val warn = eval(facts(gltf(required = listOf("EXT_made_up")))).one("load")
        assertEquals(CheckStatus.WARN, warn.status)
        assertEquals("It requires EXT_made_up, which the viewer doesn't support, so it may not load.", warn.detail)
        val ok = eval(facts(gltf(required = listOf("KHR_draco_mesh_compression", "KHR_materials_unlit")))).one("load")
        assertEquals(CheckStatus.PASS, ok.status)
        assertEquals("The file reads correctly.", ok.detail)
    }

    @Test
    fun `invalid import lists the first three errors`() {
        val c = eval(facts(isValid = false, errors = listOf("a", "b", "c", "d"))).one("validation")
        assertEquals(CheckStatus.FAIL, c.status)
        assertEquals("Import found problems: a; b; c (and 1 more).", c.detail)
    }

    @Test
    fun `unreadable geometry is one FAIL and skips the geometry rules`() {
        val r = eval(facts(gltf(bounds = null)))
        assertEquals("Couldn't read the model's geometry.", r.one("geometry").detail)
        assertTrue(r.all("units").isEmpty())
        assertTrue(r.all("placement").isEmpty())
        assertEquals(Verdict.NOT_READY, r.verdict)
    }

    // ------------------------------------------------------------ 3. units

    @Test
    fun `millimetre model fails units`() {
        val r = eval(facts(gltf(bounds = box(12_000.0, 3_000.0, 8_000.0))))
        val c = r.one("units")
        assertEquals(CheckStatus.FAIL, c.status)
        assertEquals("It is 12.0 km across, so AR will shrink it to 1 m — probably millimetre units.", c.detail)
        assertEquals("Re-export the model in metres.", c.fix)
    }

    @Test
    fun `tiny model warns about units`() {
        val c = eval(facts(gltf(bounds = box(0.004, 0.003, 0.002)))).one("units")
        assertEquals(CheckStatus.WARN, c.status)
        assertEquals("It is only 4.0 mm across in AR — probably the wrong units.", c.detail)
    }

    @Test
    fun `size uses the link scale, then the default scale`() {
        assertEquals(
            "About 2.00 × 2.00 × 2.00 m in AR (width × depth × height).",
            eval(facts(links = listOf(link(scale = 2.0)))).one("units").detail,
        )
        assertEquals(
            "About 30.0 × 15.0 × 7.50 m in AR (width × depth × height).",
            eval(facts(gltf(bounds = box(20.0, 5.0, 10.0)), defaultScale = 1.5)).one("units").detail,
        )
    }

    // ------------------------------------------------------------ 4. placement

    @Test
    fun `default Center placement sinks half of a 10 m building`() {
        val r = eval(facts(gltf(bounds = box(20.0, 10.0, 15.0)), links = listOf(link(origin = null))))
        val c = r.one("placement")
        assertEquals(CheckStatus.WARN, c.status)
        assertEquals("It sinks 5.00 m below the point (50% of the model is underground).", c.detail)
        assertEquals("Use Bottom Center so it sits on the point.", c.fix)
        assertEquals(FixAction.USE_BOTTOM_CENTER, c.fixAction)
        assertEquals("c1", c.fixCoordinateId)
    }

    @Test
    fun `Bottom Center sits on the point`() {
        val c = eval(facts(gltf(bounds = box(20.0, 10.0, 15.0)))).one("placement")
        assertEquals(CheckStatus.PASS, c.status)
        assertEquals("It sits on the point.", c.detail)
    }

    @Test
    fun `unlinked model is judged with the default Center placement`() {
        val c = eval(facts(gltf(bounds = box(20.0, 10.0, 15.0)), links = emptyList())).one("placement")
        assertEquals("With the default Center placement it sinks 5.00 m below the point (50% of the model is underground).", c.detail)
        assertEquals(FixAction.USE_BOTTOM_CENTER, c.fixAction)
        assertNull(c.fixCoordinateId)
    }

    @Test
    fun `geometry above its origin floats and the fix offsets it down`() {
        val c = eval(
            facts(gltf(bounds = box(4.0, 3.0, 4.0, bottomY = 2.0)), links = listOf(link(origin = "ORIGIN", verticalOffset = 0.5))),
        ).one("placement")
        assertEquals(CheckStatus.WARN, c.status)
        assertEquals("It floats 2.50 m above the point.", c.detail)
        assertEquals(FixAction.SET_VERTICAL_OFFSET, c.fixAction)
        assertEquals(-2.0, c.fixValue!!, 1e-6)
        assertEquals("Set the vertical offset to -2.00 m so it sits on the point.", c.fix)
    }

    @Test
    fun `pitch -90 stands a Z-up model on Bottom Center correctly`() {
        // Flat Z-up export: 10 m tall along +Z. Bottom Center + pitch -90 rotates z into height.
        val zUp = box(4.0, 0.2, 10.0)
        val c = eval(facts(gltf(bounds = zUp), links = listOf(link(pitch = -90.0)))).one("placement")
        // Rotated about the anchor (bottom centre) the model spans -5..+5 m: half underground.
        assertEquals("It sinks 5.00 m below the point (50% of the model is underground).", c.detail)
    }

    @Test
    fun `several points are named and capped at five plus a summary`() {
        val links = (1..7).map { link(id = "c$it", name = "P$it", origin = if (it % 2 == 0) null else "BOTTOM_CENTER") }
        val checks = eval(facts(gltf(bounds = box(20.0, 10.0, 15.0)), links = links)).all("placement")
        assertEquals(6, checks.size)
        val p2 = checks.single { it.title == "Placement at ‘P2’" }
        assertEquals("At ‘P2’ it sinks 5.00 m below the point (50% of the model is underground).", p2.detail)
        assertEquals("c2", p2.fixCoordinateId)
        val summary = checks.single { it.title == "Placement at other points" }
        assertEquals("2 more points: the model sinks or floats at 1 of them.", summary.detail)
    }

    // ------------------------------------------------------------ 5. origin

    @Test
    fun `geometry far from the origin warns only for Origin or Custom placement`() {
        val far = box(10.0, 5.0, 10.0, cx = 150.0)
        val warn = eval(facts(gltf(bounds = far), links = listOf(link(origin = "ORIGIN")))).one("origin")
        assertEquals(CheckStatus.WARN, warn.status)
        assertEquals("The model's geometry is 150.0 m from its origin, so it will appear away from the point.", warn.detail)
        assertEquals(FixAction.USE_BOTTOM_CENTER, warn.fixAction)
        assertEquals("c1", warn.fixCoordinateId)

        val info = eval(facts(gltf(bounds = far))).one("origin")
        assertEquals(CheckStatus.INFO, info.status)
        assertEquals("The geometry is 150.0 m from its origin, but Center/Bottom Center placement handles it.", info.detail)
    }

    // ------------------------------------------------------------ 6. up axis

    @Test
    fun `very flat solid suggests a Z-up export`() {
        val flat = gltf(bounds = box(20.0, 0.5, 10.0), tris = 5000)
        val c = eval(facts(flat)).one("upAxis")
        assertEquals(CheckStatus.WARN, c.status)
        assertEquals(
            "Very flat (0.50 m tall, 20.0 × 10.0 m across). Fine for terrain or a slab, but if it should stand up " +
                "it was probably exported Z-up.",
            c.detail,
        )
        assertEquals(FixAction.ROTATE_UP_AXIS, c.fixAction)
        assertEquals("c1", c.fixCoordinateId)
        // Already rotated, a 2-triangle quad, or unknown triangles: nothing to say.
        assertTrue(eval(facts(flat, links = listOf(link(pitch = -90.0)))).all("upAxis").isEmpty())
        assertTrue(eval(facts(gltf(bounds = box(20.0, 0.5, 10.0), tris = 2))).all("upAxis").isEmpty())
        assertTrue(eval(facts(gltf(bounds = box(20.0, 0.5, 10.0), tris = null))).all("upAxis").isEmpty())
    }

    // ------------------------------------------------------------ 8. complexity

    @Test
    fun `complexity thresholds`() {
        fun c(g: GltfFacts) = eval(facts(g)).one("complexity")
        assertEquals(CheckStatus.WARN, c(gltf(tris = 2_000_000)).status)
        assertEquals("2,000,000 triangles may make AR stutter.", c(gltf(tris = 2_000_000)).detail)
        val fail = c(gltf(tris = 5_000_000))
        assertEquals(CheckStatus.FAIL, fail.status)
        assertEquals("5,000,000 triangles is more than the tablet can draw (limit 4,000,000).", fail.detail)
        assertEquals(CheckStatus.WARN, c(gltf(maxSide = 8192, texBytes = 8192L * 8192 * 4 * 4 / 3)).status)
        assertEquals(CheckStatus.FAIL, c(gltf(texBytes = 600L * 1024 * 1024)).status)
        assertEquals(CheckStatus.WARN, c(gltf(texBytes = 300L * 1024 * 1024)).status)
        assertEquals(
            "The file is 120 MB (the import limit is 150 MB).",
            c(gltf(fileSize = 120L * 1024 * 1024)).detail,
        )
        val unknown = c(gltf(tris = null))
        assertEquals(CheckStatus.INFO, unknown.status)
        assertEquals("Unknown triangle count, 1 texture (max 2,048 px). Couldn't read the triangle count.", unknown.detail)
        assertEquals("12 triangles, no textures.", c(gltf(textures = 0, maxSide = 0, texBytes = 0)).detail)
    }

    // ------------------------------------------------------------ 9. linked / 10. anchor accuracy

    @Test
    fun `unlinked model warns it won't appear in AR`() {
        val r = eval(facts(links = emptyList()))
        val c = r.one("linked")
        assertEquals(CheckStatus.WARN, c.status)
        assertEquals("Not linked to any point, so it won't appear in AR.", c.detail)
        assertEquals(FixAction.LINK_POINT, c.fixAction)
        assertTrue(r.all("anchorAccuracy").isEmpty())
    }

    @Test
    fun `single-fix anchor warns how far off the model can sit`() {
        val c = eval(facts(links = listOf(link(rtk = "SINGLE", hAcc = 12.0)))).one("anchorAccuracy")
        assertEquals(CheckStatus.WARN, c.status)
        assertEquals("‘Corner’ was captured as Single ±12 m, so the model can sit that far off.", c.detail)
        val float = eval(facts(links = listOf(link(rtk = "RTK_FLOAT", hAcc = 0.8)))).one("anchorAccuracy")
        assertEquals("‘Corner’ was captured as RTK Float ±0.80 m, so the model can sit that far off.", float.detail)
        assertEquals(CheckStatus.WARN, eval(facts(links = listOf(link(rtk = "NO_FIX", hAcc = null)))).one("anchorAccuracy").status)
        assertEquals(CheckStatus.PASS, eval(facts(links = listOf(link(rtk = "float", hAcc = 0.3)))).one("anchorAccuracy").status)
    }

    // ------------------------------------------------------------ 11. georeference

    @Test
    fun `embedded location is compared with the linked point`() {
        // 0.001° of latitude ≈ 111 m.
        val far = eval(facts(embeddedLat = 40.124456, embeddedLon = -75.654321)).one("georeference")
        assertEquals(CheckStatus.WARN, far.status)
        assertTrue(far.detail, Regex("^Its embedded location is 11\\d\\.\\d m from ‘Corner’\\.$").matches(far.detail))
        val near = eval(facts(embeddedLat = 40.123459, embeddedLon = -75.654321)).one("georeference")
        assertEquals(CheckStatus.PASS, near.status)
        assertEquals("Its embedded location matches ‘Corner’ (within 1.00 m).", near.detail)
    }

    // ------------------------------------------------------------ verdict, summary, ordering, privacy

    @Test
    fun `verdict, summary and ordering`() {
        val warnOnly = eval(facts(links = listOf(link(origin = null))))
        assertEquals(Verdict.READY_WITH_WARNINGS, warnOnly.verdict)
        assertEquals("Ready with 1 warning", warnOnly.summary)

        val bad = eval(facts(gltf(tris = 5_000_000, missing = listOf("geo.bin")), links = emptyList()))
        assertEquals(Verdict.NOT_READY, bad.verdict)
        assertEquals("Not ready: 2 problems", bad.summary)
        val ranks = bad.checks.map { listOf(CheckStatus.FAIL, CheckStatus.WARN, CheckStatus.INFO, CheckStatus.PASS).indexOf(it.status) }
        assertEquals(ranks.sorted(), ranks)
        // Stable within a status: load (evaluated first) precedes complexity.
        assertEquals(listOf("load", "complexity"), bad.checks.filter { it.status == CheckStatus.FAIL }.map { it.id })
    }

    @Test
    fun `no message contains a latitude or longitude`() {
        val links = listOf(
            link(origin = null, rtk = "SINGLE", hAcc = 3.0),
            link(id = "c2", name = "Gate 40.123456", origin = "ORIGIN", lat = 40.2, lon = -75.7),
        )
        val r = eval(
            facts(
                gltf(bounds = box(20.0, 0.5, 10.0, cx = 60.0), tris = 5000, missing = listOf("tile_40.123456.bin")),
                links = links, isValid = false, errors = listOf("Bad location 40.123456, -75.654321"),
                embeddedLat = 40.5, embeddedLon = -75.9,
            ),
        )
        val text = r.checks.flatMap { listOfNotNull(it.title, it.detail, it.fix) } + r.summary
        for (s in text) {
            for (needle in listOf("40.12", "75.65", "40.2", "75.7", "40.5", "75.9")) {
                assertFalse("'$s' leaks $needle", s.contains(needle))
            }
        }
    }
    @Test fun `sinking with Bottom Center already set offers the exact offset, never Bottom Center again`() {
        // A Z-up export rotated upright (pitch -90) on Bottom Center: AR picks the anchor before rotating,
        // so it still sinks. Offering "Use Bottom Center" again would loop; the offset fixes it.
        val zUp = gltf(bounds = box(4.0, 0.3, 10.0))
        val r = eval(facts(gltf = zUp, links = listOf(link(origin = "BOTTOM_CENTER", pitch = -90.0))))
        val sink = r.all("placement").single()
        if (sink.status == CheckStatus.WARN && sink.detail.contains("sinks")) {
            assertEquals(FixAction.SET_VERTICAL_OFFSET, sink.fixAction)
            assertTrue("offset raises the model", (sink.fixValue ?: 0.0) > 0.0)
        }
        // And a plain sinking model on Center still gets the Bottom Center fix.
        val center = eval(facts(gltf = gltf(bounds = box(10.0, 10.0, 10.0)), links = listOf(link(origin = "CENTER")))).all("placement").single()
        assertEquals(FixAction.USE_BOTTOM_CENTER, center.fixAction)
        // Applying it (Bottom Center, no rotation) makes it sit.
        val fixed = eval(facts(gltf = gltf(bounds = box(10.0, 10.0, 10.0)), links = listOf(link(origin = "BOTTOM_CENTER")))).all("placement").single()
        assertEquals(CheckStatus.PASS, fixed.status)
    }
}
