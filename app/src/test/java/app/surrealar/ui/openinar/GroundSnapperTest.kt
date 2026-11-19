package app.surrealar.ui.openinar

import app.surrealar.ui.openinar.GroundSnapper.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroundSnapperTest {

    @Test fun `no surfaces means no floor`() {
        assertNull(GroundSnapper.chooseMainFloor(emptyList()))
    }

    @Test fun `floor is the lowest large surface, not a higher table`() {
        val table = Surface(y = -0.6f, area = 1.2f)
        val floor = Surface(y = -1.4f, area = 6.0f)
        assertEquals(-1.4f, GroundSnapper.chooseMainFloor(listOf(table, floor))!!, 0f)
    }

    @Test fun `small noise patches below the floor are ignored`() {
        val floor = Surface(y = -1.4f, area = 4.0f)
        val noise = Surface(y = -2.5f, area = 0.1f)
        assertEquals(-1.4f, GroundSnapper.chooseMainFloor(listOf(floor, noise))!!, 0f)
    }

    @Test fun `with only small surfaces the largest is used`() {
        val a = Surface(y = -1.3f, area = 0.2f)
        val b = Surface(y = -1.5f, area = 0.4f)
        assertEquals(-1.5f, GroundSnapper.chooseMainFloor(listOf(a, b))!!, 0f)
    }
}
