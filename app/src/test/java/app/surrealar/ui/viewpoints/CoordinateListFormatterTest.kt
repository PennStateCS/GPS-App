package app.surrealar.ui.viewpoints

import app.surrealar.domain.model.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateListFormatterTest {

    private fun coord(
        lat: Double = 40.123456,
        lon: Double = -74.123456,
        provider: String = "other",
        captureMethod: String? = null,
        rtkStatus: String? = null,
        hAcc: Double? = null,
        sourceDevice: String? = null,
        modelId: String? = null,
    ) = Coordinate(
        id = "c1", name = "Point 104", latitude = lat, longitude = lon, altitude = 100.0,
        timestamp = 1_000L, icon = "ic_pin", color = 0,
        provider = provider, rtkStatus = rtkStatus, horizontalAccuracyM = hAcc,
        captureMethod = captureMethod, sourceDevice = sourceDevice, modelId = modelId,
    )

    @Test fun externalRtk_showsSourceFixAccuracy() {
        val c = coord(provider = "rs2-external", rtkStatus = "FIX", hAcc = 0.03, sourceDevice = "Reach6")
        assertEquals("Reach6 · Fixed · H ±0.03 m", CoordinateListFormatter.summaryLine(c, null))
    }

    @Test fun externalRtk_defaultsToRs2_whenNoDeviceName() {
        val c = coord(captureMethod = "external_gnss", rtkStatus = "FLOAT", hAcc = 0.12)
        assertEquals("RS2+ · Float · H ±0.12 m", CoordinateListFormatter.summaryLine(c, null))
    }

    @Test fun modelLinked_usesModelNameOrFallback() {
        val c = coord(modelId = "m1", rtkStatus = "FIX", hAcc = 0.03)
        assertEquals("Barn · Fixed · H ±0.03 m", CoordinateListFormatter.summaryLine(c, "Barn"))
        assertEquals("Model linked · Fixed · H ±0.03 m", CoordinateListFormatter.summaryLine(c, null))
    }

    @Test fun internalGps_sourceOnly_whenNoFixOrAccuracy() {
        val c = coord(captureMethod = "internal_gps")
        assertEquals("Internal GPS", CoordinateListFormatter.summaryLine(c, null))
    }

    @Test fun omitsAccuracyWhenAbsent() {
        val c = coord(provider = "rs2-external", rtkStatus = "FIX")
        assertEquals("RS2+ · Fixed", CoordinateListFormatter.summaryLine(c, null))
    }

    @Test fun latLonLine_sixDecimals() {
        assertEquals("40.123456, -74.123456", CoordinateListFormatter.latLonLine(coord()))
    }
}
