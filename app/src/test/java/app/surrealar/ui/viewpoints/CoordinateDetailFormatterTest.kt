package app.surrealar.ui.viewpoints

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals("Fixed (RTK)", CoordinateDetailFormatter.rtkLabel("fix"))
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
}
