package app.surrealar.domain.model

import app.surrealar.coords.frameToken
import app.surrealar.settings.model.ReceiverDatum
import io.github.pennstatecs.geokit.datum.Itrf2014ToNad83
import io.github.pennstatecs.geokit.datum.ReferenceFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The persisted v14 provenance tokens and helpers (plain JVM). */
class CoordinateProvenanceTest {

    @Test
    fun frameTokens_areTheGeokitStableFrameIds() {
        assertEquals(ReferenceFrame.WGS84_AS_ITRF2014.id, FrameTokens.WGS84_AS_ITRF2014)
        assertEquals(ReferenceFrame.ITRF2014.id, FrameTokens.ITRF2014)
        assertEquals(ReferenceFrame.NAD83_2011.id, FrameTokens.NAD83_2011)
        for (t in listOf(FrameTokens.WGS84_AS_ITRF2014, FrameTokens.ITRF2014, FrameTokens.NAD83_2011)) {
            assertEquals(t, ReferenceFrame.fromId(t)?.id)
        }
    }

    @Test
    fun storedTokens_neverChange() {
        // Stored in Room and backups: renaming one would orphan existing rows.
        assertEquals("wgs84-as-itrf2014", FrameTokens.WGS84_AS_ITRF2014)
        assertEquals("itrf2014", FrameTokens.ITRF2014)
        assertEquals("nad83-2011", FrameTokens.NAD83_2011)
        assertEquals("RECEIVER_GGA", OrthometricHeightSource.RECEIVER_GGA)
        assertEquals("DEVICE_GNSS", OrthometricHeightSource.DEVICE_GNSS)
        assertEquals("COMPUTED", OrthometricHeightSource.COMPUTED)
        assertEquals("GEOID18", OrthometricModel.GEOID18)
        assertEquals("EGM2008", OrthometricModel.EGM2008)
        assertEquals("EGM96", OrthometricModel.EGM96)
    }

    @Test
    fun sourceFrame_followsTheStoredCrsEpsg() {
        assertEquals(FrameTokens.WGS84_AS_ITRF2014, FrameTokens.forStoredCrsEpsg(4326))
        assertEquals(FrameTokens.WGS84_AS_ITRF2014, FrameTokens.forStoredCrsEpsg(4979))
        assertEquals(FrameTokens.NAD83_2011, FrameTokens.forStoredCrsEpsg(6318))
        assertEquals(FrameTokens.NAD83_2011, FrameTokens.forStoredCrsEpsg(6319))
        assertNull(FrameTokens.forStoredCrsEpsg(null))
        assertNull(FrameTokens.forStoredCrsEpsg(9989))   // not a stored-point datum today
    }

    @Test
    fun receiverDatumSetting_mapsToFrameTokens() {
        assertEquals(FrameTokens.ITRF2014, ReceiverDatum.ITRF2014.frameToken())
        assertEquals(FrameTokens.NAD83_2011, ReceiverDatum.NAD83_2011.frameToken())
    }

    @Test
    fun coordinateEpoch_isTheFeedTransformsDecimalYear() {
        val t = 1_763_900_000_123L
        assertEquals(Itrf2014ToNad83.decimalYear(t), coordinateEpochOf(t), 0.0)
    }

    @Test
    fun clearingPositionProvenance_keepsHeightProvenanceAndValues() {
        val c = Coordinate(
            id = "p", name = "P", latitude = 41.0, longitude = -76.0, altitude = 300.0, timestamp = 1L,
            icon = "", color = 0, altitudeMsl = 333.0,
            sourceFrame = FrameTokens.WGS84_AS_ITRF2014, receiverFrame = FrameTokens.NAD83_2011,
            coordinateEpoch = 2026.5, orthometricHeightSource = OrthometricHeightSource.RECEIVER_GGA,
        )
        val cleared = c.withPositionProvenanceUnknown()
        assertNull(cleared.sourceFrame)
        assertNull(cleared.receiverFrame)
        assertNull(cleared.coordinateEpoch)
        assertEquals(OrthometricHeightSource.RECEIVER_GGA, cleared.orthometricHeightSource)
        assertEquals(c.copy(sourceFrame = null, receiverFrame = null, coordinateEpoch = null), cleared)
    }
}
