package app.surrealar.util.diagnostics.report

import app.surrealar.receiver.tilt.EpochReference
import app.surrealar.receiver.tilt.TiltStatus
import app.surrealar.settings.model.CompensatedOutputSetting
import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.util.diagnostics.CaptureInterruptions
import io.github.pennstatecs.geokit.receiver.emlid.nmea.EtcNotification
import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidPositionReference
import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidTiltReading
import io.github.pennstatecs.geokit.receiver.emlid.tilt.EmlidTiltState
import io.github.pennstatecs.geokit.receiver.emlid.tilt.PositionReference
import io.github.pennstatecs.geokit.receiver.emlid.tilt.TiltMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyContextCollectorTest {

    private val tilt = TiltStatus(
        applicable = true, profile = ExternalReceiverProfile.REACH_RS4_PRO,
        compensatedOutput = CompensatedOutputSetting.ENABLED, firmware = "34.10",
        latest = EmlidTiltReading(EmlidTiltState.ACTIVE, EtcNotification.NONE, 61_200.0, 254.0, 116.1, 12.3, 6.28, 6.28, 6.99, null),
        etcSeen = true, etcLive = true, etcAgeMs = 120, etcRateHz = 5.0, etcCount = 885,
        reference = EpochReference.POLE_TIP,
        result = EmlidPositionReference.Result(PositionReference.RECEIVER_COMPENSATED_POLE_TIP, EmlidPositionReference.Reason.RECEIVER_COMPENSATING),
        match = TiltMatch.MATCHED,
    )

    private fun input(t: TiltStatus? = tilt) = SurveyContextCollector.Input(
        activeSource = "EXTERNAL_TCP", receiverProfile = "Emlid Reach RS4 Pro", receiverModel = "Reach RS4 Pro",
        connectionType = "TCP", receiverFrame = "ITRF2014", compensatedOutput = "Enabled", firmware = "34.10",
        fix = SurveyContextCollector.FixQuality("EXTERNAL_TCP", "FIX", 2026.9012345, "NMEA_ZDA", 1.2, 0.014, 0.021, 24, 31, 0.7, 1.3, 0),
        sentenceCounts = listOf("GGA" to "885", "ETC" to "885"), nmeaLinesPerSecond = 35.0, tilt = t,
        poleHeightM = 1.8, phaseCenterOffsetM = 0.095, phaseCenterFromProfile = true, cameraBelowReceiverM = 0.4, captureOffsetM = 1.895,
        interruptions = CaptureInterruptions.Snapshot(linkedMapOf("position_reference_changed" to 2, "receiver_datum_changed" to 1), "position_reference_changed", 1L),
        horizontal = "Latitude / Longitude", vertical = "Orthometric height H (NAVD88, GEOID18)", outputDatum = "WGS84 (ITRF2014)",
        betaPreview = false, betaSuspended = listOf("height reference"),
        geoidModel = "GEOID18", geoidVersion = "GEOID18 (NGS)", geoidLoadState = "loaded",
        transformationPaths = listOf("legacy  wgs84-as-itrf2014 -> nad83-2011 : VERIFIED_LEGACY"),
        gridFailures = listOf("EGM96 geoid open failed (IOException)"),
    )

    @Test fun rendersEveryRequestedField() {
        val r = SurveyContextCollector.render(input())
        for (s in listOf(
            "Receiver profile", "Emlid Reach RS4 Pro", "Connection type", "TCP", "Receiver frame setting", "ITRF2014",
            "Compensated NMEA output", "Enabled", "Firmware", "34.10", "Coordinate epoch", "2026.901",
            "Fix type", "FIX", "Correction age", "1.2 s", "Horizontal accuracy", "0.014 m (68% radius)", "Vertical accuracy",
            "24 / 31", "0.70 / 1.30", "ETC rate", "5.0 Hz", "GGA", "885",
            "Tilt state", "active · 12.3° · heading 254°", "live (last ETC 120 ms ago)", "Position reference", "pole tip",
            "RECEIVER_COMPENSATING", "Pole height", "1.800 m", "0.095 m (receiver profile)", "1.895 m",
            "Total", "position_reference_changed", "Last reason",
            "Horizontal system", "Height reference", "NSRS 2022 beta preview", "OFF", "Beta choices not in use",
            "Geoid model", "GEOID18", "Geoid load state", "loaded", "VERIFIED_LEGACY", "EGM96 geoid open failed",
        )) assertTrue("missing: $s\n$r", r.contains(s))
        assertTrue(r.contains("Total                                  : 3"))
    }

    @Test fun nonTiltProfile_saysNotApplicable() {
        val r = SurveyContextCollector.render(input(t = tilt.copy(applicable = false)))
        assertTrue(r.contains("Not applicable for this receiver profile"))
    }

    @Test fun containsNoCoordinatesOrSecrets_andSurvivesTheRedactorUnchanged() {
        val r = SurveyContextCollector.render(input())
        assertFalse(r.contains("41.") || r.contains("-76."))
        assertFalse(r.contains("192.168"))
        assertEquals("the section is already clean: the redactor changes nothing", r, ReportRedactor.scrub(r))
    }

    @Test fun redactorStillScrubsAnythingThatSlipsIn() {
        val leaked = SurveyContextCollector.render(input().copy(
            receiverModel = "ntrip://user:secret@caster.example.com",
            firmware = "lat=41.123456 lon=-76.123456",
        ))
        val scrubbed = ReportRedactor.scrub(leaked)
        assertFalse(scrubbed.contains("secret"))
        assertFalse(scrubbed.contains("41.123456"))
        assertFalse(scrubbed.contains("-76.123456"))
    }
}
