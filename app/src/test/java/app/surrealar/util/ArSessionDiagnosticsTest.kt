package app.surrealar.util

import app.surrealar.util.ArSessionDiagnostics.ModelStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests the AR/model session diagnostics holder that backs `ar-session-summary.txt` and the
 * per-model `lastArStatus` column of `model-integrity.txt`. Protects the "labels show but models
 * don't" troubleshooting path from silently losing per-model state.
 */
class ArSessionDiagnosticsTest {

    @Before fun reset() = ArSessionDiagnostics.startSession()

    @Test fun `startSession marks a session open and clears prior state`() {
        ArSessionDiagnostics.recordModel("c1", "P1", "m1", "a.glb", true, 10, ModelStatus.QUEUED)
        ArSessionDiagnostics.startSession()
        assertTrue(ArSessionDiagnostics.hasSession())
        assertTrue(ArSessionDiagnostics.openedAtMs > 0)
        assertEquals(0, ArSessionDiagnostics.models().size)
        assertEquals(0, ArSessionDiagnostics.coordinateCount)
    }

    @Test fun `recordModel and counters are reflected in models and summary`() {
        ArSessionDiagnostics.coordinateCount = 12
        ArSessionDiagnostics.linkedModelCount = 2
        ArSessionDiagnostics.recordModel("c1", "Valve", "m1", "Valve.glb", true, 123, ModelStatus.QUEUED)
        ArSessionDiagnostics.recordModel("c2", "Pump", "m2", "Pump.glb", true, 456, ModelStatus.IN_SCENE)
        assertEquals(2, ArSessionDiagnostics.models().size)
        val text = ArSessionDiagnostics.buildSummaryText()
        assertTrue(text.contains("coordinatesReceived  : 12"))
        assertTrue(text.contains("coordinatesWithModel : 2"))
        assertTrue(text.contains("inScene  : 1"))
        assertTrue(text.contains("queued   : 1"))
        assertTrue(text.contains("name=\"Valve\""))
    }

    @Test fun `setStatus advances a queued model through to in-scene`() {
        ArSessionDiagnostics.recordModel("c1", "P", "m1", "a.glb", true, 1, ModelStatus.QUEUED)
        ArSessionDiagnostics.setStatus("c1", ModelStatus.READY)
        ArSessionDiagnostics.setStatus("c1", ModelStatus.IN_SCENE)
        assertEquals(ModelStatus.IN_SCENE, ArSessionDiagnostics.models().single().status)
    }

    @Test fun `terminal IN_SCENE is not downgraded by a later lower-quality status`() {
        ArSessionDiagnostics.recordModel("c1", "P", "m1", "a.glb", true, 1, ModelStatus.IN_SCENE)
        ArSessionDiagnostics.setStatus("c1", ModelStatus.SKIPPED, "distance_filter")
        assertEquals(ModelStatus.IN_SCENE, ArSessionDiagnostics.models().single().status)
    }

    @Test fun `terminal FAILED is not overwritten by an intermediate status`() {
        ArSessionDiagnostics.recordModel("c1", "P", "m1", "a.glb", true, 1, ModelStatus.LOADING)
        ArSessionDiagnostics.setStatus("c1", ModelStatus.FAILED, "parse_failed")
        ArSessionDiagnostics.setStatus("c1", ModelStatus.READY)   // must be ignored
        val e = ArSessionDiagnostics.models().single()
        assertEquals(ModelStatus.FAILED, e.status)
        assertEquals("parse_failed", e.reason)
    }

    @Test fun `setStatus on unknown coordinate is a safe no-op`() {
        ArSessionDiagnostics.setStatus("nope", ModelStatus.IN_SCENE)   // does not throw
        assertEquals(0, ArSessionDiagnostics.models().size)
    }

    @Test fun `reasonCounts aggregates skipped and failed reasons`() {
        ArSessionDiagnostics.recordModel("c1", "P", "m1", "a.glb", false, -1, ModelStatus.SKIPPED, "missing_file")
        ArSessionDiagnostics.recordModel("c2", "Q", "m2", "b.glb", true, 1, ModelStatus.SKIPPED, "distance_filter")
        ArSessionDiagnostics.recordModel("c3", "R", "m3", "c.glb", true, 1, ModelStatus.FAILED, "parse_failed")
        val counts = ArSessionDiagnostics.reasonCounts()
        assertEquals(1, counts["missing_file"])
        assertEquals(1, counts["distance_filter"])
        assertEquals(1, counts["parse_failed"])
    }

    @Test fun `statusForModelId returns the most successful status across coordinates`() {
        // Same model linked to two coordinates: one in scene, one anchor-failed → report success.
        ArSessionDiagnostics.recordModel("c1", "A", "m1", "a.glb", true, 1, ModelStatus.FAILED, "anchor_failed")
        ArSessionDiagnostics.recordModel("c2", "B", "m1", "a.glb", true, 1, ModelStatus.IN_SCENE)
        assertEquals(ModelStatus.IN_SCENE, ArSessionDiagnostics.statusForModelId("m1")!!.status)
    }

    @Test fun `endSession finalizes a loaded-but-not-shown model as not-rendered when earth tracked`() {
        // Preloaded + anchor tracked but never selected/visible → NOT_RENDERED (distinct from
        // ANCHOR_PENDING, so the summary is not misleading about why the model didn't appear).
        ArSessionDiagnostics.linkedModelCount = 1
        ArSessionDiagnostics.lastEarthTrackingState = "TRACKING"
        ArSessionDiagnostics.recordModel("c1", "P", "m1", "a.glb", true, 1, ModelStatus.READY)
        ArSessionDiagnostics.endSession()
        val e = ArSessionDiagnostics.models().single()
        assertEquals(ModelStatus.NOT_RENDERED, e.status)
        assertEquals("not_selected_visible", e.reason)
        assertTrue(ArSessionDiagnostics.closedAtMs > 0)
    }

    @Test fun `endSession finalizes a loaded model as anchor pending when earth never tracked`() {
        ArSessionDiagnostics.linkedModelCount = 1
        ArSessionDiagnostics.lastEarthTrackingState = null   // never reached TRACKING
        ArSessionDiagnostics.recordModel("c1", "P", "m1", "a.glb", true, 1, ModelStatus.READY)
        ArSessionDiagnostics.endSession()
        val e = ArSessionDiagnostics.models().single()
        assertEquals(ModelStatus.ANCHOR_PENDING, e.status)
        assertEquals("earth_not_tracking", e.reason)
        assertTrue(ArSessionDiagnostics.closedAtMs > 0)
    }

    @Test fun `summary warns when labels rendered but no model reached the scene`() {
        ArSessionDiagnostics.linkedModelCount = 1
        ArSessionDiagnostics.labelsShown = 3
        ArSessionDiagnostics.lastEarthTrackingState = "TRACKING"
        ArSessionDiagnostics.recordModel("c1", "P", "m1", "a.glb", true, 1, ModelStatus.FAILED, "filament_asset_failed")
        val text = ArSessionDiagnostics.buildSummaryText()
        assertTrue(text.contains("labels rendered but NO models reached the scene"))
        assertTrue(text.contains("FAILED"))
    }

    @Test fun `summary warns when earth tracking never reached TRACKING`() {
        ArSessionDiagnostics.linkedModelCount = 1
        ArSessionDiagnostics.lastEarthTrackingState = null   // never tracked
        ArSessionDiagnostics.recordModel("c1", "P", "m1", "a.glb", true, 1, ModelStatus.QUEUED)
        val text = ArSessionDiagnostics.buildSummaryText()
        assertTrue(text.contains("Earth tracking never reached TRACKING"))
    }

    @Test fun `empty session summary is clear and safe`() {
        val text = ArSessionDiagnostics.buildSummaryText()
        assertTrue(text.contains("(no model-linked coordinates considered)"))
        assertTrue(text.contains("--- warnings ---"))
        assertFalse(text.contains("name=\""))   // no per-model rows
    }
}
