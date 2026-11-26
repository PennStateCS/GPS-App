package app.surrealar.ui.settings.receiver

import org.junit.Assert.assertEquals
import org.junit.Test

/** Two-column receiver details on tablets: whole sections, balanced, in order. */
class ReceiverDetailsColumnsTest {

    @Test fun `row keys are stable across value changes and unique for repeated labels`() {
        val a = DetailSection(1, listOf(DetailRow(10, "12/s", null), DetailRow(0, "1h", ValueOrigin.REPORTED, labelText = "Uptime"),
            DetailRow(0, "x", null, labelText = "Uptime")))
        val b = a.copy(rows = listOf(DetailRow(10, "13/s", null), DetailRow(0, "2h", ValueOrigin.REPORTED, labelText = "Uptime"),
            DetailRow(0, "y", null, labelText = "Uptime")))
        assertEquals(listOf("1/r10", "1/tUptime", "1/tUptime#2"), DetailsRowKeys.keyed(a).map { it.first })
        assertEquals("values changed, layout did not: update in place",
            DetailsRowKeys.shape(listOf(a), emptySet()), DetailsRowKeys.shape(listOf(b), emptySet()))
        val collapsible = a.copy(collapsible = true)
        assertEquals(emptyList<String>(), DetailsRowKeys.shape(listOf(collapsible), emptySet()).single().rowKeys)
        assertEquals(3, DetailsRowKeys.shape(listOf(collapsible), setOf(1)).single().rowKeys.size)
        val fewer = a.copy(rows = a.rows.take(1))
        assertEquals(false, DetailsRowKeys.shape(listOf(a), emptySet()) == DetailsRowKeys.shape(listOf(fewer), emptySet()))
    }

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
