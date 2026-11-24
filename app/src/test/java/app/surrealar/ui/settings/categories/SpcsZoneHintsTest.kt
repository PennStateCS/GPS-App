package app.surrealar.ui.settings.categories

import io.github.pennstatecs.geokit.projection.SpcsZoneLocator
import io.github.pennstatecs.geokit.projection.SpcsZones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpcsZoneHintsTest {
    private val paNorth = SpcsZones.byEpsg(6562)!!
    private val paSouth = SpcsZones.byEpsg(6564)!!
    private val hazleton = SpcsZoneLocator.SPCS83.locate(40.97, -76.01)

    @Test fun `switching to State Plane in Hazleton proposes Pennsylvania North over a saved South zone`() {
        // Hazleton lies inside Pennsylvania South's area-of-use box, which the old rule accepted.
        assertTrue(paSouth.contains(40.97, -76.01))
        assertEquals(paNorth, SpcsZoneHints.proposal(paSouth, hazleton))
        assertNull(SpcsZoneHints.proposal(paNorth, hazleton))   // saved zone fits: keep it
        assertEquals(paNorth, SpcsZoneHints.proposal(null, hazleton))
        assertNull(SpcsZoneHints.proposal(paSouth, SpcsZoneLocator.SPCS83.locate(51.5, -0.1)))
    }

    @Test fun `suggestion hints name the basis and the alternatives`() {
        assertEquals("Suggested from your position (zone county limits). Check it near a zone boundary.",
            SpcsZoneHints.suggestionHint(hazleton))
        assertEquals("Suggested from your position (zone county limits). Check it near a zone boundary. " +
            "Also valid here: Kentucky Single Zone (1600).",
            SpcsZoneHints.suggestionHint(SpcsZoneLocator.SPCS83.locate(38.0406, -84.5037)))
        val alaska = SpcsZoneHints.suggestionHint(SpcsZoneLocator.SPCS83.locate(64.8378, -147.7164))
        assertTrue(alaska, alaska.startsWith("Suggested from your position. Check it: zone areas overlap near state lines."))
    }

    @Test fun `mismatch hint uses county limits where they apply`() {
        assertEquals("Your position appears to be in Pennsylvania North (3701), not this zone.",
            SpcsZoneHints.mismatchHint(paSouth, hazleton, boxContains = true))
        assertNull(SpcsZoneHints.mismatchHint(paNorth, hazleton, boxContains = true))
        assertEquals("Your current position is outside this zone.",
            SpcsZoneHints.mismatchHint(paNorth, null, boxContains = false))
    }
}
