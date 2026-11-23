package app.surrealar.ui.openinar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Remaining [ArDebugReport] branches: every hint table, optics / AR-vs-GNSS / gate-profile lines,
 * test-grid and anchor marks, the anchor-row overflow, and the later NEXT blockers.
 */
class ArDebugReportBranchesTest {

    private val fix = ArDebugReport.FixInfo(
        provider = "RS2+", rtk = "FIX", sats = 20, hAccM = 0.02, gateAccM = 0.02,
        ageMs = 1500, lat = 41.3477735, lon = -76.0225155,
    )

    private fun input(
        camera: String? = "TRACKING",
        cameraReason: String? = "NONE",
        featuresCounted: Boolean = true,
        earth: String? = "TRACKING",
        earthState: String? = "ENABLED",
        f: ArDebugReport.FixInfo? = fix,
        items: Int = 0,
        created: Boolean = false,
        awaiting: Boolean = false,
        anchors: List<ArDebugReport.AnchorRow> = emptyList(),
        pending: Boolean = false,
        grid: ArDebugReport.TestGridInfo? = null,
        inScene: Int = 0,
        poses: Int = 0,
        optics: ArDebugReport.Optics? = null,
        arVsGnss: ArVsGnss? = null,
        gateProfile: String? = null,
        camLat: Double? = null,
        camAlt: Double? = null,
        camHeading: Double? = null,
    ) = ArDebugReport.Input(
        geospatialEnabled = true, depthEnabled = false,
        cameraTracking = camera, cameraFailureReason = cameraReason,
        featuresCounted = featuresCounted, planes = 1, points = 50,
        earthTracking = earth, earthState = earthState,
        earthHAccM = null, earthVAccM = Double.NaN, earthYawDeg = null,
        camLat = camLat, camLon = camLat?.let { -76.0 }, camAlt = camAlt, camHeading = camHeading,
        fix = f, gnssGateMaxM = 20.0,
        items = items, itemsWithModels = 0,
        geoAnchorsCreated = created, awaitingLocalization = awaiting,
        geoAnchors = anchors, testSpawnPending = pending, testGrid = grid,
        modelsInScene = inScene, modelPoses = poses,
        optics = optics, arVsGnss = arVsGnss, gateProfile = gateProfile,
    )

    private fun anchor(name: String, tracking: String = "TRACKING", terrain: String? = null, dist: Double? = 12.34) =
        ArDebugReport.AnchorRow(name, tracking, terrain, dist, 45.0, "PIN")

    /** The stage line whose label (after the one-char mark and a space) starts with [prefix]. */
    private fun line(text: String, prefix: String) =
        text.lines().first { it.length > 2 && it.substring(2).startsWith(prefix) }

    @Test fun `earth state hints`() {
        assertEquals("API key quota exceeded", ArDebugReport.earthStateHint("ERROR_RESOURCE_EXHAUSTED"))
        assertEquals("update Google Play Services for AR", ArDebugReport.earthStateHint("ERROR_APK_VERSION_TOO_OLD"))
        assertEquals("geospatial mode not enabled in session config", ArDebugReport.earthStateHint("ERROR_GEOSPATIAL_MODE_DISABLED"))
        assertEquals("ARCore Earth error; see log", ArDebugReport.earthStateHint("ERROR_INTERNAL"))
    }

    @Test fun `camera hints`() {
        assertEquals("too dark", ArDebugReport.cameraHint("INSUFFICIENT_LIGHT"))
        assertEquals("move slower", ArDebugReport.cameraHint("EXCESSIVE_MOTION"))
        assertEquals("another app is using the camera", ArDebugReport.cameraHint("CAMERA_UNAVAILABLE"))
        assertEquals("ARCore bad state; restart AR", ArDebugReport.cameraHint("BAD_STATE"))
        assertEquals("see log", ArDebugReport.cameraHint("SOMETHING_NEW"))
    }

    @Test fun `terrain hints`() {
        assertEquals("API key not authorized for ARCore API", ArDebugReport.terrainHint("ERROR_NOT_AUTHORIZED"))
        assertEquals("no terrain data at this location (bad lat/lon?)", ArDebugReport.terrainHint("ERROR_UNSUPPORTED_LOCATION"))
        assertEquals("ARCore internal error; often bad input coords", ArDebugReport.terrainHint("ERROR_INTERNAL"))
        assertEquals("see log", ArDebugReport.terrainHint("ERROR_OTHER"))
    }

    @Test fun `camera not started without a reason gets the generic hint`() {
        val b = ArDebugReport.nextBlocker(input(camera = null, cameraReason = null))!!
        assertEquals("Camera not started — hold steady, aim at a textured surface", b)
        val text = ArDebugReport.build(input(camera = null, cameraReason = null, featuresCounted = false))
        assertTrue(text, line(text, "Camera").contains("n/a  planes – · points –"))
    }

    @Test fun `earth error is marked failed on the Earth line with unknown accuracies`() {
        val text = ArDebugReport.build(input(earthState = "ERROR_INTERNAL"))
        assertTrue(text, line(text, "Earth ").startsWith("✗ Earth    TRACKING [ERROR_INTERNAL]  H±? V±? yaw±?"))
        val waiting = ArDebugReport.build(input(earth = null, earthState = null))
        assertTrue(waiting, line(waiting, "Earth ").startsWith("… Earth    n/a [n/a]"))
    }

    @Test fun `no fix shows the waiting GNSS line and blocks`() {
        assertEquals("No GNSS fix yet — check GNSS source / receiver connection", ArDebugReport.nextBlocker(input(f = null)))
        assertTrue(ArDebugReport.build(input(f = null)).contains("… GNSS     no fix yet"))
    }

    @Test fun `GNSS line accuracy text falls back to HDOP estimate then unknown`() {
        val est = ArDebugReport.build(input(f = fix.copy(hAccM = null, gateAccM = 3.25, sats = null, ageMs = null)))
        assertTrue(est, line(est, "GNSS").endsWith("RS2+ FIX ? sats ~3.2m (HDOP est.)") || line(est, "GNSS").endsWith("RS2+ FIX ? sats ~3.3m (HDOP est.)"))
        val unknown = ArDebugReport.build(input(f = fix.copy(hAccM = null, gateAccM = null)))
        assertTrue(unknown, line(unknown, "GNSS").endsWith("±? age 1.5s"))
        val bad = ArDebugReport.build(input(f = fix.copy(hAccM = null, gateAccM = 30.0)))
        assertTrue(bad, line(bad, "GNSS").startsWith("✗ GNSS"))
    }

    @Test fun `optics line shows FOVs and visible fraction`() {
        val o = ArDebugReport.Optics(640, 480, 60.0, 45.0, 60.0, 45.0)
        assertEquals(1.0, o.shownH, 1e-12)
        assertEquals(1.0, o.shownV, 1e-12)
        val text = ArDebugReport.build(input(optics = o))
        assertTrue(text, text.contains("· Optics   tex 640x480 · cam FOV 60°×45° · screen 60°×45° (shows 100%×100% of image)"))
    }

    @Test fun `AR vs GNSS line grades and shows expected error at the farthest model`() {
        val good = ArVsGnss(0.3, 90.0, 0.12, 0.5, 2.0, 50.0, 1.8, 0.0)
        val text = ArDebugReport.build(input(arVsGnss = good, gateProfile = "RTK (h≤0.1m)"))
        assertTrue(text, text.contains("✓ AR↔GNSS  H 0.30m → 90° V +0.12m  GOOD"))
        assertTrue(text, text.contains("expected error at 50m point ≈ ±1.8m (AR H±0.5m yaw±2°)"))
        assertTrue(text, text.contains("· Gate     RTK (h≤0.1m)"))

        val fair = ArDebugReport.build(input(arVsGnss = ArVsGnss(1.0, 0.0, null, null, null, null, null, 1.2)))
        assertTrue(fair, fair.contains("… AR↔GNSS  H 1.00m → 0°  FAIR  (moving — stand still to judge)"))
        assertFalse(fair, fair.contains("expected error"))

        val poor = ArDebugReport.build(input(arVsGnss = ArVsGnss(5.0, 180.0, null, null, null, null, null, null)))
        assertTrue(poor, poor.contains("✗ AR↔GNSS  H 5.00m → 180°  POOR"))
    }

    @Test fun `anchor marks and gate text`() {
        val placed = ArDebugReport.build(input(items = 2, created = true, anchors = listOf(anchor("a"), anchor("b"))))
        assertTrue(placed, line(placed, "Anchors").startsWith("✓ Anchors  2 coords (0 w/ model) · gate: placed · tracking 2/2"))

        val empty = ArDebugReport.build(input(items = 2, created = true))
        assertTrue(empty, line(empty, "Anchors").startsWith("✗ Anchors"))

        val awaiting = ArDebugReport.build(input(items = 1, awaiting = true))
        assertTrue(awaiting, line(awaiting, "Anchors").startsWith("… Anchors") && awaiting.contains("gate: waiting for AR accuracy"))

        val waiting = ArDebugReport.build(input(items = 1))
        assertTrue(waiting, waiting.contains("gate: waiting ·"))
    }

    @Test fun `awaiting localization blocker shows AR accuracy`() {
        val b = ArDebugReport.nextBlocker(input(items = 1, awaiting = true))!!
        assertTrue(b, b.startsWith("Waiting for AR accuracy (H±? yaw±?)"))
    }

    @Test fun `test grid lines and blockers`() {
        val errGrid = ArDebugReport.TestGridInfo(25, 0, mapOf("ERROR_INTERNAL" to 25), "TERRAIN")
        assertEquals("Test terrain anchors ERROR_INTERNAL — ARCore internal error; often bad input coords",
            ArDebugReport.nextBlocker(input(grid = errGrid)))
        assertTrue(ArDebugReport.build(input(grid = errGrid)).contains("✗ Test     TERRAIN · tracking 0/25  [ERROR_INTERNAL×25]"))

        val resolving = ArDebugReport.TestGridInfo(25, 0, mapOf("TASK_IN_PROGRESS" to 25), "TERRAIN")
        assertEquals("Test anchors resolving terrain (TASK_IN_PROGRESS) — keep scanning", ArDebugReport.nextBlocker(input(grid = resolving)))
        assertTrue(ArDebugReport.build(input(grid = resolving)).contains("… Test     TERRAIN"))

        val stored = ArDebugReport.TestGridInfo(9, 0, mapOf("NONE" to 9), "STORED")
        assertEquals("Test anchors not tracking yet — keep scanning", ArDebugReport.nextBlocker(input(grid = stored)))
        val storedText = ArDebugReport.build(input(grid = stored))
        assertTrue(storedText, line(storedText, "Test").endsWith("STORED · tracking 0/9"))

        val done = ArDebugReport.TestGridInfo(9, 9, emptyMap(), null)
        assertNull(ArDebugReport.nextBlocker(input(grid = done)))
        assertTrue(ArDebugReport.build(input(grid = done)).contains("✓ Test     ? · tracking 9/9"))

        assertTrue(ArDebugReport.build(input(pending = true)).contains("… Test     requested — waiting for Earth TRACKING + valid fix"))
    }

    @Test fun `terrain anchor error and none-tracking blockers`() {
        val err = input(items = 1, created = true, anchors = listOf(anchor("p", "PAUSED", "ERROR_UNSUPPORTED_LOCATION")))
        assertEquals("Terrain anchor ERROR_UNSUPPORTED_LOCATION — no terrain data at this location (bad lat/lon?)",
            ArDebugReport.nextBlocker(err))
        val paused = input(items = 1, created = true, anchors = listOf(anchor("p", "PAUSED")))
        assertEquals("Anchors created but none tracking yet — keep scanning", ArDebugReport.nextBlocker(paused))
    }

    @Test fun `models loading blocker and render marks`() {
        val loading = input(items = 1, created = true, anchors = listOf(anchor("a")), inScene = 1, poses = 3)
        assertEquals("Models still loading (1/3)", ArDebugReport.nextBlocker(loading))
        assertTrue(ArDebugReport.build(loading).contains("… Render   models in scene 1/3"))
        val done = input(items = 1, created = true, anchors = listOf(anchor("a")), inScene = 3, poses = 3)
        assertNull(ArDebugReport.nextBlocker(done))
        val text = ArDebugReport.build(done)
        assertTrue(text, text.startsWith("▶ NEXT: no blockers — pins should be visible"))
        assertTrue(text, text.contains("✓ Render   models in scene 3/3"))
        assertTrue(ArDebugReport.build(input()).contains("· Render   models in scene 0/0"))
    }

    @Test fun `camera line and anchor rows with overflow`() {
        val rows = (0 until 7).map { anchor("Anchor-with-a-very-long-name-$it", dist = if (it == 1) null else 12.34) } +
            anchor("terrain", terrain = "SUCCESS", dist = -1.0)
        val text = ArDebugReport.build(input(items = 8, created = true, anchors = rows, camLat = 41.3, camAlt = 300.04, camHeading = 89.6))
        assertTrue(text, text.contains("📍 Cam 41.3000000, -76.0000000  alt 300.0m  hdg 90°"))
        assertTrue(text, text.contains("  [0] Anchor-with-a-very  TRACKING  12.3m @ 45°  PIN"))
        assertTrue(text, text.contains("  [1] Anchor-with-a-very  TRACKING  ?m  PIN"))
        assertFalse(text, text.contains("[5]"))
        assertTrue(text, text.endsWith("  … +3 more"))

        val noExtras = ArDebugReport.build(input(camLat = 41.3))
        assertTrue(noExtras, noExtras.lines().any { it == "📍 Cam 41.3000000, -76.0000000" })
    }
}
