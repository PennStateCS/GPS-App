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
}
