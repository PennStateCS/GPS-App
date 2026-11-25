package app.surrealar.coords

import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.VerticalReference
import io.github.pennstatecs.geokit.datum.FrameTransformationRegistry
import io.github.pennstatecs.geokit.datum.Itrf2014ToNad83
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * docs/geodesy/rs4-nmea-heights.md: receiver-reported H (model unknown) and app-computed GEOID18 H stay
 * separate, and an app geoid height uses only h (= H_rcvr + N_rcvr from the same GGA), never H_rcvr again —
 * so a receiver that streams orthometric heights (firmware 34.10) cannot get a second geoid correction.
 * SYNTHETIC values.
 */
class ReceiverHeightNoDoubleCorrectionTest {

    private val geoid18N = -33.0   // fake GEOID18 lookup
    private val converter = CoordinateConverter({ _, _ -> geoid18N }, { null }, { null }, FrameTransformationRegistry.DEFAULT)
    private val epoch = Itrf2014ToNad83.decimalYear(LocalDate.of(2026, 11, 25).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli())

    private fun src(h: Double, receiverH: Double?) = SourcePosition(41.3477, -76.0226, h, receiverH, isNad83 = true, epochYear = epoch)

    @Test
    fun `GEOID18 H depends only on h, not on the receiver's H`() {
        val s = CoordinateSystemSettings(vertical = VerticalReference.NAVD88_GEOID18)
        val h = 395.539 - 33.145
        val a = converter.convert(src(h, 395.539), s).height!!
        val b = converter.convert(src(h, 395.312), s).height!!   // receiver switched to another geoid: same h
        val c = converter.convert(src(h, null), s).height!!
        assertEquals(a, b, 0.0)
        assertEquals(a, c, 0.0)
        assertEquals(h - geoid18N, a, 1e-6)   // one geoid term
    }

    @Test
    fun `receiver H is shown as receiver H with model unknown, without an undulation`() {
        val s = CoordinateSystemSettings(vertical = VerticalReference.RECEIVER_MSL)
        val e = converter.elevation(src(362.394, 395.539), s)
        assertEquals(395.539, e.orthometricHeight!!, 1e-9)
        assertEquals(ElevationDetail.RECEIVER_MODEL, e.geoidModel)
        assertEquals(ElevationDetail.RECEIVER_MODEL, e.verticalReference)
        assertNull("the receiver's N is not presented as a model undulation", e.undulation)
    }
}
