package app.surrealar.ui.settings.receiver

import org.junit.Assert.assertEquals
import org.junit.Test

/** Two-column receiver details on tablets: whole sections, balanced, in order. */
class ReceiverDetailsColumnsTest {

    @Test fun `sections go to the shorter column, ties to the left`() {
        // Receiver 7, RTK 5, Coordinates 4, Antenna 4, Troubleshooting 4, vendor details (collapsed) 1
        assertEquals(listOf(0, 1, 1, 0, 1, 0), ReceiverDetailsColumns.split(listOf(7, 5, 4, 4, 4, 1)))
    }

    @Test fun `columns stay balanced within one section`() {
        val w = listOf(7, 5, 4, 4, 4, 1)
        val split = ReceiverDetailsColumns.split(w)
        val left = w.filterIndexed { i, _ -> split[i] == 0 }.sum()
        val right = w.filterIndexed { i, _ -> split[i] == 1 }.sum()
        assert(kotlin.math.abs(left - right) <= w.max()) { "left=$left right=$right" }
    }

    @Test fun `empty and single`() {
        assertEquals(emptyList<Int>(), ReceiverDetailsColumns.split(emptyList()))
        assertEquals(listOf(0), ReceiverDetailsColumns.split(listOf(3)))
    }
}
