package app.surrealar.ui.toolbar

import app.surrealar.domain.model.LocationSourceType
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Step 3: the AGE token shows the correction age for external receivers only. */
class ToolbarCorrectionAgeTest {

    private fun fix(provider: Provider, age: Double?) = Fix(
        provider = provider, timeUtc = Instant.EPOCH, timestampSource = TimestampSource.NMEA_ZDA,
        latDeg = 41.2, lonDeg = -76.0, altEllipsoidalM = 300.0, altMslM = 333.0, geoidSeparationM = -33.0,
        hDop = 0.8, vDop = 1.2, pDop = null, hAccM = 0.02, vAccM = 0.03,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null, rtkStatus = RtkStatus.FLOAT,
        satsUsed = 20, satsVisible = 25, diffAgeS = age, speedMps = null, courseDeg = null,
    )

    private fun ageText(source: LocationSourceType, f: Fix): String? =
        (GnssToolbarStateMapper.map(source, f, SkySnapshot(), "RS2+") as ToolbarMapResult.Render).state.correctionAgeText

    @Test fun `external receivers show the age`() {
        assertEquals("1.2s", ageText(LocationSourceType.EXTERNAL, fix(Provider.EXTERNAL_TCP, 1.2)))
        assertEquals("0.0s", ageText(LocationSourceType.EXTERNAL, fix(Provider.EXTERNAL_TCP, 0.0)))
        assertEquals("13s", ageText(LocationSourceType.EXTERNAL, fix(Provider.EXTERNAL_TCP, 13.4)))
    }

    @Test fun `hidden for internal, replay, unknown and invalid ages`() {
        assertNull(ageText(LocationSourceType.INTERNAL, fix(Provider.INTERNAL, 1.2)))
        assertNull(ageText(LocationSourceType.EXTERNAL, fix(Provider.REPLAY, 1.2)))
        assertNull(ageText(LocationSourceType.EXTERNAL, fix(Provider.EXTERNAL_TCP, null)))
        assertNull(ageText(LocationSourceType.EXTERNAL, fix(Provider.EXTERNAL_TCP, -1.0)))
        assertNull(ageText(LocationSourceType.EXTERNAL, fix(Provider.EXTERNAL_TCP, Double.NaN)))
    }

    @Test fun `no-fix states have no age`() {
        assertNull(GnssToolbarStateMapper.waiting(LocationSourceType.EXTERNAL, "RS2+").correctionAgeText)
    }
}