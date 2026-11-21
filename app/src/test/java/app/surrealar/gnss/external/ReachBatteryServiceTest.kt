package app.surrealar.gnss.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests [ReachBatteryService.parseStateOfCharge] — the raw `state_of_charge` → percent mapping. */
class ReachBatteryServiceTest {

    private fun soc(raw: Any?) = ReachBatteryService.parseStateOfCharge(raw)

    @Test fun `whole percentages pass through`() {
        assertEquals(100, soc(100))
        assertEquals(45, soc(45))
        assertEquals(0, soc(0))
    }

    @Test fun `1 percent is 1 percent, not a full battery`() {
        assertEquals(1, soc(1))
        assertEquals(1, soc(1.0))
        assertEquals(1, soc("1"))
        assertEquals(1, soc("1%"))
    }

    @Test fun `values strictly between 0 and 1 are fractions`() {
        assertEquals(50, soc(0.5))
        assertEquals(7, soc(0.07))
        assertEquals(45, soc("0.45"))
    }

    @Test fun `string percentages are parsed`() {
        assertEquals(87, soc("87%"))
        assertEquals(87, soc(" 87 % "))
    }

    @Test fun `out of range values are clamped`() {
        assertEquals(100, soc(130))
        assertEquals(0, soc(-5))
    }

    @Test fun `absent or unparseable is null`() {
        assertNull(soc(null))
        assertNull(soc("n/a"))
        assertNull(soc(Double.NaN))
        assertNull(soc(true))
    }
}
