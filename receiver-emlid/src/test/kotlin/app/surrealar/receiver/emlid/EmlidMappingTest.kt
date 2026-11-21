package app.surrealar.receiver.emlid
import app.surrealar.gnss.receiver.ReceiverBattery
import app.surrealar.gnss.receiver.ReceiverCorrections
import app.surrealar.gnss.receiver.ReceiverDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Reach JSON fixtures (same payloads as ReachBatteryParseTest / ReachDeviceServiceParseTest)
 * render to exactly the text the Developer page and Settings showed before the receiver-neutral
 * models: "before" = the old ReachDeviceRepository mapping + old UI formatting, "after" = EmlidMapping
 * + the new UI formatting.
 */
class EmlidMappingTest {

    private val batteryFixtures = listOf(
        """{"charger_status":"Not charging","current":0.0,"otg":false,"state_of_charge":100,"temperature":32.6,"voltage":6.86}""",
        """{"gnss":{"fix":"fix"},"battery":{"state_of_charge":42,"voltage":7.1,"charger_status":"Charging"}}""",
        """{"state_of_charge":0.85,"otg":true,"usb_charger_current":1.234,"usb_charger_voltage":5.02,"charger_status":"Charging"}""",
        """{"state_of_charge":1}""",
    )

    private val deviceFixtures = listOf(
        """{"device":{"name":"Reach6","type":"RS2+","serial_number":"SN123"},"firmware":{"version":"32.1"}}""",
        """{"general":{"name":"ReachX","model":"RS2","firmware_version":"30.0","serial":"S9"}}""",
        """{"device":{"name":"Reach6","uptime":90061}}""",
        """{"device":{"name":"Reach6","uptime":"2 days, 1:08:08"}}""",
        """{"device":{"name":"Reach6","uptime":"a while"}}""",
    )

    // ── Before: the Developer page's former battery rows, from the old ReachBatteryInfo fields ──
    private fun oldBatteryRows(b: BatteryStatus?): Map<String, String> = mapOf(
        "SoC" to (b?.percent?.let { "$it%" } ?: "--"),
        "Voltage" to (b?.voltageV?.let { "%.2f V".format(it) } ?: "--"),
        "Current" to (b?.currentA?.let { "%.2f A".format(it) } ?: "--"),
        "USB Charger Current" to (b?.usbChargerCurrentA?.let { "%.2f A".format(it) } ?: "--"),
        "USB Charger Voltage" to (b?.usbChargerVoltageV?.let { "%.2f V".format(it) } ?: "--"),
        "Temperature" to (b?.temperatureC?.let { "%.1f °C".format(it) } ?: "--"),
        "Charger Status" to (b?.chargerStatus ?: "--"),
        "OTG Power" to when (b?.otg) { true -> "Enabled"; false -> "Disabled"; null -> "--" },
        // Settings status row and toolbar
        "Settings" to (b?.let { "${it.percent}%${if (it.isCharging) " ⚡" else ""}" } ?: "—"),
        "Toolbar" to "${b?.percent}/${b?.isCharging}",
    )

    // ── After: the same rows from ReceiverBattery ──
    private fun newBatteryRows(b: ReceiverBattery?): Map<String, String> = mapOf(
        "SoC" to (b?.percent?.let { "$it%" } ?: "--"),
        "Voltage" to (b?.voltageV?.let { "%.2f V".format(it) } ?: "--"),
        "Current" to (b?.currentA?.let { "%.2f A".format(it) } ?: "--"),
        "USB Charger Current" to (b?.details?.get("USB Charger Current") ?: "--"),
        "USB Charger Voltage" to (b?.details?.get("USB Charger Voltage") ?: "--"),
        "Temperature" to (b?.temperatureC?.let { "%.1f °C".format(it) } ?: "--"),
        "Charger Status" to (b?.chargerStatus ?: "--"),
        "OTG Power" to (b?.details?.get("OTG Power") ?: "--"),
        "Settings" to (b?.let { "${it.percent}%${if (it.isCharging) " ⚡" else ""}" } ?: "—"),
        "Toolbar" to "${b?.percent}/${b?.isCharging}",
    )

    private fun uptime(sec: Long): String {
        val days = sec / 86400; val h = (sec % 86400) / 3600; val m = (sec % 3600) / 60
        return when { days > 0 -> "${days}d ${h}h ${m}m"; h > 0 -> "${h}h ${m}m"; else -> "${m}m" }
    }

    // ── Before: old ReachDeviceRepository.pollDevice mapping + old Developer/Settings rendering ──
    private fun oldDeviceRows(ip: String, d: ReachDeviceInfoDto?): Map<String, String> {
        fun String?.b() = takeIf { !it.isNullOrBlank() }
        val total = d?.storageTotalMb
        val free = d?.storageFreeMb ?: 0L
        return mapOf(
            "IP" to ip,
            "Hostname" to (d?.hostname.b() ?: "--"),
            "Name" to (d?.name.b() ?: "--"),
            "Model" to (d?.model.b() ?: "--"),
            "Firmware" to (d?.firmware.b() ?: "--"),
            "GNSS Receiver FW" to (d?.gnssReceiverFirmware.b() ?: "--"),
            "Serial" to (d?.serial.b() ?: "--"),
            "Uptime" to (d?.uptime.b() ?: d?.uptimeSec?.let(::uptime) ?: "--"),
            "Storage" to (total?.let { t -> val used = t - free
                "$used MB used / $t MB total (${((used.toDouble() / t) * 100).toInt()}%  —  $free MB free)" } ?: "--"),
            "Settings name" to (d?.name.b() ?: ip),
            "Settings uptime" to (d?.uptimeSec?.let { "${it}s" } ?: "null"),
        )
    }

    private fun newDeviceRows(d: ReceiverDeviceInfo): Map<String, String> = mapOf(
        "IP" to (d.host ?: "--"),
        "Hostname" to (d.hostname ?: "--"),
        "Name" to (d.name ?: "--"),
        "Model" to (d.model ?: "--"),
        "Firmware" to (d.firmware ?: "--"),
        "GNSS Receiver FW" to (d.details["GNSS Receiver FW"] ?: "--"),
        "Serial" to (d.serial ?: "--"),
        "Uptime" to (d.uptimeText ?: d.uptimeS?.let(::uptime) ?: "--"),
        "Storage" to (d.storage?.let { s -> "${s.usedMb} MB used / ${s.totalMb} MB total (${s.usagePercent}%  —  ${s.availableMb} MB free)" } ?: "--"),
        "Settings name" to (d.name ?: d.host ?: "Unknown Device"),
        "Settings uptime" to (d.uptimeS?.let { "${it}s" } ?: "null"),
    )

    @Test fun `battery fixtures render the same before and after`() {
        batteryFixtures.forEach { json ->
            val status = ReachBatteryService.parse(json)
            assertEquals(json, oldBatteryRows(status), newBatteryRows(status?.let(EmlidMapping::battery)))
        }
    }

    @Test fun `battery mapping keeps every field`() {
        val s = BatteryStatus(55, 7.0, -0.4, 30.0, "Discharging", false, otg = true, usbChargerCurrentA = 0.5, usbChargerVoltageV = 5.1)
        assertEquals(
            ReceiverBattery(55, false, 7.0, 30.0, -0.4, "Discharging",
                mapOf("USB Charger Current" to "%.2f A".format(0.5), "USB Charger Voltage" to "%.2f V".format(5.1), "OTG Power" to "Enabled")),
            EmlidMapping.battery(s),
        )
    }

    @Test fun `device fixtures render the same before and after`() {
        val svc = ReachDeviceService(ReachHttpClient("127.0.0.1"))
        deviceFixtures.forEach { json ->
            val dto = svc.parseReachDeviceInfo("/info", json)
            assertEquals(json, oldDeviceRows("192.168.42.1", dto), newDeviceRows(EmlidMapping.deviceInfo("192.168.42.1", dto, null)))
        }
    }

    @Test fun `device storage, chip firmware and errors are kept`() {
        val dto = ReachDeviceInfoDto(name = "Reach6", model = "RS4", firmware = "v33", serial = "S1", uptime = null,
            gnssReceiverFirmware = "HPG_1.50", modemImei = null, storageTotalMb = 1000, storageFreeMb = 250,
            hostname = "Reach6.local", rawResponse = "{...}", uptimeSec = 61)
        val d = EmlidMapping.deviceInfo("10.0.0.2", dto, null)
        assertEquals(750L, d.storage!!.usedMb); assertEquals(75, d.storage!!.usagePercent)
        assertEquals("HPG_1.50", d.details["GNSS Receiver FW"]); assertEquals("{...}", d.details["Raw response"])
        assertEquals("ERROR: timeout", EmlidMapping.deviceInfo("10.0.0.2", null, "timeout").details["Raw response"])
    }

    @Test fun `corrections map field by field and render the same in Settings`() {
        val c = ReachCorrectionsInfo(true, "NTRIP", 18, 1.4, 2.3, 41.0, -76.0, 380.0, "fix")
        assertEquals(ReceiverCorrections(true, "NTRIP", null, 1.4, 2.3, 41.0, -76.0, 380.0, 18, "fix"), EmlidMapping.corrections(c))
        fun oldRow(i: ReachCorrectionsInfo) = if (i.isReceiving) "${i.channel ?: "Connected"}${i.ageSeconds?.let { " (${it.toInt()}s)" } ?: ""}" else "Not connected"
        fun newRow(i: ReceiverCorrections) = if (i.isReceiving) "${i.channel ?: "Connected"}${i.ageS?.let { " (${it.toInt()}s)" } ?: ""}" else "Not connected"
        listOf(c, c.copy(isReceiving = false), c.copy(channel = null, ageSeconds = null))
            .forEach { assertEquals(oldRow(it), newRow(EmlidMapping.corrections(it))) }
    }
}
