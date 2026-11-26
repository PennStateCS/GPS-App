package app.surrealar.ui.home

import app.surrealar.receiver.tilt.EpochReference
import app.surrealar.settings.model.CompensatedOutputSetting
import app.surrealar.ui.home.HomeFieldStatusMapper.Action
import app.surrealar.ui.home.HomeFieldStatusMapper.PositionReference
import app.surrealar.ui.home.HomeFieldStatusMapper.Severity
import app.surrealar.ui.home.HomeFieldStatusMapper.State
import app.surrealar.ui.home.HomeLiveSource.Link
import app.surrealar.ui.home.HomeLiveSource.Live
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every row of the precedence table in [HomeFieldStatusMapper] (and combinations of simultaneous problems), through
 * [HomeFieldStatusMapper.map] with real inputs. Expected values are literals written before the call.
 */
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
        receiverModel: String? = null,
        vAccInvalid: Boolean = false,
        positionReference: PositionReference? = null,
        hAccEstimated: Boolean = false,
        live: Live? = null,
        link: Link? = null,
    ): HomeFieldStatusMapper.Inputs {
        val base = HomeFieldStatusMapper.Inputs(
            isInternal = isInternal, externalLabel = externalLabel, receiverName = receiverName,
            connectionAddress = connectionAddress, rtkStatus = rtkStatus, hAccM = hAccM, vAccM = vAccM,
            satsUsed = satsUsed, satsVisible = satsVisible, skyTotalUsed = skyTotalUsed,
            skyTotalVisible = skyTotalVisible, hdop = hdop, pdop = pdop, correctionAgeS = correctionAgeS,
            correctionStationId = correctionStationId, nmeaLinesPerSecond = nmeaLinesPerSecond,
            nmeaParseErrors = nmeaParseErrors, hasRecentFix = hasRecentFix, receiverModel = receiverModel,
            vAccInvalid = vAccInvalid, positionReference = positionReference, hAccEstimated = hAccEstimated,
        )
        return base.copy(live = live ?: base.live, link = link ?: base.link)
    }

    private fun map(i: HomeFieldStatusMapper.Inputs) = HomeFieldStatusMapper.map(i)
    private fun chipLabels(s: HomeFieldStatusMapper.FieldStatus) = s.chips.map { it.label }

    // ── Rows 1–5: no live fix from the selected source ─────────────────────────────────────────

    @Test fun `row 1 - tablet GNSS with no fix is waiting and offers open map`() {
        val s = map(inputs(isInternal = true, hasRecentFix = false))
        assertEquals(State.WAITING, s.state)
        assertEquals("Waiting for position", s.headline)
        assertEquals(Severity.NEUTRAL, s.severity)
        assertEquals(Action.OPEN_MAP, s.actionDestination)
        assertEquals("Tablet GNSS receiver", s.receiverDetail)
        assertEquals(listOf("No fix"), chipLabels(s))
    }

    @Test fun `row 2 - external connecting`() {
        val s = map(inputs(receiverName = "Reach6", connectionAddress = "192.168.2.174:9000",
            hasRecentFix = false, link = Link.CONNECTING))
        assertEquals(State.CONNECTING, s.state)
        assertEquals("Connecting to receiver", s.headline)
        assertEquals(Severity.NEUTRAL, s.severity)
        assertEquals(Action.RECEIVER_SETTINGS, s.actionDestination)
        assertEquals("Selected: Reach6", s.receiverDetail)
    }

    @Test fun `row 3 - external reconnecting`() {
        val s = map(inputs(receiverName = "Reach6", hasRecentFix = false, link = Link.RECONNECTING))
        assertEquals(State.RECONNECTING, s.state)
        assertEquals("Reconnecting to receiver", s.headline)
        assertEquals(Severity.CAUTION, s.severity)
        assertEquals(Action.RECEIVER_SETTINGS, s.actionDestination)
    }

    @Test fun `row 4 - external link up but no position yet`() {
        val s = map(inputs(hasRecentFix = false, link = Link.CONNECTED, nmeaLinesPerSecond = 10.0))
        assertEquals(State.WAITING, s.state)
        assertEquals("Waiting for position", s.headline)
        assertEquals(Severity.CAUTION, s.severity)
        assertEquals("RS2+ · connected, no position yet", s.primaryDetail)
    }

    @Test fun `row 5 - external no data shows the selected receiver, warning, receiver settings (no NMEA chip)`() {
        val s = map(inputs(externalLabel = "RS4", receiverName = "Reach6", connectionAddress = "192.168.2.174:9000",
            nmeaLinesPerSecond = 0.0, hasRecentFix = false, link = Link.UNAVAILABLE))
        assertEquals(State.NO_RECEIVER_DATA, s.state)
        assertEquals("No receiver data", s.headline)
        assertEquals(Severity.WARNING, s.severity)
        assertEquals(Action.RECEIVER_SETTINGS, s.actionDestination)
        assertEquals("Receiver Settings", s.actionLabel)
        assertEquals("Selected: Reach6", s.receiverDetail)
        assertEquals(listOf("No fix", "Check receiver"), chipLabels(s))
    }

    @Test fun `external selected but the live fix is tablet GNSS - no receiver metrics or identity are attached`() {
        val s = map(inputs(receiverName = "Reach6", receiverModel = "Emlid Reach RS2+", rtkStatus = RtkStatus.SINGLE,
            hAccM = 3.0, satsUsed = 12, live = Live.INTERNAL, link = Link.CONNECTING))
        assertEquals(State.CONNECTING, s.state)
        assertEquals("Selected: Reach6", s.receiverDetail)
        assertEquals(listOf("No fix"), chipLabels(s))
        assertFalse(s.primaryDetail.contains("±"))
    }

    @Test fun `tablet GNSS selected but the live fix is external - waiting, not the receiver's RTK status`() {
        val s = map(inputs(isInternal = true, rtkStatus = RtkStatus.FIX, hAccM = 0.01, live = Live.EXTERNAL))
        assertEquals(State.WAITING, s.state)
        assertEquals(Severity.NEUTRAL, s.severity)
    }

    // ── Rows 6–7: informational sources ─────────────────────────────────────────────────────────

    @Test fun `row 6 - tablet GNSS shows device chips and horizontal-only accuracy`() {
        val s = map(inputs(isInternal = true, rtkStatus = RtkStatus.SINGLE, hAccM = 3.4, vAccM = 5.0, satsUsed = 12))
        assertEquals(State.INTERNAL_GPS, s.state)
        assertEquals("Using tablet GNSS", s.headline)
        assertEquals(Severity.NEUTRAL, s.severity)
        assertEquals("Internal · Single · H ±3.40 m", s.primaryDetail)
        assertEquals("Tablet GNSS receiver", s.receiverDetail)
        assertEquals(listOf("Sats 12/12", "Device GPS"), chipLabels(s))
    }

    @Test fun `row 7 - replay is informational and carries no receiver identity`() {
        val s = map(inputs(receiverName = "Reach6", receiverModel = "Emlid Reach RS2+", rtkStatus = RtkStatus.FIX,
            hAccM = 0.01, live = Live.REPLAY))
        assertEquals(State.REPLAY, s.state)
        assertEquals(Severity.NEUTRAL, s.severity)
        assertEquals("Recorded NMEA (not a live receiver)", s.receiverDetail)
    }

    // ── Rows 8–10: blocking warnings on a live external fix ─────────────────────────────────────

    @Test fun `row 8 - stale corrections override quality and show RTK stale chip`() {
        val s = map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.01, satsUsed = 29, satsVisible = 29, correctionAgeS = 30.0))
        assertEquals(State.CORRECTIONS_STALE, s.state)
        assertEquals("Corrections stale", s.headline)
        assertEquals(Severity.WARNING, s.severity)
        assertEquals(Severity.WARNING, s.chips.first { it.label == "RTK stale" }.severity)
    }

    @Test fun `row 9 - RS4 reference unconfirmed is never green even with RTK Fixed and 1 cm`() {
        val s = map(inputs(externalLabel = "RS4", rtkStatus = RtkStatus.FIX, hAccM = 0.01, vAccM = 0.02,
            correctionAgeS = 1.0, positionReference = PositionReference.UNCONFIRMED))
        assertEquals(State.REFERENCE_UNCONFIRMED, s.state)
        assertEquals("Position reference unconfirmed", s.headline)
        assertEquals(Severity.CAUTION, s.severity)
        assertEquals("Reference unconfirmed", chipLabels(s).first())
    }

    @Test fun `row 9 - may be compensated is a warning`() {
        val s = map(inputs(externalLabel = "RS4", rtkStatus = RtkStatus.FIX, hAccM = 0.01,
            positionReference = PositionReference.MAY_BE_COMPENSATED))
        assertEquals(State.REFERENCE_UNCONFIRMED, s.state)
        assertEquals(Severity.WARNING, s.severity)
        assertEquals("May be compensated", chipLabels(s).first())
    }

    @Test fun `row 10 - invalid vertical accuracy outranks high accuracy and reads V invalid, never 999 m`() {
        val s = map(inputs(externalLabel = "RS4", rtkStatus = RtkStatus.FIX, hAccM = 0.02, vAccM = 999.0, vAccInvalid = true))
        assertEquals(State.VERTICAL_INVALID, s.state)
        assertEquals("Vertical accuracy invalid", s.headline)
        assertEquals(Severity.CAUTION, s.severity)
        assertEquals("RS4 · Fix · H ±0.02 m · V invalid", s.primaryDetail)
    }

    @Test fun `combination - stale + reference + vertical - headline is stale, the others are listed`() {
        val s = map(inputs(externalLabel = "RS4", rtkStatus = RtkStatus.FIX, hAccM = 0.01, correctionAgeS = 40.0,
            positionReference = PositionReference.UNCONFIRMED, vAccInvalid = true))
        assertEquals(State.CORRECTIONS_STALE, s.state)
        assertEquals(listOf("Position reference unconfirmed", "Vertical accuracy invalid"), s.additionalWarnings)
    }

    @Test fun `combination - reference + vertical - headline is reference, vertical listed`() {
        val s = map(inputs(externalLabel = "RS4", rtkStatus = RtkStatus.FLOAT, hAccM = 0.05,
            positionReference = PositionReference.MAY_BE_COMPENSATED, vAccInvalid = true))
        assertEquals(State.REFERENCE_UNCONFIRMED, s.state)
        assertEquals(Severity.WARNING, s.severity)
        assertEquals(listOf("Vertical accuracy invalid"), s.additionalWarnings)
    }

    @Test fun `pole tip and antenna references are informational, not warnings`() {
        val tip = map(inputs(externalLabel = "RS4", rtkStatus = RtkStatus.FIX, hAccM = 0.01,
            positionReference = PositionReference.POLE_TIP))
        assertEquals(State.HIGH_ACCURACY, tip.state)
        assertEquals("Pole tip", chipLabels(tip).first())
        val antenna = map(inputs(externalLabel = "RS4", rtkStatus = RtkStatus.FIX, hAccM = 0.01,
            positionReference = PositionReference.ANTENNA))
        assertEquals(State.HIGH_ACCURACY, antenna.state)
        assertEquals("Antenna", chipLabels(antenna).first())
        assertTrue(antenna.additionalWarnings.isEmpty())
    }

    // ── Rows 11–16: quality ─────────────────────────────────────────────────────────────────────

    @Test fun `row 11 - rtk fixed with reported accuracy within target is high accuracy`() {
        val s = map(inputs(receiverName = "Reach6", connectionAddress = "192.168.2.174:9000",
            rtkStatus = RtkStatus.FIX, hAccM = 0.02, vAccM = 0.04, satsUsed = 29, satsVisible = 29, hdop = 0.6,
            correctionAgeS = 1.0, correctionStationId = "1116", nmeaLinesPerSecond = 12.0))
        assertEquals(State.HIGH_ACCURACY, s.state)
        assertEquals("High accuracy", s.headline)
        assertEquals(Severity.GOOD, s.severity)
        assertEquals("RS2+ · Fix · H ±0.02 m · V ±0.04 m", s.primaryDetail)
        assertEquals("Reach6", s.receiverDetail)   // the IP is not repeated next to a name
        assertEquals(listOf("Sats 29/29", "HDOP 0.6", "RTK 1.0s", "Base 1116", "NMEA 12/s"), chipLabels(s))
    }

    @Test fun `row 11 boundary - exactly 5 cm is high accuracy, just above is below target`() {
        assertEquals(State.HIGH_ACCURACY, map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.05)).state)
        assertEquals(State.ACCURACY_BELOW_TARGET, map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.0501)).state)
    }

    @Test fun `row 12 - rtk fixed without reported accuracy is unverified, not green`() {
        val s = map(inputs(rtkStatus = RtkStatus.FIX, hAccM = null, correctionAgeS = 1.0))
        assertEquals(State.FIX_UNVERIFIED, s.state)
        assertEquals("RTK Fixed — accuracy unverified", s.headline)
        assertEquals(Severity.CAUTION, s.severity)
    }

    @Test fun `row 12 - an estimated accuracy never verifies rtk fixed`() {
        val s = map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.01, hAccEstimated = true))
        assertEquals(State.FIX_UNVERIFIED, s.state)
        assertEquals("RS2+ · Fix · H ±0.01 m (est.)", s.primaryDetail)
    }

    @Test fun `row 13 - rtk fixed with poor reported accuracy is below target`() {
        val s = map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.30))
        assertEquals(State.ACCURACY_BELOW_TARGET, s.state)
        assertEquals("Accuracy below target", s.headline)
        assertEquals(Severity.CAUTION, s.severity)
    }

    @Test fun `row 14 - rtk float is caution`() {
        val s = map(inputs(rtkStatus = RtkStatus.FLOAT, hAccM = 0.04, vAccM = 0.06, satsUsed = 29, satsVisible = 29, correctionAgeS = 0.0))
        assertEquals(State.FLOAT, s.state)
        assertEquals("RTK Float", s.headline)
        assertEquals(Severity.CAUTION, s.severity)
        assertEquals("RS2+ · Float · H ±0.04 m · V ±0.06 m", s.primaryDetail)
        assertTrue(chipLabels(s).contains("RTK 0.0s"))
    }

    @Test fun `row 15 - low accuracy when a non-rtk fix exceeds the threshold`() {
        val s = map(inputs(rtkStatus = RtkStatus.SINGLE, hAccM = 2.5, satsUsed = 6))
        assertEquals(State.LOW_ACCURACY, s.state)
        assertEquals(Severity.WARNING, s.severity)
    }

    @Test fun `row 16 - dgps with sub-metre accuracy is only position available, never ready or green`() {
        val s = map(inputs(rtkStatus = RtkStatus.DGPS, hAccM = 0.4, satsUsed = 18, satsVisible = 20))
        assertEquals(State.POSITION_AVAILABLE, s.state)
        assertEquals("Position available", s.headline)
        assertEquals(Severity.NEUTRAL, s.severity)
    }

    @Test fun `row 16 - single with unknown accuracy is position available, never ready`() {
        val s = map(inputs(rtkStatus = RtkStatus.SINGLE, hAccM = null))
        assertEquals(State.POSITION_AVAILABLE, s.state)
        assertNotEquals(Severity.GOOD, s.severity)
        assertFalse(s.headline.contains("Ready"))
    }

    @Test fun `no headline anywhere says ready for field work`() {
        val statuses = RtkStatus.entries.flatMap { rtk ->
            listOf(null, 0.01, 0.5, 5.0).map { h -> map(inputs(rtkStatus = rtk, hAccM = h)) }
        }
        assertTrue(statuses.none { it.headline.contains("Ready", ignoreCase = true) })
        assertTrue(statuses.filter { it.severity == Severity.GOOD }.all { it.state == State.HIGH_ACCURACY })
    }

    // ── Satellite chip parity with the toolbar ──────────────────────────────────────────────────

    @Test fun `sats uses used over used when visible unavailable (matches toolbar)`() {
        assertTrue(chipLabels(map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, satsUsed = 24))).contains("Sats 24/24"))
    }

    @Test fun `sats uses used over visible from the fix when both present`() {
        assertTrue(chipLabels(map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, satsUsed = 24, satsVisible = 30))).contains("Sats 24/30"))
    }

    @Test fun `sky counts take priority over the fix (matches toolbar)`() {
        val s = map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, satsUsed = 20, satsVisible = 22, skyTotalUsed = 24, skyTotalVisible = 30))
        assertTrue(chipLabels(s).contains("Sats 24/30"))
    }

    @Test fun `no sats chip when neither fix nor sky has counts`() {
        assertFalse(chipLabels(map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02))).any { it.startsWith("Sats") })
    }

    // ── NMEA chips ──────────────────────────────────────────────────────────────────────────────

    @Test fun `nmea chip shown only for a real positive rate`() {
        assertTrue(chipLabels(map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, nmeaLinesPerSecond = 12.0))).contains("NMEA 12/s"))
        assertFalse(chipLabels(map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, nmeaLinesPerSecond = null))).any { it.startsWith("NMEA") })
        assertFalse(chipLabels(map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, nmeaLinesPerSecond = 0.0))).any { it.startsWith("NMEA") })
        assertFalse(chipLabels(map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, nmeaLinesPerSecond = 0.4))).any { it.startsWith("NMEA") })
    }

    @Test fun `recent parse errors chip appears only when nonzero and says the window`() {
        assertTrue(chipLabels(map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, nmeaParseErrors = 3))).contains("Errors 3 in 1 min"))
        assertFalse(chipLabels(map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, nmeaParseErrors = 0))).any { it.startsWith("Errors") })
    }

    @Test fun `pdop is used only when hdop missing`() {
        val withHdop = map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, hdop = 0.7, pdop = 1.2))
        assertTrue(chipLabels(withHdop).contains("HDOP 0.7"))
        assertFalse(chipLabels(withHdop).any { it.startsWith("PDOP") })
        assertTrue(chipLabels(map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, pdop = 1.2))).contains("PDOP 1.2"))
    }

    @Test fun `chip order follows reference, sats, dop, rtk, base, nmea, errors`() {
        val s = map(inputs(externalLabel = "RS4", rtkStatus = RtkStatus.FIX, hAccM = 0.02, satsUsed = 29, satsVisible = 29,
            hdop = 0.7, correctionAgeS = 0.0, correctionStationId = "1116", nmeaLinesPerSecond = 12.0, nmeaParseErrors = 2,
            positionReference = PositionReference.POLE_TIP))
        assertEquals(listOf("Pole tip", "Sats 29/29", "HDOP 0.7", "RTK 0.0s", "Base 1116", "NMEA 12/s", "Errors 2 in 1 min"), chipLabels(s))
    }

    // ── Receiver identity line ──────────────────────────────────────────────────────────────────

    @Test fun `receiver line - name and reported model, no address`() {
        val s = map(inputs(receiverName = "Reach6", connectionAddress = "192.168.2.174:9000", receiverModel = "Emlid Reach RS2+",
            rtkStatus = RtkStatus.FIX, hAccM = 0.02))
        assertEquals("Reach6 · Emlid Reach RS2+", s.receiverDetail)
    }

    @Test fun `receiver line - a saved name that is just the address is not repeated`() {
        val s = map(inputs(receiverName = "192.168.2.174", connectionAddress = "192.168.2.174:9000", rtkStatus = RtkStatus.FIX))
        assertEquals("192.168.2.174:9000", s.receiverDetail)
    }

    @Test fun `receiver line - address only when no name is known, generic label when nothing`() {
        assertEquals("10.0.0.5:9001", map(inputs(connectionAddress = "10.0.0.5:9001", rtkStatus = RtkStatus.FIX)).receiverDetail)
        assertEquals("External receiver", map(inputs(rtkStatus = RtkStatus.FIX)).receiverDetail)
        assertEquals("No receiver saved", map(inputs(hasRecentFix = false, link = Link.UNAVAILABLE)).receiverDetail)
    }

    @Test fun `missing vertical accuracy is omitted from primary detail`() {
        assertEquals("RS2+ · Fix · H ±0.02 m", map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02)).primaryDetail)
    }

    // ── Position reference mapping (from the tilt monitor) ─────────────────────────────────────

    @Test fun `position reference mapping`() {
        val unknown = CompensatedOutputSetting.UNKNOWN
        val enabled = CompensatedOutputSetting.ENABLED
        assertNull(HomeFieldStatusMapper.positionReferenceOf(false, EpochReference.MAY_BE_COMPENSATED, unknown))
        assertEquals(PositionReference.POLE_TIP, HomeFieldStatusMapper.positionReferenceOf(true, EpochReference.POLE_TIP, enabled))
        assertEquals(PositionReference.ANTENNA, HomeFieldStatusMapper.positionReferenceOf(true, EpochReference.ANTENNA, enabled))
        assertEquals(PositionReference.MAY_BE_COMPENSATED, HomeFieldStatusMapper.positionReferenceOf(true, EpochReference.MAY_BE_COMPENSATED, enabled))
        assertEquals(PositionReference.UNCONFIRMED, HomeFieldStatusMapper.positionReferenceOf(true, EpochReference.ANTENNA_UNCERTAIN, enabled))
        assertEquals(PositionReference.UNCONFIRMED, HomeFieldStatusMapper.positionReferenceOf(true, null, unknown))
        assertNull(HomeFieldStatusMapper.positionReferenceOf(true, null, enabled))
    }

    @Test fun `a green High accuracy headline never sits beside a WARNING chip (review L7)`() {
        val s = map(inputs(rtkStatus = RtkStatus.FIX, hAccM = 0.02, nmeaParseErrors = 3))
        if (s.severity == HomeFieldStatusMapper.Severity.GOOD)
            assertTrue(s.chips.none { it.severity == HomeFieldStatusMapper.Severity.WARNING })
    }
}
