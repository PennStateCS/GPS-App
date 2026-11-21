package io.github.pennstatecs.geokit.receiver.emlid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests the pure JSON→DTO parsing in [ReachDeviceService.parseReachDeviceInfo] across known
 * Reach/Emlid firmware layouts and bad input. Protects device-info display + the `CORRECTIONS`
 * diagnostics from silent breakage when receiver firmware JSON changes.
 *
 * org.json comes from the test classpath (the APK uses Android's platform copy). The HTTP
 * client is never used — only the parse method is exercised — so no network/hardware is required.
 */
class ReachDeviceServiceParseTest {

    private val svc = ReachDeviceService(ReachHttpClient("127.0.0.1"))
    private fun parse(json: String) = svc.parseReachDeviceInfo("/info", json)

    @Test fun `nested device-firmware layout`() {
        val dto = parse(
            """{"device":{"name":"Reach6","type":"RS2+","serial_number":"SN123"},
                "firmware":{"version":"32.1"}}"""
        )!!
        assertEquals("Reach6", dto.name)
        assertEquals("RS2+", dto.model)
        assertEquals("32.1", dto.firmware)
        assertEquals("SN123", dto.serial)
    }

    @Test fun `general wrapper layout (ReachView 3)`() {
        val dto = parse(
            """{"general":{"name":"ReachX","model":"RS2","firmware_version":"30.0","serial":"S9"}}"""
        )!!
        assertEquals("ReachX", dto.name)
        assertEquals("RS2", dto.model)
        assertEquals("30.0", dto.firmware)
        assertEquals("S9", dto.serial)
    }

    @Test fun `flat root-level fallback layout`() {
        val dto = parse("""{"name":"ReachRoot","model":"RS2","version":"29","serial":"S1"}""")!!
        assertEquals("ReachRoot", dto.name)
        assertEquals("RS2", dto.model)
        assertEquals("29", dto.firmware)
    }

    @Test fun `missing firmware field still returns a DTO with other fields`() {
        val dto = parse("""{"device":{"name":"Reach6","type":"RS2+"}}""")!!
        assertEquals("Reach6", dto.name)
        assertEquals("RS2+", dto.model)
        assertNull(dto.firmware)
    }

    @Test fun `missing device name still returns a DTO when other fields exist`() {
        val dto = parse("""{"firmware":{"version":"31.0"},"serial_number":"SN9"}""")!!
        assertNull(dto.name)
        assertEquals("31.0", dto.firmware)
        assertEquals("SN9", dto.serial)
    }

    @Test fun `explicit null fields are treated as absent (not the literal string null)`() {
        val dto = parse("""{"device":{"name":null,"type":"RS2"}}""")!!
        assertNull(dto.name)            // JSON null must not surface as "null"
        assertEquals("RS2", dto.model)
    }

    @Test fun `unexpected extra fields are ignored`() {
        val dto = parse("""{"device":{"name":"Reach6","type":"RS2+"},"junk":{"x":1},"extra":true}""")!!
        assertEquals("Reach6", dto.name)
        assertEquals("RS2+", dto.model)
    }

    @Test fun `response with no recognizable device fields returns null`() {
        assertNull(parse("""{"foo":"bar","battery":{"percent":80}}"""))
    }

    @Test fun `malformed JSON returns null instead of crashing`() {
        assertNull(parse("{ this is not json"))
        assertNull(parse("<html>404</html>"))
    }

    @Test fun `empty response returns null`() {
        assertNull(parse(""))
    }

    @Test fun `numeric uptime is read as seconds, not as text`() {
        val dto = parse("""{"device":{"name":"Reach6","uptime":90061}}""")!!
        assertEquals(90061L, dto.uptimeSec)          // 1 d 1 h 1 m 1 s
        assertEquals("1d 1h 1m", dto.uptime)
    }

    @Test fun `text uptime is parsed to seconds`() {
        val dto = parse("""{"device":{"name":"Reach6","uptime":"2 days, 1:08:08"}}""")!!
        assertEquals(2 * 86400L + 3600 + 8 * 60 + 8, dto.uptimeSec)
        assertEquals("2 days, 1:08:08", dto.uptime)
    }

    @Test fun `unparseable uptime text is unknown, not zero`() {
        val dto = parse("""{"device":{"name":"Reach6","uptime":"a while"}}""")!!
        assertNull(dto.uptimeSec)
        assertEquals("a while", dto.uptime)
    }

    @Test fun `uptime text parser forms`() {
        assertEquals(86400L, ReachDeviceService.parseUptimeTextToSeconds("86400"))
        assertEquals(4088L, ReachDeviceService.parseUptimeTextToSeconds("1:08:08"))
        assertEquals(86400L, ReachDeviceService.parseUptimeTextToSeconds("1 day"))
        assertNull(ReachDeviceService.parseUptimeTextToSeconds(""))
    }
}
