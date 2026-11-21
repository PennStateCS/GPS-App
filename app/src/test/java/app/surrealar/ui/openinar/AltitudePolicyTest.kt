package app.surrealar.ui.openinar

import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import app.surrealar.ui.openinar.AltitudePolicy.Choice
import org.junit.Assert.assertEquals
import org.junit.Test

class AltitudePolicyTest {

    private fun auto(alt: Double?, vAcc: Double?, rtk: RtkStatus?) = AltitudePolicy.choose(AltitudePolicy.AUTO, alt, vAcc, rtk)

    @Test fun `AUTO uses the stored height of an RTK-accurate point`() {
        assertEquals(Choice.STORED_ACCURATE, auto(390.0, 0.03, RtkStatus.FIX))
        assertEquals(Choice.STORED_ACCURATE, auto(390.0, 0.5, RtkStatus.FLOAT))   // boundary inclusive
    }

    @Test fun `AUTO trusts RTK FIX or FLOAT when no accuracy figure was stored`() {
        assertEquals(Choice.STORED_RTK, auto(390.0, null, RtkStatus.FIX))
        assertEquals(Choice.STORED_RTK, auto(390.0, null, RtkStatus.FLOAT))
    }

    @Test fun `AUTO puts internal-GPS points on terrain`() {
        assertEquals(Choice.TERRAIN_INACCURATE, auto(395.3, 4.2, RtkStatus.DGPS))   // the tablet's Point 2
        assertEquals(Choice.TERRAIN_INACCURATE, auto(390.0, null, RtkStatus.SINGLE))
        assertEquals(Choice.TERRAIN_INACCURATE, auto(390.0, null, null))
        // A measured accuracy beats the RTK label: a FIX with 2 m vertical is not trusted.
        assertEquals(Choice.TERRAIN_INACCURATE, auto(390.0, 2.0, RtkStatus.FIX))
    }

    @Test fun `AUTO puts points without a height on terrain`() {
        assertEquals(Choice.TERRAIN_NO_HEIGHT, auto(null, 0.02, RtkStatus.FIX))
        assertEquals(Choice.TERRAIN_NO_HEIGHT, auto(Double.NaN, 0.02, RtkStatus.FIX))
    }

    @Test fun `STORED and TERRAIN force their choice`() {
        assertEquals(Choice.STORED_FORCED, AltitudePolicy.choose(AltitudePolicy.STORED, 395.3, 4.2, RtkStatus.DGPS))
        assertEquals(Choice.TERRAIN_FORCED, AltitudePolicy.choose(AltitudePolicy.TERRAIN, 390.0, 0.02, RtkStatus.FIX))
    }

    @Test fun `modes normalize, cycle and label`() {
        assertEquals(AltitudePolicy.AUTO, AltitudePolicy.normalize(null))
        assertEquals(AltitudePolicy.AUTO, AltitudePolicy.normalize("garbage"))
        assertEquals(AltitudePolicy.TERRAIN, AltitudePolicy.normalize("TERRAIN"))
        assertEquals(AltitudePolicy.STORED, AltitudePolicy.next(AltitudePolicy.AUTO))
        assertEquals(AltitudePolicy.TERRAIN, AltitudePolicy.next(AltitudePolicy.STORED))
        assertEquals(AltitudePolicy.AUTO, AltitudePolicy.next(AltitudePolicy.TERRAIN))
        assertEquals("Auto", AltitudePolicy.label("AUTO"))
    }
}
