package io.github.pennstatecs.geokit.receiver.emlid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmlidReachModelTest {

    @Test fun `published L1 phase-centre offsets`() {
        assertEquals(0.134, EmlidReachModel.RS2.phaseCenterOffsetM!!, 0.0)
        assertEquals(0.134, EmlidReachModel.RS2_PLUS.phaseCenterOffsetM!!, 0.0)
        assertEquals(0.095, EmlidReachModel.RS4.phaseCenterOffsetM!!, 0.0)
    }

    @Test fun `RS4 Pro offset is unknown`() {
        assertNull(EmlidReachModel.RS4_PRO.phaseCenterOffsetM)
    }

    // ── fromReportedModel ──────────────────────────────────────────────────────

    @Test fun `RS4 Pro is matched before RS4`() {
        for (s in listOf("Reach RS4 Pro", "reach rs4 pro", "RS4 PRO", "reach_rs4_pro", "Reach-RS4-Pro", "rs4pro", "  Reach RS4 Pro  "))
            assertEquals(s, EmlidReachModel.RS4_PRO, EmlidReachModel.fromReportedModel(s))
    }

    @Test fun `RS4 alone is RS4`() {
        for (s in listOf("Reach RS4", "reach rs4", "RS4", "reach_rs4", "Reach RS4 (2024)"))
            assertEquals(s, EmlidReachModel.RS4, EmlidReachModel.fromReportedModel(s))
    }

    @Test fun `RS2 plus spellings are RS2+`() {
        for (s in listOf("Reach RS2+", "reach rs2+", "RS2+", "Reach RS2 Plus", "reach_rs2plus", "rs2plus"))
            assertEquals(s, EmlidReachModel.RS2_PLUS, EmlidReachModel.fromReportedModel(s))
    }

    @Test fun `the Reach web service's own device type strings (family name glued to the model)`() {
        // Observed: an RS2+ on firmware 33.1 reports /info device.type = "ReachRS2+" (2026-11-25).
        assertEquals(EmlidReachModel.RS2_PLUS, EmlidReachModel.fromReportedModel("ReachRS2+"))
        // Same pattern for the other models (not yet observed on hardware).
        assertEquals(EmlidReachModel.RS4, EmlidReachModel.fromReportedModel("ReachRS4"))
        assertEquals(EmlidReachModel.RS4_PRO, EmlidReachModel.fromReportedModel("ReachRS4Pro"))
        assertEquals(EmlidReachModel.RS4_PRO, EmlidReachModel.fromReportedModel("ReachRS4 Pro"))
        assertNull("plain RS2 stays ambiguous", EmlidReachModel.fromReportedModel("ReachRS2"))
        assertNull("other Reach models are not these profiles", EmlidReachModel.fromReportedModel("ReachM2"))
    }

    @Test fun `a plain RS2 is ambiguous and matches nothing`() {
        for (s in listOf("Reach RS2", "RS2", "reach_rs2"))
            assertNull(s, EmlidReachModel.fromReportedModel(s))
    }

    @Test fun `blank, unknown and look-alike strings match nothing`() {
        for (s in listOf(null, "", "   ", "Reach RS3", "Reach RS+", "Reach M2", "RS40", "RS4X", "xrs4", "Pro", "Reach", "unknown"))
            assertNull(s.toString(), EmlidReachModel.fromReportedModel(s))
    }
}
