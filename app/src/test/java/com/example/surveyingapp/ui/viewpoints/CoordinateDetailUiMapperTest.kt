package com.example.surveyingapp.ui.viewpoints

import com.example.surveyingapp.domain.model.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoordinateDetailUiMapperTest {

    private fun coord(
        provider: String = "fused",
        captureMethod: String? = null,
        rtkStatus: String? = null,
        horizontalAccuracyM: Double? = null,
        averagedSamples: Int? = null,
        icon: String = "pin"
    ) = Coordinate(
        id = "1", name = "P", latitude = 41.0, longitude = -75.0, altitude = 0.0,
        timestamp = 0L, icon = icon, color = 0,
        provider = provider, captureMethod = captureMethod, rtkStatus = rtkStatus,
        horizontalAccuracyM = horizontalAccuracyM, averagedSamples = averagedSamples
    )

    // ── Source badge (always visible) ─────────────────────────────────────────

    @Test
    fun sourceBadge_internalGps() {
        assertEquals("INTERNAL GPS", CoordinateDetailUiMapper.sourceBadge(coord(provider = "fused")).text)
    }

    @Test
    fun sourceBadge_externalRs2() {
        assertEquals("RS2+", CoordinateDetailUiMapper.sourceBadge(coord(provider = "rs2-tcp")).text)
        assertEquals("RS2+", CoordinateDetailUiMapper.sourceBadge(coord(captureMethod = "external_gnss")).text)
    }

    @Test
    fun sourceBadge_modelImportedManual() {
        assertEquals("MODEL EMBEDDED LOCATION", CoordinateDetailUiMapper.sourceBadge(coord(captureMethod = "model_embedded")).text)
        assertEquals("IMPORTED", CoordinateDetailUiMapper.sourceBadge(coord(captureMethod = "imported")).text)
        assertEquals("MANUAL", CoordinateDetailUiMapper.sourceBadge(coord(captureMethod = "map_tap")).text)
    }

    // ── Fix badge ─────────────────────────────────────────────────────────────

    @Test
    fun fixBadge_externalRtkFixed() {
        val b = CoordinateDetailUiMapper.fixBadge(coord(provider = "rs2-tcp", rtkStatus = "FIX"))!!
        assertEquals("FIXED", b.text)
        assertEquals(0xFF2E7D32.toInt(), b.colorArgb)
        assertEquals("RTK fixed solution", b.contentDescription)
    }

    @Test
    fun fixBadge_externalRtkFloat() {
        val b = CoordinateDetailUiMapper.fixBadge(coord(provider = "rs2-tcp", rtkStatus = "float"))!!
        assertEquals("FLOAT", b.text)
        assertEquals("RTK float solution", b.contentDescription)
    }

    @Test
    fun fixBadge_internalSingle() {
        assertEquals("SINGLE", CoordinateDetailUiMapper.fixBadge(coord(rtkStatus = "SINGLE"))!!.text)
    }

    @Test
    fun fixBadge_hiddenForNonGnssOrMissingStatus() {
        assertNull("hidden for manual capture", CoordinateDetailUiMapper.fixBadge(coord(captureMethod = "manual", rtkStatus = "FIX")))
        assertNull("hidden when no rtk status", CoordinateDetailUiMapper.fixBadge(coord(rtkStatus = null)))
    }

    // ── Extra badge ────────────────────────────────────────────────────────────

    @Test
    fun extraBadge_averagedTakesPrecedence() {
        assertEquals("AVERAGED", CoordinateDetailUiMapper.extraBadge(coord(averagedSamples = 5, icon = "model:abc"))!!.text)
    }

    @Test
    fun extraBadge_modelLinked() {
        assertEquals("MODEL LINKED", CoordinateDetailUiMapper.extraBadge(coord(icon = "model:abc"))!!.text)
    }

    @Test
    fun extraBadge_hiddenWhenNeither() {
        assertNull(CoordinateDetailUiMapper.extraBadge(coord(icon = "pin", averagedSamples = 0)))
    }

    // ── Accuracy badge ─────────────────────────────────────────────────────────

    @Test
    fun accuracyBadge_surveyGradeColorAndText() {
        val b = CoordinateDetailUiMapper.accuracyBadge(coord(horizontalAccuracyM = 0.02), showAccuracyIndicators = true)!!
        assertEquals("H ±0.02 m", b.text)
        assertEquals(0xFF2E7D32.toInt(), b.colorArgb)
        assertEquals("Horizontal accuracy plus or minus 0.02 meters", b.contentDescription)
    }

    @Test
    fun accuracyBadge_hiddenWhenMissingAccuracy() {
        assertNull(CoordinateDetailUiMapper.accuracyBadge(coord(horizontalAccuracyM = null), showAccuracyIndicators = true))
    }

    @Test
    fun accuracyBadge_hiddenWhenIndicatorsDisabled() {
        assertNull(CoordinateDetailUiMapper.accuracyBadge(coord(horizontalAccuracyM = 0.5), showAccuracyIndicators = false))
    }

    // ── Aggregate ──────────────────────────────────────────────────────────────

    @Test
    fun badges_internalGpsNoAccuracy_onlySourceVisible() {
        val b = CoordinateDetailUiMapper.badges(coord(provider = "fused"), showAccuracyIndicators = true)
        assertEquals("INTERNAL GPS", b.source?.text)
        assertNull(b.fix)
        assertNull(b.extra)
        assertNull(b.accuracy)
        assertEquals(true, b.anyVisible)
    }

    @Test
    fun badges_externalRtkFixedAveragedWithAccuracy_allVisible() {
        val c = coord(provider = "rs2-tcp", captureMethod = "external_gnss", rtkStatus = "FIX",
            horizontalAccuracyM = 0.03, averagedSamples = 10)
        val b = CoordinateDetailUiMapper.badges(c, showAccuracyIndicators = true)
        assertEquals("RS2+", b.source?.text)
        assertEquals("FIXED", b.fix?.text)
        assertEquals("AVERAGED", b.extra?.text)
        assertEquals("H ±0.03 m", b.accuracy?.text)
        assertEquals(true, b.anyVisible)
    }
}
