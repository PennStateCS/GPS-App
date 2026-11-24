package app.surrealar.ui.viewpoints

import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.FrameTokens
import app.surrealar.domain.model.OrthometricHeightSource
import app.surrealar.domain.model.OrthometricModel
import org.junit.Assert.assertEquals
import org.junit.Test

/** Provenance rows on the point detail screen: B tokens named, NULL shown as "unknown", never guessed. */
class CoordinateProvenanceRowsTest {

    private fun point() = Coordinate(
        id = "p", name = "p", latitude = 41.0, longitude = -76.0, altitude = 300.0,
        timestamp = 1_795_000_000_000L, icon = "", color = 0, crsEpsg = 4979,
    )

    private fun rows(c: Coordinate) = CoordinateDetailFormatter.provenanceRows(c).associate { it.label to it.value }

    @Test fun `legacy point - every provenance field reads unknown`() {
        val r = rows(point())
        assertEquals(
            linkedMapOf(
                "Source frame" to "unknown",
                "Coordinate epoch" to "unknown",
                "Receiver frame" to "unknown",
                "Orthometric height source" to "unknown",
                "Orthometric height model" to "model unknown",
            ), r)
        assertEquals("H at mark (receiver geoid, model unknown)", CoordinateDetailFormatter.receiverOrthometricHeightLabel(null))
        assertEquals("N (receiver geoid, model unknown)", CoordinateDetailFormatter.receiverGeoidSeparationLabel(null))
    }

    @Test fun `captured point - tokens are named`() {
        val c = point().copy(
            sourceFrame = FrameTokens.WGS84_AS_ITRF2014, receiverFrame = FrameTokens.NAD83_2011,
            coordinateEpoch = 2026.88612, orthometricHeightSource = OrthometricHeightSource.RECEIVER_GGA,
        )
        val r = rows(c)
        assertEquals("WGS84 (ITRF2014)", r["Source frame"])
        assertEquals("2026.886", r["Coordinate epoch"])
        assertEquals("NAD83(2011)", r["Receiver frame"])
        assertEquals("External receiver (NMEA GGA)", r["Orthometric height source"])
        assertEquals("model unknown", r["Orthometric height model"])
        assertEquals("Device GNSS", CoordinateDetailFormatter.orthometricHeightSourceLabel(OrthometricHeightSource.DEVICE_GNSS))
        assertEquals("ITRF2014", CoordinateDetailFormatter.frameLabel(FrameTokens.ITRF2014))
    }

    @Test fun `known model is named in the height rows`() {
        val c = point().copy(orthometricModel = OrthometricModel.EGM96)
        assertEquals("EGM96", rows(c)["Orthometric height model"])
        assertEquals("H at mark (receiver geoid, EGM96)", CoordinateDetailFormatter.receiverOrthometricHeightLabel(OrthometricModel.EGM96))
        assertEquals("N (receiver geoid, EGM96)", CoordinateDetailFormatter.receiverGeoidSeparationLabel(OrthometricModel.EGM96))
    }

    @Test fun `unrecognised tokens are shown as unknown with the raw token`() {
        val r = rows(point().copy(sourceFrame = "itrf2030", orthometricHeightSource = "FUTURE", orthometricModel = "XGM2030"))
        assertEquals("unknown (itrf2030)", r["Source frame"])
        assertEquals("unknown (FUTURE)", r["Orthometric height source"])
        assertEquals("unknown (XGM2030)", r["Orthometric height model"])
        assertEquals("unknown", CoordinateDetailFormatter.coordinateEpochLabel(Double.NaN))
    }
}
