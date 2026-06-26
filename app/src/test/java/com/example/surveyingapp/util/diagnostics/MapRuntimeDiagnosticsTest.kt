package com.example.surveyingapp.util.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapRuntimeDiagnosticsTest {

    @Test
    fun `grid summary formats without coordinates`() {
        assertEquals(
            "mode=Auto spacing=10 m lines=42 zoom=19.2",
            MapRuntimeDiagnostics.gridSummary("Auto", "10 m", 42, 19.2f)
        )
        assertEquals(
            "mode=Off spacing=- lines=0 zoom=2.0",
            MapRuntimeDiagnostics.gridSummary("Off", null, 0, 2.0f)
        )
    }

    @Test
    fun `labels summary includes reason only when present`() {
        assertEquals(
            "mode=Distance labeled=12 skipped=6 reason=noLiveFix",
            MapRuntimeDiagnostics.labelsSummary("Distance", 12, 6, "noLiveFix")
        )
        assertEquals(
            "mode=Name labeled=18 skipped=0",
            MapRuntimeDiagnostics.labelsSummary("Name", 18, 0, null)
        )
    }

    @Test
    fun `snapshot format includes status fields and computes hidden markers`() {
        val text = MapDiagSnapshot(
            mapReady = true, mapLoaded = true, mapType = "Hybrid",
            gridMode = "Auto", gridSpacing = "10 m", pointLabelMode = "Distance",
            markersTotal = 18, markersVisible = 12, stakeoutActive = true,
        ).format()
        assertTrue(text.contains("Map ready               : yes"))
        assertTrue(text.contains("Map type                : Hybrid"))
        assertTrue(text.contains("Grid mode               : Auto (10 m)"))
        assertTrue(text.contains("Point label mode        : Distance"))
        assertTrue(text.contains("Markers total/vis/hidden: 18 / 12 / 6"))
        assertTrue(text.contains("Stakeout active         : yes"))
    }

    @Test
    fun `snapshot format never contains latitude or longitude tokens`() {
        val text = MapDiagSnapshot(mapReady = true, markersTotal = 5, markersVisible = 5).format().lowercase()
        // Privacy: only counts/modes/booleans are stored, so no coordinate words should appear.
        assertFalse(text.contains("latitude"))
        assertFalse(text.contains("longitude"))
    }

    @Test
    fun `holder update and clear work`() {
        MapRuntimeDiagnostics.update { it.copy(mapType = "Terrain", markersTotal = 9) }
        assertEquals("Terrain", MapRuntimeDiagnostics.snapshot.mapType)
        assertEquals(9, MapRuntimeDiagnostics.snapshot.markersTotal)
        MapRuntimeDiagnostics.clear()
        assertEquals("Normal", MapRuntimeDiagnostics.snapshot.mapType)
        assertEquals(0, MapRuntimeDiagnostics.snapshot.markersTotal)
    }
}
