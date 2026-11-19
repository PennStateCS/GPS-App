package app.surrealar.ui.openinar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArDebugReportTest {

    private val goodFix = ArDebugReport.FixInfo(
        provider = "RS2+", rtk = "FIX", sats = 20, hAccM = 0.02, gateAccM = 0.02,
        ageMs = 200, lat = 41.3477735, lon = -76.0225155,
    )

    private fun input(
        geospatial: Boolean = true,
        camera: String? = "TRACKING",
        cameraReason: String? = "NONE",
        earth: String? = "TRACKING",
        earthState: String? = "ENABLED",
        fix: ArDebugReport.FixInfo? = goodFix,
        items: Int = 0,
        created: Boolean = false,
        awaiting: Boolean = false,
        anchors: List<ArDebugReport.AnchorRow> = emptyList(),
        pending: Boolean = false,
        grid: ArDebugReport.TestGridInfo? = null,
        inScene: Int = 0,
        poses: Int = 0,
    ) = ArDebugReport.Input(
        geospatialEnabled = geospatial, depthEnabled = true,
        cameraTracking = camera, cameraFailureReason = cameraReason,
        featuresCounted = true, planes = 2, points = 300,
        earthTracking = earth, earthState = earthState,
        earthHAccM = 3.0, earthVAccM = 2.0, earthYawDeg = 10.0,
        camLat = 41.3, camLon = -76.0, camAlt = 300.0, camHeading = 90.0,
        fix = fix, gnssGateMaxM = 20.0,
        items = items, itemsWithModels = 0,
        geoAnchorsCreated = created, awaitingLocalization = awaiting,
        geoAnchors = anchors, testSpawnPending = pending, testGrid = grid,
        modelsInScene = inScene, modelPoses = poses,
    )

    @Test fun `geospatial disabled is the first blocker`() {
        assertTrue(ArDebugReport.nextBlocker(input(geospatial = false, camera = "PAUSED"))!!.startsWith("Geospatial"))
    }

    @Test fun `earth error state gives API key hint`() {
        val b = ArDebugReport.nextBlocker(input(earthState = "ERROR_NOT_AUTHORIZED"))!!
        assertTrue(b, b.contains("API_KEY"))
    }

    @Test fun `camera failure reason is surfaced with a hint`() {
        val b = ArDebugReport.nextBlocker(input(camera = "PAUSED", cameraReason = "INSUFFICIENT_FEATURES"))!!
        assertTrue(b, b.contains("INSUFFICIENT_FEATURES") && b.contains("textured"))
    }

    @Test fun `earth not tracking blocks before GNSS`() {
        assertTrue(ArDebugReport.nextBlocker(input(earth = "PAUSED", fix = null))!!.startsWith("Earth localizing"))
    }

    @Test fun `invalid fix coordinates are called out (lon -760 bug)`() {
        val b = ArDebugReport.nextBlocker(input(fix = goodFix.copy(lon = -760.0225155), pending = true))!!
        assertTrue(b, b.contains("invalid coords"))
        assertTrue(ArDebugReport.build(input(fix = goodFix.copy(lon = -760.0225155))).contains("INVALID COORDS"))
    }

    @Test fun `nothing to show suggests the test grid`() {
        assertTrue(ArDebugReport.nextBlocker(input())!!.contains("tap Test"))
    }

    @Test fun `pending test spawn is reported`() {
        assertTrue(ArDebugReport.nextBlocker(input(pending = true))!!.contains("waiting to spawn"))
    }

    @Test fun `gnss accuracy gate blocks coordinate anchors`() {
        val b = ArDebugReport.nextBlocker(input(items = 3, fix = goodFix.copy(hAccM = null, gateAccM = 36.0)))!!
        assertTrue(b, b.contains("36.0m"))
    }

    @Test fun `awaiting AR localization is reported`() {
        assertTrue(ArDebugReport.nextBlocker(input(items = 3, awaiting = true))!!.startsWith("Waiting for AR accuracy"))
    }

    @Test fun `terrain anchor error on test grid is reported`() {
        val grid = ArDebugReport.TestGridInfo(25, 0, mapOf("ERROR_UNSUPPORTED_LOCATION" to 25))
        val b = ArDebugReport.nextBlocker(input(grid = grid))!!
        assertTrue(b, b.contains("ERROR_UNSUPPORTED_LOCATION"))
    }

    @Test fun `test grid resolving is reported`() {
        val grid = ArDebugReport.TestGridInfo(25, 0, mapOf("TASK_IN_PROGRESS" to 25))
        assertTrue(ArDebugReport.nextBlocker(input(grid = grid))!!.contains("resolving"))
    }

    @Test fun `test grid line shows altitude mode and hides NONE terrain state`() {
        val stored = ArDebugReport.TestGridInfo(25, 25, mapOf("NONE" to 25), mode = "STORED")
        val text = ArDebugReport.build(input(grid = stored))
        assertTrue(text, text.contains("Test     STORED · tracking 25/25"))
        assertTrue(text, !text.contains("NONE×"))
        val terrain = ArDebugReport.TestGridInfo(25, 0, mapOf("TASK_IN_PROGRESS" to 25), mode = "TERRAIN")
        assertTrue(ArDebugReport.build(input(grid = terrain)).contains("TERRAIN · tracking 0/25  [TASK_IN_PROGRESS×25]"))
    }

    @Test fun `stored-mode grid not tracking does not mention terrain`() {
        val stored = ArDebugReport.TestGridInfo(25, 0, mapOf("NONE" to 25), mode = "STORED")
        assertEquals("Test anchors not tracking yet — keep scanning", ArDebugReport.nextBlocker(input(grid = stored)))
    }

    @Test fun `all tracking and loaded means no blocker`() {
        val grid = ArDebugReport.TestGridInfo(25, 25, mapOf("SUCCESS" to 25))
        assertNull(ArDebugReport.nextBlocker(input(grid = grid, inScene = 25, poses = 25)))
        assertTrue(ArDebugReport.build(input(grid = grid)).startsWith("▶ NEXT: no blockers"))
    }

    @Test fun `anchor rows are capped`() {
        val rows = (1..8).map { ArDebugReport.AnchorRow("P$it", "TRACKING", null, 5.0, 0.0, "PIN") }
        val text = ArDebugReport.build(input(items = 8, created = true, anchors = rows))
        assertEquals(ArDebugReport.MAX_ANCHOR_ROWS, Regex("""\[\d]""").findAll(text).count())
        assertTrue(text.contains("+3 more"))
    }
}
