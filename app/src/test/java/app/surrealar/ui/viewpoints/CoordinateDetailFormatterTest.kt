package app.surrealar.ui.viewpoints

import app.surrealar.settings.model.VerticalReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateDetailFormatterTest {

    @Test
    fun numberFormatters_useUsLocaleAndFixedPrecision() {
        assertEquals("40.712800", CoordinateDetailFormatter.fmt6(40.7128))
        assertEquals("1.23 m", CoordinateDetailFormatter.fmtM2(1.234))
        assertEquals("1.235 m", CoordinateDetailFormatter.fmtM3(1.2345))
        assertEquals("0.9", CoordinateDetailFormatter.fmtDop(0.94))
    }

    @Test
    fun rtkLabel_mapsKnownStatusesCaseInsensitively() {
        assertEquals("Fix (RTK)", CoordinateDetailFormatter.rtkLabel("fix"))
        assertEquals("Float (RTK)", CoordinateDetailFormatter.rtkLabel("FLOAT"))
        assertEquals("DGPS", CoordinateDetailFormatter.rtkLabel("dgps"))
        assertEquals("Single", CoordinateDetailFormatter.rtkLabel("Single"))
        assertEquals("WEIRD", CoordinateDetailFormatter.rtkLabel("WEIRD")) // passthrough
        assertEquals("--", CoordinateDetailFormatter.rtkLabel(null))
    }

    @Test
    fun captureMethodLabel_mapsKnownMethods_andNullsOnBlank() {
        assertEquals("Internal GPS", CoordinateDetailFormatter.captureMethodLabel("internal_gps"))
        assertEquals("Model embedded location", CoordinateDetailFormatter.captureMethodLabel("model_embedded"))
        assertEquals("Map tap", CoordinateDetailFormatter.captureMethodLabel("MAP_TAP"))
        assertNull(CoordinateDetailFormatter.captureMethodLabel(null))
        assertNull(CoordinateDetailFormatter.captureMethodLabel(""))
        assertEquals("custom", CoordinateDetailFormatter.captureMethodLabel("custom")) // passthrough
    }

    @Test
    fun providerLabel_mapsKnownProviders_andNullsOnOther() {
        assertEquals("Internal GPS (fused)", CoordinateDetailFormatter.providerLabel("fused"))
        assertEquals("External GNSS (TCP)", CoordinateDetailFormatter.providerLabel("rs2-tcp"))
        assertEquals("External GNSS (Bluetooth)", CoordinateDetailFormatter.providerLabel("rs2-bt"))
        assertNull(CoordinateDetailFormatter.providerLabel("other"))
        assertNull(CoordinateDetailFormatter.providerLabel(null))
    }

    @Test
    fun fmtDistance_switchesToKmAtOneThousandMeters() {
        assertEquals("999.0 m", CoordinateDetailFormatter.fmtDistance(999.0))
        assertEquals("1.50 km", CoordinateDetailFormatter.fmtDistance(1500.0))
    }

    @Test
    fun cardinalDir_coversCompassSectors() {
        assertEquals("N", CoordinateDetailFormatter.cardinalDir(0.0))
        assertEquals("N", CoordinateDetailFormatter.cardinalDir(350.0))
        assertEquals("NE", CoordinateDetailFormatter.cardinalDir(45.0))
        assertEquals("E", CoordinateDetailFormatter.cardinalDir(90.0))
        assertEquals("SW", CoordinateDetailFormatter.cardinalDir(225.0))
        assertEquals("NW", CoordinateDetailFormatter.cardinalDir(315.0))
    }

    @Test
    fun accuracyBadgeText_tightensPrecisionForSmallValues() {
        assertEquals("H ±0.42 m", CoordinateDetailFormatter.accuracyBadgeText(0.42))
        assertEquals("H ±3.5 m", CoordinateDetailFormatter.accuracyBadgeText(3.45))
        assertEquals("H ±25 m", CoordinateDetailFormatter.accuracyBadgeText(25.0))
    }

    // ── Calculated display helpers ──────────────────────────────────────────

    @Test
    fun captureRateText_computesRate_andAvoidsDivideByZero() {
        assertEquals("2.5 fixes/sec", CoordinateDetailFormatter.captureRateText(150, 60_000L))
        assertEquals("3.0 fixes/sec", CoordinateDetailFormatter.captureRateText(30, 10_000L))
        assertNull(CoordinateDetailFormatter.captureRateText(null, 60_000L))
        assertNull(CoordinateDetailFormatter.captureRateText(150, null))
        assertNull(CoordinateDetailFormatter.captureRateText(150, 0L))   // no divide-by-zero
    }

    @Test
    fun satelliteSummaryText_handlesUsedVisibleAndEither() {
        assertEquals("18 used / 24 visible", CoordinateDetailFormatter.satelliteSummaryText(18, 24))
        assertEquals("18 used", CoordinateDetailFormatter.satelliteSummaryText(18, null))
        assertEquals("24 visible", CoordinateDetailFormatter.satelliteSummaryText(null, 24))
        assertNull(CoordinateDetailFormatter.satelliteSummaryText(null, null))
    }

    @Test
    fun correctionFreshnessText_marksFreshBelowThreshold() {
        assertEquals("Fresh, 1.2 s old", CoordinateDetailFormatter.correctionFreshnessText(1.2))
        assertEquals("12.5 s old", CoordinateDetailFormatter.correctionFreshnessText(12.5))
        assertNull(CoordinateDetailFormatter.correctionFreshnessText(null))
        assertNull(CoordinateDetailFormatter.correctionFreshnessText(-1.0))
    }

    @Test
    fun surveyQualitySummaryText_coversFixStatusesAndFallbacks() {
        assertEquals("RTK fix · survey grade", CoordinateDetailFormatter.surveyQualitySummaryText("FIX", 0.02, 0.8))
        assertEquals("RTK float · sub-meter", CoordinateDetailFormatter.surveyQualitySummaryText("float", 0.4, 1.0))
        assertEquals("DGPS · meter-level", CoordinateDetailFormatter.surveyQualitySummaryText("DGPS", null, null))
        assertEquals("Single · approximate", CoordinateDetailFormatter.surveyQualitySummaryText("SINGLE", null, null))
        // Unknown status falls back to the accuracy grade.
        assertEquals("Sub-meter", CoordinateDetailFormatter.surveyQualitySummaryText(null, 0.3, null))
        assertEquals("Approximate", CoordinateDetailFormatter.surveyQualitySummaryText("NONE", 5.0, null))
        // Nothing known at all -> null, not "null".
        assertNull(CoordinateDetailFormatter.surveyQualitySummaryText(null, null, null))
    }

    @Test
    fun recordHistory_hidesCreatedWhenSameMinuteAsCaptured() {
        val captured = 1_700_000_000_000L
        val r = CoordinateDetailFormatter.recordHistoryVisibility(captured, captured + 5_000L, captured + 5_000L)
        org.junit.Assert.assertFalse(r.showCreated)   // within same minute as captured
        org.junit.Assert.assertFalse(r.showUpdated)   // same minute as created
        org.junit.Assert.assertFalse(r.anyShown)
    }

    @Test
    fun recordHistory_showsUpdatedWhenMeaningfullyLater() {
        val captured = 1_700_000_000_000L
        val updated = captured + 10 * 60_000L         // 10 minutes later
        val r = CoordinateDetailFormatter.recordHistoryVisibility(captured, captured, updated)
        org.junit.Assert.assertFalse(r.showCreated)   // created == captured minute
        org.junit.Assert.assertTrue(r.showUpdated)
        org.junit.Assert.assertTrue(r.anyShown)
    }

    @Test
    fun recordHistory_showsCreatedAsFallbackWhenNoCaptured() {
        val r = CoordinateDetailFormatter.recordHistoryVisibility(0L, 1_700_000_000_000L, 0L)
        org.junit.Assert.assertTrue(r.showCreated)
        org.junit.Assert.assertFalse(r.showUpdated)
    }

    @Test
    fun recordHistory_hidesUpdatedWhenSameMinuteAsCreated() {
        val created = 1_700_000_000_000L
        val r = CoordinateDetailFormatter.recordHistoryVisibility(created - 60_000L, created, created + 20_000L)
        org.junit.Assert.assertTrue(r.showCreated)    // created is a different minute from captured
        org.junit.Assert.assertFalse(r.showUpdated)   // updated within the same minute as created
    }

    @Test
    fun locationSummary_includesUtmWhenAvailable_andNoNull() {
        val full = app.surrealar.domain.model.Coordinate(
            id = "c", name = "P", latitude = 41.347822, longitude = -76.022615, altitude = 399.10,
            timestamp = 0L, icon = "ic_pin", color = 0,
            easting = 414449.639, northing = 4577874.159, utmZone = "18T",
        )
        val s = CoordinateDetailFormatter.locationSummary(full)
        assertEquals(
            "Latitude: 41.347822°\nLongitude: -76.022615°\nEllipsoidal height h: 399.10 m\n" +
                "UTM: 18T 414449.639 E, 4577874.159 N",
            s
        )
    }

    @Test
    fun locationSummary_omitsUtmWhenAbsent() {
        val noUtm = app.surrealar.domain.model.Coordinate(
            id = "c", name = "P", latitude = 41.0, longitude = -76.0, altitude = 100.0,
            timestamp = 0L, icon = "ic_pin", color = 0,
        )
        assertEquals("Latitude: 41.000000°\nLongitude: -76.000000°\nEllipsoidal height h: 100.00 m",
            CoordinateDetailFormatter.locationSummary(noUtm))
    }

    @Test
    fun locationSummary_fallbackWhenNotFinite() {
        val bad = app.surrealar.domain.model.Coordinate(
            id = "c", name = "P", latitude = Double.NaN, longitude = -76.0, altitude = 100.0,
            timestamp = 0L, icon = "ic_pin", color = 0,
        )
        assertEquals("No saved location details available", CoordinateDetailFormatter.locationSummary(bad))
    }

    @Test
    fun fixShortLabel_mapsKnownStatuses() {
        assertEquals("Fix", CoordinateDetailFormatter.fixShortLabel("FIX"))
        assertEquals("Float", CoordinateDetailFormatter.fixShortLabel("float"))
        assertEquals("DGPS", CoordinateDetailFormatter.fixShortLabel("DGPS"))
        assertEquals("Single", CoordinateDetailFormatter.fixShortLabel("SINGLE"))
        assertNull(CoordinateDetailFormatter.fixShortLabel(null))
        assertNull(CoordinateDetailFormatter.fixShortLabel(""))
    }

    @Test
    fun fixTileValue_addsRtkSuffixForFixed() {
        assertEquals("RTK Fix", CoordinateDetailFormatter.fixTileValue("FIX"))
        assertEquals("Float", CoordinateDetailFormatter.fixTileValue("FLOAT"))
        assertEquals("DGPS", CoordinateDetailFormatter.fixTileValue("dgps"))
        assertNull(CoordinateDetailFormatter.fixTileValue(null))
    }

    @Test
    fun accuracyTileText_tightensPrecision_andNullSafe() {
        assertEquals("±0.42 m", CoordinateDetailFormatter.accuracyTileText(0.42))
        assertEquals("±3.5 m", CoordinateDetailFormatter.accuracyTileText(3.45))
        assertEquals("±25 m", CoordinateDetailFormatter.accuracyTileText(25.0))
        assertNull(CoordinateDetailFormatter.accuracyTileText(null))
    }

    @Test
    fun accuracyTileText_showsConfidence() {
        assertEquals("±0.02 m (68%)",
            CoordinateDetailFormatter.accuracyTileText(0.02, CoordinateDetailFormatter.HORIZONTAL_CONFIDENCE))
        assertEquals("±0.03 m (1σ)",
            CoordinateDetailFormatter.accuracyTileText(0.03, CoordinateDetailFormatter.VERTICAL_CONFIDENCE))
        assertNull(CoordinateDetailFormatter.accuracyTileText(null, "68%"))
    }

    @Test
    fun fmt8_eightDecimals() {
        assertEquals("41.34769028", CoordinateDetailFormatter.fmt8(41.3476902812))
        assertEquals("-76.02259178", CoordinateDetailFormatter.fmt8(-76.022591781))
    }

    @Test
    fun satellitesTileText_compactForms() {
        assertEquals("18/24", CoordinateDetailFormatter.satellitesTileText(18, 24))
        assertEquals("18", CoordinateDetailFormatter.satellitesTileText(18, null))
        assertEquals("24", CoordinateDetailFormatter.satellitesTileText(null, 24))
        assertNull(CoordinateDetailFormatter.satellitesTileText(null, null))
    }

    @Test
    fun internalGpsNote_followsProvider_notAveraging() {
        assertNotNull(CoordinateDetailFormatter.internalGpsNote("fused", "averaged"))
        assertNotNull(CoordinateDetailFormatter.internalGpsNote("fused", "internal_gps"))
        assertNotNull(CoordinateDetailFormatter.internalGpsNote("other", "internal_gps"))
        assertNull(CoordinateDetailFormatter.internalGpsNote("external-tcp", "averaged"))
        assertNull(CoordinateDetailFormatter.internalGpsNote("rs2-external", "averaged"))
        assertNull(CoordinateDetailFormatter.internalGpsNote("other", "manual"))
    }

    @Test
    fun satellitesTileLabel_saysUsedWhenVisibleUnknown() {
        assertEquals("Satellites", CoordinateDetailFormatter.satellitesTileLabel(18, 24))
        assertEquals("Satellites used", CoordinateDetailFormatter.satellitesTileLabel(24, null))
        assertEquals("Satellites", CoordinateDetailFormatter.satellitesTileLabel(null, 24))
    }

    @Test
    fun timeSourceLabel_isHumanReadable() {
        assertEquals("Receiver UTC (ZDA)", CoordinateDetailFormatter.timeSourceLabel("NMEA_ZDA"))
        assertEquals("Device GNSS clock", CoordinateDetailFormatter.timeSourceLabel("GNSS_PROVIDER"))
        assertEquals("Device clock", CoordinateDetailFormatter.timeSourceLabel("DEVICE"))
        assertEquals("Unknown", CoordinateDetailFormatter.timeSourceLabel("UNKNOWN"))
        assertEquals("Unknown", CoordinateDetailFormatter.timeSourceLabel("SOMETHING_ELSE"))
    }

    @Test
    fun modelPlacementSummaryText_includesOnlyPresentFields() {
        assertNull(CoordinateDetailFormatter.modelPlacementSummaryText(null, null, null, null, null, null, null, null))
        assertEquals(
            "scale 1.50× · yaw 90° CCW · v-offset 1.50 m",
            CoordinateDetailFormatter.modelPlacementSummaryText(1.5, 90.0, null, null, 1.5, null, null, null)
        )
        assertEquals(
            "origin (0.10, 0.20, 0.00) m",
            CoordinateDetailFormatter.modelPlacementSummaryText(null, null, null, null, null, 0.1, 0.2, null)
        )
    }

    @Test
    fun locationLabels_useDotSeparator_noNestedBrackets() {
        assertEquals("Lat / Lon · WGS84 (ITRF2014)",
            CoordinateDetailFormatter.qualifiedLabel("Lat / Lon", "WGS84 (ITRF2014)"))
        assertEquals("Height at mark · Orthometric height H (receiver geoid, model unknown)",
            CoordinateDetailFormatter.qualifiedLabel(CoordinateDetailFormatter.HEIGHT_AT_MARK, VerticalReference.RECEIVER_MSL.label))
    }

    @Test
    fun heightLabels_useSurveyTerminology_noMslNoAtGroundClaim() {
        assertEquals("Ellipsoidal height (h)", VerticalReference.ELLIPSOIDAL.label)
        assertEquals("h", VerticalReference.ELLIPSOIDAL.shortLabel)
        assertEquals("Orthometric height H (NAVD88, GEOID18)", VerticalReference.NAVD88_GEOID18.label)
        assertEquals("H GEOID18", VerticalReference.NAVD88_GEOID18.shortLabel)
        assertEquals("Orthometric height H (receiver geoid, model unknown)", VerticalReference.RECEIVER_MSL.label)
        for (v in VerticalReference.entries) {
            assertFalse(v.label, v.label.contains("MSL"))
            assertFalse(v.label, v.label.contains("at ground"))
        }
    }

    @Test
    fun receiverMslRow_hiddenOnlyWhenHeightAlreadyShowsIt() {
        assertFalse(CoordinateDetailFormatter.showReceiverMslRow(VerticalReference.RECEIVER_MSL))
        assertTrue(CoordinateDetailFormatter.showReceiverMslRow(VerticalReference.ELLIPSOIDAL))
        assertTrue(CoordinateDetailFormatter.showReceiverMslRow(VerticalReference.NAVD88_GEOID18))
        assertTrue(CoordinateDetailFormatter.showReceiverMslRow(VerticalReference.GEOID2022))
    }

    @Test
    fun betaHeightModelRow_onlyForBetaHeights_withVersionEpochAndCaution() {
        val base = app.surrealar.coords.ConvertedPosition(
            horizontal = app.surrealar.settings.model.HorizontalSystem.GEOGRAPHIC,
            datum = app.surrealar.settings.model.OutputDatum.WGS84, unit = app.surrealar.settings.model.LinearUnit.METERS,
            latDeg = 41.0, lonDeg = -76.0, easting = null, northing = null, zoneLabel = null,
            vertical = VerticalReference.NAVD88_GEOID18, height = 100.0,
        )
        assertNull(CoordinateDetailFormatter.betaHeightModelText(base))
        val beta = base.copy(vertical = VerticalReference.GEOID2022, verticalLabel = "NAPGD2022 beta",
            heightModelNote = "GEOID2022 (beta beta_v0a) epoch 2026.886")
        val text = CoordinateDetailFormatter.betaHeightModelText(beta)!!
        assertTrue(text, text.startsWith("GEOID2022 (beta beta_v0a) epoch 2026.886"))
        assertTrue(text, text.contains("NAPGD2022"))
        assertTrue(text, text.contains("ITRF2020"))
        assertTrue(text, text.contains("not for authoritative survey results"))
    }

    @Test
    fun receiverMslReference_keepsPrefKeyAndShortLabel() {
        assertEquals("receiver_msl", VerticalReference.RECEIVER_MSL.prefKey)
        assertEquals("H rcvr", VerticalReference.RECEIVER_MSL.shortLabel)
    }
}
