package app.surrealar.gnss.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the pure JSON→[BatteryStatus] parse in [ReachBatteryService.parse]: state-of-charge
 * scaling, the /status nested layout, and the single strict charging rule.
 *
 * Robolectric supplies a real `org.json.JSONObject` (stubbed in plain JVM unit tests). No HTTP.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReachBatteryParseTest {

    private fun parse(json: String) = ReachBatteryService.parse(json)
    private fun socOf(value: String) = parse("""{"state_of_charge":$value}""")?.percent

    // ── state_of_charge ──────────────────────────────────────────────────────

    @Test fun `100 is 100 percent`() = assertEquals(100, socOf("100"))
    @Test fun `1 is 1 percent, not a full battery`() = assertEquals(1, socOf("1"))
    @Test fun `0 is 0 percent`() = assertEquals(0, socOf("0"))
    @Test fun `0_85 is a fraction - 85 percent`() = assertEquals(85, socOf("0.85"))
    @Test fun `string with percent sign`() = assertEquals(85, socOf("\"85%\""))
    @Test fun `over 100 is clamped`() = assertEquals(100, socOf("150"))

    @Test fun `missing state_of_charge returns null`() {
        assertNull(parse("""{"voltage":6.86,"charger_status":"Charging"}"""))
    }

    @Test fun `malformed json returns null`() {
        assertNull(parse("not json"))
    }

    // ── layouts ──────────────────────────────────────────────────────────────

    @Test fun `flat battery endpoint layout`() {
        val b = parse(
            """{"charger_status":"Not charging","current":0.0,"otg":false,
                "state_of_charge":100,"temperature":32.6,"voltage":6.86}"""
        )!!
        assertEquals(100, b.percent)
        assertEquals(6.86, b.voltageV!!, 1e-9)
        assertEquals(32.6, b.temperatureC!!, 1e-9)
        assertEquals("Not charging", b.chargerStatus)
        assertFalse(b.isCharging)
    }

    @Test fun `battery fields nested under battery (status layout)`() {
        val b = parse(
            """{"gnss":{"fix":"fix"},
                "battery":{"state_of_charge":42,"voltage":7.1,"charger_status":"Charging"}}"""
        )!!
        assertEquals(42, b.percent)
        assertEquals(7.1, b.voltageV!!, 1e-9)
        assertTrue(b.isCharging)
    }

    // ── charging rule ────────────────────────────────────────────────────────

    private fun chargingFor(status: String?): Boolean {
        val field = status?.let { ""","charger_status":"$it"""" } ?: ""
        return parse("""{"state_of_charge":50$field}""")!!.isCharging
    }

    @Test fun `Charging is charging`() = assertTrue(chargingFor("Charging"))
    @Test fun `Fast charging is charging`() = assertTrue(chargingFor("Fast charging"))
    @Test fun `Trickle charging is charging`() = assertTrue(chargingFor(" Trickle Charging "))
    @Test fun `Not charging is not charging`() = assertFalse(chargingFor("Not charging"))
    @Test fun `Discharging is not charging`() = assertFalse(chargingFor("Discharging"))
    @Test fun `blank status is not charging`() = assertFalse(chargingFor(""))
    @Test fun `missing status is not charging`() = assertFalse(chargingFor(null))
    @Test fun `Full is not charging`() = assertFalse(chargingFor("Full"))
}
