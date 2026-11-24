package app.surrealar.ui.settings.categories

import io.github.pennstatecs.geokit.projection.SpcsZone
import io.github.pennstatecs.geokit.projection.SpcsZoneLocator
import io.github.pennstatecs.geokit.projection.SpcsZones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpcsZoneHintsTest {
    private val locator = SpcsZoneLocator.SPCS83
    private val paNorth = SpcsZones.byEpsg(6562)!!
    private val paSouth = SpcsZones.byEpsg(6564)!!
    private val hazleton = locator.locate(40.97, -76.01)

    private fun proposal(saved: SpcsZone?, lat: Double, lon: Double) =
        SpcsZoneHints.proposal(saved, saved?.let { locator.check(it, lat, lon) }, locator.locate(lat, lon))

    private fun mismatch(zone: SpcsZone, lat: Double, lon: Double) =
        SpcsZoneHints.mismatchHint(zone, locator.check(zone, lat, lon), locator.locate(lat, lon))

    private fun ngs(n: Int) = SpcsZones.all.single { it.ngsZone == n }

    @Test fun `switching to State Plane in Hazleton proposes Pennsylvania North over a saved South zone`() {
        // Hazleton lies inside Pennsylvania South's area-of-use box, which the old rule accepted.
        assertTrue(paSouth.contains(40.97, -76.01))
        assertEquals(paNorth, proposal(paSouth, 40.97, -76.01))
        assertNull(proposal(paNorth, 40.97, -76.01))   // saved zone fits: keep it
        assertEquals(paNorth, proposal(null, 40.97, -76.01))
        assertNull(proposal(paSouth, 51.5, -0.1))       // no suggestion abroad: keep the saved zone
    }

    @Test fun `a saved zone is never replaced on an uncertain answer`() {
        // ~55 m from the New York / Pennsylvania line: either zone may be right.
        assertNull(proposal(paNorth, 42.0005, -77.0))
        assertNull(proposal(ngs(3102), 42.0005, -77.0))
        // Alaska (box only): keep the saved Alaska zone.
        assertNull(proposal(ngs(5003), 64.8378, -147.7164))
        // Offshore New Jersey, inside its box: keep it.
        assertNull(proposal(ngs(2900), 39.30, -74.20))
    }

    @Test fun `Columbus GA keeps Georgia West (the 1-20M lines would have swapped in Alabama East)`() {
        assertNull(proposal(ngs(1002), 32.4669, -84.9910))
        assertNull(mismatch(ngs(1002), 32.4669, -84.9910))
    }

    @Test fun `suggestion hints name the basis, a nearby zone line and the alternatives`() {
        assertEquals("Suggested from your position (zone county limits). Check it near a zone boundary.",
            SpcsZoneHints.suggestionHint(hazleton))
        assertEquals("Suggested from your position (zone county limits). Check it near a zone boundary. " +
            "Also possible here: Kentucky Single Zone (1600).",
            SpcsZoneHints.suggestionHint(locator.locate(38.0406, -84.5037)))
        val near = locator.locate(42.0005, -77.0)
        assertEquals(setOf(3701, 3102), near.candidates.map { it.ngsZone }.toSet())
        val nearLine = SpcsZoneHints.suggestionHint(near)
        assertTrue(nearLine, nearLine.contains("You are near a zone line: confirm the zone."))
        assertTrue(nearLine, nearLine.contains("Also possible here: " + near.candidates[1].label + "."))
        val alaska = SpcsZoneHints.suggestionHint(locator.locate(64.8378, -147.7164))
        assertTrue(alaska, alaska.startsWith("Suggested from your position. Check it: zone areas overlap near state lines."))
    }

    @Test fun `mismatch hint uses the same zone limits as the suggestion`() {
        assertEquals("Your position appears to be in Pennsylvania North (3701), not this zone.",
            mismatch(paSouth, 40.97, -76.01))
        assertNull(mismatch(paNorth, 40.97, -76.01))
        // Philadelphia with New Jersey chosen: inside NJ's box, clearly in Pennsylvania South.
        assertEquals("Your position appears to be in Pennsylvania South (3702), not this zone.",
            mismatch(ngs(2900), 39.9526, -75.1652))
        // Outside the zone's box (certain).
        assertEquals("Your current position is outside this zone.", mismatch(paNorth, 64.8378, -147.7164))
        // Uncertain answers are worded as such, never as a mismatch.
        assertEquals("Your position is near this zone's boundary: check the zone.", mismatch(paNorth, 42.0005, -77.0))
        assertEquals("This zone's limits are only approximate here: check the zone.", mismatch(ngs(5003), 64.8378, -147.7164))
        assertNull(SpcsZoneHints.mismatchHint(paNorth, null, null))
    }
}
