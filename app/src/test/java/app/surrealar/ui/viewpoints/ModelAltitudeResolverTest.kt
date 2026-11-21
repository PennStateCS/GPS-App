package app.surrealar.ui.viewpoints

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelAltitudeResolverTest {

    @Test fun `model altitude is used when present`() {
        val r = ModelAltitudeResolver.resolve(modelAltitudeM = 361.2, liveEllipsoidalM = 362.0)!!
        assertEquals(361.2, r.altitudeM, 0.0)
        assertEquals(ModelAltitudeResolver.Source.MODEL, r.source)
    }

    @Test fun `missing model altitude falls back to the current fix height, flagged`() {
        val r = ModelAltitudeResolver.resolve(modelAltitudeM = null, liveEllipsoidalM = 362.0)!!
        assertEquals(362.0, r.altitudeM, 0.0)
        assertEquals(ModelAltitudeResolver.Source.CURRENT_FIX, r.source)
    }

    @Test fun `no altitude anywhere is unknown, never 0`() {
        assertNull(ModelAltitudeResolver.resolve(null, null))
        assertNull(ModelAltitudeResolver.resolve(Double.NaN, null))
    }
}
