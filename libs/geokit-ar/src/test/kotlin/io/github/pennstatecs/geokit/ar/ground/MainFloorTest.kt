package io.github.pennstatecs.geokit.ar.ground

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainFloorTest {

    @Test fun `no surfaces means no floor`() {
        assertNull(MainFloor.choose(emptyList()))
    }

    @Test fun `floor is the lowest large surface, not a higher table`() {
        val table = GroundSurface(y = -0.6f, area = 1.2f)
        val floor = GroundSurface(y = -1.4f, area = 6.0f)
        assertEquals(-1.4f, MainFloor.choose(listOf(table, floor))!!, 0f)
    }

    @Test fun `small noise patches below the floor are ignored`() {
        val floor = GroundSurface(y = -1.4f, area = 4.0f)
        val noise = GroundSurface(y = -2.5f, area = 0.1f)
        assertEquals(-1.4f, MainFloor.choose(listOf(floor, noise))!!, 0f)
    }

    @Test fun `with only small surfaces the largest is used`() {
        val a = GroundSurface(y = -1.3f, area = 0.2f)
        val b = GroundSurface(y = -1.5f, area = 0.4f)
        assertEquals(-1.5f, MainFloor.choose(listOf(a, b))!!, 0f)
    }
}
