package app.surrealar.settings.model

import app.surrealar.di.GnssModule
import io.github.pennstatecs.geokit.gnss.nmea.sentence.EBP
import io.github.pennstatecs.geokit.gnss.receiver.AccuracyClass
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverCapabilities
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverVendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalReceiverProfileCapabilitiesTest {

    private val emlid = listOf(
        ExternalReceiverProfile.REACH_RS2_PLUS, ExternalReceiverProfile.REACH_RS4, ExternalReceiverProfile.REACH_RS4_PRO,
    )

    @Test fun `Emlid profiles are survey multiband with every capability and EBP+ETC parsers`() {
        emlid.forEach { p ->
            assertEquals(p.name, ReceiverVendor.EMLID, p.vendor)
            assertEquals(p.name, AccuracyClass.SURVEY_MULTIBAND, p.accuracyClass)
            assertEquals(p.name, ReceiverCapabilities.ALL, p.capabilities)
            assertEquals(p.name, setOf("EBP", "ETC"), p.extraParsers().keys)
        }
    }

    @Test fun `generic NMEA TCP is single-frequency with no device capabilities or extra parsers`() {
        val g = ExternalReceiverProfile.GENERIC_NMEA_TCP
        assertEquals(ReceiverVendor.GENERIC, g.vendor)
        assertEquals(AccuracyClass.SINGLE_FREQUENCY, g.accuracyClass)
        assertEquals(ReceiverCapabilities.NONE, g.capabilities)
        assertTrue(g.extraParsers().isEmpty())
    }

    @Test fun `labels resolve back to profiles`() {
        ExternalReceiverProfile.entries.forEach { assertEquals(it, ExternalReceiverProfile.fromLabel(it.label)) }
        assertEquals(ExternalReceiverProfile.REACH_RS2_PLUS, ExternalReceiverProfile.fromLabel(" emlid reach rs2+ "))
        assertNull(ExternalReceiverProfile.fromLabel("Reach6"))
        assertNull(ExternalReceiverProfile.fromLabel(null))
    }

    @Test fun `live registry still registers the Emlid sentences (union of profile parsers)`() {
        assertEquals(setOf("EBP", "ETC"), ExternalReceiverProfile.allExtraParsers().keys)
        // A real Emlid RS4 EBP sentence (reach_nmea_capture.txt).
        val line = "\$GNEBP,4118.3990546,N,07601.0359618,W,374.038,M*17"
        assertTrue(GnssModule.provideNmeaRegistry().parse(line) is EBP)
    }
}
