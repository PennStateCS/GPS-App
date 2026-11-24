package app.surrealar.ui.viewpoints

import app.surrealar.coords.ConvertedPosition
import app.surrealar.domain.model.Coordinate
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.VerticalReference
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
        assertEquals("Reach6 · Fix · H ±0.03 m", CoordinateListFormatter.summaryLine(c, null))
    }

    @Test fun externalRtk_defaultsToGeneric_whenNoDeviceName() {
        val c = coord(captureMethod = "external_gnss", rtkStatus = "FLOAT", hAcc = 0.12)
        assertEquals("External GNSS · Float · H ±0.12 m", CoordinateListFormatter.summaryLine(c, null))
    }

    @Test fun modelLinked_usesModelNameOrFallback() {
        val c = coord(modelId = "m1", rtkStatus = "FIX", hAcc = 0.03)
        assertEquals("Barn · Fix · H ±0.03 m", CoordinateListFormatter.summaryLine(c, "Barn"))
        assertEquals("Model linked · Fix · H ±0.03 m", CoordinateListFormatter.summaryLine(c, null))
    }

    @Test fun modelLinked_keepsSourceDevice() {
        val c = coord(provider = "external-tcp", rtkStatus = "FIX", hAcc = 0.03, sourceDevice = "Emlid Reach RS2+", modelId = "m1")
        assertEquals("Emlid Reach RS2+ · Fix · H ±0.03 m", CoordinateListFormatter.summaryLine(c, "Barn"))
    }

    private fun pos(vertical: VerticalReference, height: Double?) = ConvertedPosition(
        horizontal = HorizontalSystem.GEOGRAPHIC, datum = OutputDatum.WGS84, unit = LinearUnit.METERS,
        latDeg = 40.0, lonDeg = -76.0, easting = null, northing = null, zoneLabel = null,
        vertical = vertical, height = height,
    )

    @Test fun rowHeight_usesChosenReferenceWhenAvailable() {
        assertEquals("365.120 m H rcvr",
            CoordinateListFormatter.rowHeightText(pos(VerticalReference.RECEIVER_MSL, 365.12)) { error("not needed") })
    }

    @Test fun rowHeight_fallsBackToEllipsoidal() {
        assertEquals("397.000 m h",
            CoordinateListFormatter.rowHeightText(pos(VerticalReference.RECEIVER_MSL, null)) {
                pos(VerticalReference.ELLIPSOIDAL, 397.0)
            })
    }

    @Test fun rowHeight_dashWhenNoHeightAtAll() {
        assertEquals("—", CoordinateListFormatter.rowHeightText(pos(VerticalReference.RECEIVER_MSL, null)) { null })
        assertEquals("—", CoordinateListFormatter.rowHeightText(pos(VerticalReference.RECEIVER_MSL, null)) { error("boom") })
    }

    @Test fun internalGps_sourceOnly_whenNoFixOrAccuracy() {
        val c = coord(captureMethod = "internal_gps")
        assertEquals("Internal GPS", CoordinateListFormatter.summaryLine(c, null))
    }

    @Test fun omitsAccuracyWhenAbsent() {
        val c = coord(provider = "rs2-external", rtkStatus = "FIX")
        assertEquals("External GNSS · Fix", CoordinateListFormatter.summaryLine(c, null))
    }

    @Test fun latLonLine_sixDecimals() {
        assertEquals("40.123456, -74.123456", CoordinateListFormatter.latLonLine(coord()))
    }
}
