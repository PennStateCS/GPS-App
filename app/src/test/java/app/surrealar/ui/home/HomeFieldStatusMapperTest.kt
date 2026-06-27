package app.surrealar.ui.home

import app.surrealar.gnss.model.RtkStatus
import app.surrealar.ui.home.HomeFieldStatusMapper.Action
import app.surrealar.ui.home.HomeFieldStatusMapper.Severity
import app.surrealar.ui.home.HomeFieldStatusMapper.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFieldStatusMapperTest {

    private fun inputs(
        isInternal: Boolean = false,
        externalLabel: String = "RS2+",
        receiverName: String? = null,
        connectionAddress: String? = null,
        rtkStatus: RtkStatus = RtkStatus.NONE,
        hAccM: Double? = null,
        vAccM: Double? = null,
        satsUsed: Int? = null,
        satsVisible: Int? = null,
        skyTotalUsed: Int = 0,
        skyTotalVisible: Int = 0,
        hdop: Double? = null,
        pdop: Double? = null,
        correctionAgeS: Double? = null,
        correctionStationId: String? = null,
        nmeaLinesPerSecond: Double? = null,
        nmeaParseErrors: Long = 0,
        hasRecentFix: Boolean = true,
    ) = HomeFieldStatusMapper.Inputs(
        isInternal = isInternal, externalLabel = externalLabel, receiverName = receiverName,
        connectionAddress = connectionAddress, rtkStatus = rtkStatus, hAccM = hAccM, vAccM = vAccM,
        satsUsed = satsUsed, satsVisible = satsVisible, skyTotalUsed = skyTotalUsed,
        skyTotalVisible = skyTotalVisible, hdop = hdop, pdop = pdop, correctionAgeS = correctionAgeS,
        correctionStationId = correctionStationId, nmeaLinesPerSecond = nmeaLinesPerSecond,
        nmeaParseErrors = nmeaParseErrors, hasRecentFix = hasRecentFix,
    )

    private fun chipLabels(s: HomeFieldStatusMapper.FieldStatus) = s.chips.map { it.label }

    @Test
    fun `internal with no fix is waiting and offers open map`() {
        val s = HomeFieldStatusMapper.map(inputs(isInternal = true, hasRecentFix = false))
        assertEquals(State.WAITING, s.state)
        assertEquals(Severity.NEUTRAL, s.severity)
        assertEquals(Action.OPEN_MAP, s.actionDestination)
        assertEquals("Android device location", s.receiverDetail)
        assertEquals(listOf("No fix"), chipLabels(s))
    }

    @Test
    fun `external no data shows endpoint, warning, and receiver settings action (no NMEA chip)`() {
        val s = HomeFieldStatusMapper.map(
            inputs(externalLabel = "RS4", receiverName = "Reach6", connectionAddress = "192.168.2.174:9000",
                nmeaLinesPerSecond = 0.0, hasRecentFix = false)
        )
        assertEquals(State.NO_RECEIVER_DATA, s.state)
        assertEquals(Severity.WARNING, s.severity)
        assertEquals(Action.RECEIVER_SETTINGS, s.actionDestination)
        assertEquals("Receiver Settings", s.actionLabel)
        assertEquals("Reach6 · 192.168.2.174:9000", s.receiverDetail)
        // No misleading "NMEA 0/s" chip — the absent stream is conveyed by the headline.
        assertEquals(listOf("No fix", "Check receiver"), chipLabels(s))
    }

    @Test
    fun `internal gps loaded shows device chips and horizontal-only accuracy`() {
        val s = HomeFieldStatusMapper.map(
            inputs(isInternal = true, rtkStatus = RtkStatus.SINGLE, hAccM = 3.4, vAccM = 5.0, satsUsed = 12)
        )
        assertEquals(State.INTERNAL_GPS, s.state)
        assertEquals("Internal · GPS · H ±3.40 m", s.primaryDetail) // no V for internal
        assertEquals("Android device location", s.receiverDetail)
        // Sats matches the toolbar: visible unknown → "used/used".
        assertEquals(listOf("Sats 12/12", "Device GPS"), chipLabels(s))
    }

    @Test
    fun `rtk fixed is high accuracy with full chip row`() {
        val s = HomeFieldStatusMapper.map(
            inputs(
                receiverName = "Reach6", connectionAddress = "192.168.2.174:9000",
                rtkStatus = RtkStatus.FIX, hAccM = 0.02, vAccM = 0.04,
                satsUsed = 29, satsVisible = 29, hdop = 0.6, correctionAgeS = 1.0,
                correctionStationId = "1116", nmeaLinesPerSecond = 12.0,
            )
        )
        assertEquals(State.HIGH_ACCURACY, s.state)
        assertEquals(Severity.GOOD, s.severity)
        assertEquals("RS2+ · Fixed · H ±0.02 m · V ±0.04 m", s.primaryDetail)
        assertEquals("Reach6 · 192.168.2.174:9000", s.receiverDetail)
        assertEquals(listOf("Sats 29/29", "HDOP 0.6", "RTK 1s", "Base 1116", "NMEA 12/s"), chipLabels(s))
    }

    @Test
    fun `rtk float is caution`() {
        val s = HomeFieldStatusMapper.map(
            inputs(rtkStatus = RtkStatus.FLOAT, hAccM = 0.04, vAccM = 0.06, satsUsed = 29, satsVisible = 29, correctionAgeS = 0.0)
        )
        assertEquals(State.FLOAT, s.state)
        assertEquals(Severity.CAUTION, s.severity)
        assertEquals("RS2+ · Float · H ±0.04 m · V ±0.06 m", s.primaryDetail)
        assertTrue(chipLabels(s).contains("RTK 0s"))
    }

    @Test
    fun `low accuracy when non-rtk fix exceeds threshold`() {
        val s = HomeFieldStatusMapper.map(inputs(rtkStatus = RtkStatus.SINGLE, hAccM = 2.5, satsUsed = 6))
        assertEquals(State.LOW_ACCURACY, s.state)
        assertEquals(Severity.WARNING, s.severity)
    }

    @Test
    fun `usable non-rtk fix is ready`() {
        val s = HomeFieldStatusMapper.map(inputs(rtkStatus = RtkStatus.DGPS, hAccM = 0.4, satsUsed = 18, satsVisible = 20))
        assertEquals(State.READY, s.state)
        assertEquals(Severity.GOOD, s.severity)
    }

    @Test
    fun `stale corrections override quality and show RTK stale chip`() {
        val s = HomeFieldStatusMapper.map(
            inputs(rtkStatus = RtkStatus.FLOAT, hAccM = 0.04, satsUsed = 29, satsVisible = 29, correctionAgeS = 30.0)
        )
        assertEquals(State.CORRECTIONS_STALE, s.state)
        assertEquals(Severity.WARNING, s.severity)
        val rtk = s.chips.first { it.label == "RTK stale" }
        assertEquals(Severity.WARNING, rtk.severity)
    }

    // ── Satellite chip parity with the toolbar ──────────────────────────────────────────────────

    @Test
    fun `sats uses used over used when visible unavailable (matches toolbar)`() {
        val s = HomeFieldStatusMapper.map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, satsUsed = 24, satsVisible = null))
        assertTrue(chipLabels(s).contains("Sats 24/24"))
    }

    @Test
    fun `sats uses used over visible from the fix when both present`() {
        val s = HomeFieldStatusMapper.map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, satsUsed = 24, satsVisible = 30))
        assertTrue(chipLabels(s).contains("Sats 24/30"))
    }

    @Test
    fun `sky counts take priority over the fix (matches toolbar)`() {
        val s = HomeFieldStatusMapper.map(
            inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, satsUsed = 20, satsVisible = 22, skyTotalUsed = 24, skyTotalVisible = 30)
        )
        assertTrue(chipLabels(s).contains("Sats 24/30"))
    }

    @Test
    fun `no sats chip when neither fix nor sky has counts`() {
        val s = HomeFieldStatusMapper.map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, satsUsed = null, satsVisible = null))
        assertFalse(chipLabels(s).any { it.startsWith("Sats") })
    }

    // ── NMEA chip display rules ─────────────────────────────────────────────────────────────────

    @Test
    fun `nmea chip shown only for a real positive rate`() {
        val s = HomeFieldStatusMapper.map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, satsUsed = 20, nmeaLinesPerSecond = 12.0))
        assertTrue(chipLabels(s).contains("NMEA 12/s"))
    }

    @Test
    fun `nmea chip hidden when rate is unavailable`() {
        val s = HomeFieldStatusMapper.map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, satsUsed = 20, nmeaLinesPerSecond = null))
        assertFalse(chipLabels(s).any { it.startsWith("NMEA") })
    }

    @Test
    fun `nmea chip hidden when rate is zero even with a recent fix`() {
        val s = HomeFieldStatusMapper.map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, satsUsed = 20, nmeaLinesPerSecond = 0.0))
        assertFalse(chipLabels(s).any { it.startsWith("NMEA") })
    }

    @Test
    fun `nmea chip hidden for a sub-one-per-second rate`() {
        val s = HomeFieldStatusMapper.map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, satsUsed = 20, nmeaLinesPerSecond = 0.4))
        assertFalse(chipLabels(s).any { it.startsWith("NMEA") })
    }

    // ── Remaining chips ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `pdop is used only when hdop missing`() {
        val withHdop = HomeFieldStatusMapper.map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, satsUsed = 20, hdop = 0.7, pdop = 1.2))
        assertTrue(chipLabels(withHdop).contains("HDOP 0.7"))
        assertFalse(chipLabels(withHdop).any { it.startsWith("PDOP") })

        val pdopOnly = HomeFieldStatusMapper.map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, satsUsed = 20, hdop = null, pdop = 1.2))
        assertTrue(chipLabels(pdopOnly).contains("PDOP 1.2"))
    }

    @Test
    fun `parse errors chip appears only when nonzero`() {
        val withErrors = HomeFieldStatusMapper.map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, satsUsed = 20, nmeaParseErrors = 3))
        assertTrue(chipLabels(withErrors).contains("Errors 3"))

        val noErrors = HomeFieldStatusMapper.map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, satsUsed = 20, nmeaParseErrors = 0))
        assertFalse(chipLabels(noErrors).any { it.startsWith("Errors") })
    }

    @Test
    fun `chip order follows sats, dop, rtk, base, nmea, errors`() {
        val s = HomeFieldStatusMapper.map(
            inputs(
                rtkStatus = RtkStatus.FIX, hAccM = 0.02, satsUsed = 29, satsVisible = 29,
                hdop = 0.7, correctionAgeS = 0.0, correctionStationId = "1116",
                nmeaLinesPerSecond = 12.0, nmeaParseErrors = 2,
            )
        )
        assertEquals(
            listOf("Sats 29/29", "HDOP 0.7", "RTK 0s", "Base 1116", "NMEA 12/s", "Errors 2"),
            chipLabels(s),
        )
    }

    @Test
    fun `missing receiver name falls back to address only`() {
        val s = HomeFieldStatusMapper.map(
            inputs(connectionAddress = "10.0.0.5:9001", rtkStatus = RtkStatus.FIX, hAccM = 0.02, satsUsed = 20)
        )
        assertEquals("10.0.0.5:9001", s.receiverDetail)
    }

    @Test
    fun `missing receiver name and address falls back to generic label`() {
        val s = HomeFieldStatusMapper.map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, satsUsed = 20))
        assertEquals("External receiver", s.receiverDetail)
    }

    @Test
    fun `missing vertical accuracy is omitted from primary detail`() {
        val s = HomeFieldStatusMapper.map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, vAccM = null, satsUsed = 20))
        assertEquals("RS2+ · Fixed · H ±0.02 m", s.primaryDetail)
    }
}
