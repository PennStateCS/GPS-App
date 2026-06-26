package com.example.surveyingapp.ui.rendermap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapGridTest {

    // ── mode cycling ──────────────────────────────────────────────────────────────

    @Test
    fun `mode cycles Off Auto Fine Coarse Off`() {
        assertEquals(MapGridMode.AUTO, MapGridMode.OFF.next())
        assertEquals(MapGridMode.FINE, MapGridMode.AUTO.next())
        assertEquals(MapGridMode.COARSE, MapGridMode.FINE.next())
        assertEquals(MapGridMode.OFF, MapGridMode.COARSE.next())
    }

    // ── auto spacing ──────────────────────────────────────────────────────────────

    @Test
    fun `auto spacing targets roughly the requested pixel gap`() {
        // ~0.45 m/px (zoom ~18 at mid latitude): desired ≈ 50 m → nearest nice number is 50.
        assertEquals(50.0, MapGrid.autoSpacingMeters(0.45), 1e-9)
        // Coarse scale: 5 m/px → desired ≈ 550 m → nearest is 500.
        assertEquals(500.0, MapGrid.autoSpacingMeters(5.0), 1e-9)
        // Very fine scale: 0.005 m/px → desired ≈ 0.55 m → clamps to 0.5.
        assertEquals(0.5, MapGrid.autoSpacingMeters(0.005), 1e-9)
    }

    @Test
    fun `auto spacing handles degenerate scale safely`() {
        assertEquals(0.5, MapGrid.autoSpacingMeters(0.0), 1e-9)
        assertEquals(0.5, MapGrid.autoSpacingMeters(Double.NaN), 1e-9)
    }

    // ── fine / coarse relative to auto ──────────────────────────────────────────────

    @Test
    fun `fine is smaller and coarse is larger than auto`() {
        val auto = 10.0
        val fine = MapGrid.spacingForMode(MapGridMode.FINE, auto)!!
        val coarse = MapGrid.spacingForMode(MapGridMode.COARSE, auto)!!
        val autoResolved = MapGrid.spacingForMode(MapGridMode.AUTO, auto)!!
        assertEquals(10.0, autoResolved, 1e-9)
        assertTrue("fine ($fine) < auto ($autoResolved)", fine < autoResolved)
        assertTrue("coarse ($coarse) > auto ($autoResolved)", coarse > autoResolved)
        // Fine = one step down (10 → 5), Coarse = two steps up (10 → 50).
        assertEquals(5.0, fine, 1e-9)
        assertEquals(50.0, coarse, 1e-9)
    }

    @Test
    fun `fine and coarse clamp at the sequence ends`() {
        // Auto already at the smallest: Fine cannot go below 0.5.
        assertEquals(0.5, MapGrid.spacingForMode(MapGridMode.FINE, 0.5)!!, 1e-9)
        // Auto near the top: Coarse clamps to 5 km.
        assertEquals(5000.0, MapGrid.spacingForMode(MapGridMode.COARSE, 5000.0)!!, 1e-9)
    }

    @Test
    fun `off mode has no spacing`() {
        assertNull(MapGrid.spacingForMode(MapGridMode.OFF, 10.0))
    }

    // ── formatting + labels ──────────────────────────────────────────────────────

    @Test
    fun `spacing formats metres and kilometres`() {
        assertEquals("0.5 m", MapGrid.formatSpacing(0.5))
        assertEquals("1 m", MapGrid.formatSpacing(1.0))
        assertEquals("10 m", MapGrid.formatSpacing(10.0))
        assertEquals("500 m", MapGrid.formatSpacing(500.0))
        assertEquals("1 km", MapGrid.formatSpacing(1000.0))
        assertEquals("2 km", MapGrid.formatSpacing(2000.0))
        assertEquals("5 km", MapGrid.formatSpacing(5000.0))
    }

    @Test
    fun `button label shows mode and spacing`() {
        assertEquals("Off", MapGrid.buttonLabel(MapGridMode.OFF, null))
        assertEquals("Auto 10 m", MapGrid.buttonLabel(MapGridMode.AUTO, 10.0))
        assertEquals("Fine 5 m", MapGrid.buttonLabel(MapGridMode.FINE, 5.0))
        assertEquals("Coarse 50 m", MapGrid.buttonLabel(MapGridMode.COARSE, 50.0))
    }

    @Test
    fun `content description states mode and the next action`() {
        assertEquals("Grid off. Tap to switch to auto grid.", MapGrid.contentDescription(MapGridMode.OFF, null))
        assertEquals("Grid auto, 10 m. Tap to switch to fine grid.", MapGrid.contentDescription(MapGridMode.AUTO, 10.0))
        assertEquals("Grid coarse, 50 m. Tap to turn grid off.", MapGrid.contentDescription(MapGridMode.COARSE, 50.0))
    }
}
