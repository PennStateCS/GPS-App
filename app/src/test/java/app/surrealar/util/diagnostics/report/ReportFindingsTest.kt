package app.surrealar.util.diagnostics.report

import app.surrealar.util.diagnostics.LogLineFormat
import app.surrealar.util.diagnostics.incident.IncidentContext
import app.surrealar.util.diagnostics.incident.IncidentMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/**
 * One test per findings rule, each with the structured DiagEvents code and the legacy sentences older
 * builds write. Fixed clock: the report is at 15:00, an incident (when given) at 14:55 (UTC-5).
 */
class ReportFindingsTest {

    private val now = ms("15:00:00")
    private val incidentAt = ms("14:55:00")

    private fun ms(hms: String) = OffsetDateTime.parse("2026-11-21T$hms-05:00").toInstant().toEpochMilli()
    private fun line(hms: String, level: Char, tag: String, msg: String) = "2026-11-21T$hms.000-05:00 $level $tag [main] $msg"

    private fun entries(lines: List<String>): List<LogEntry> {
        val out = mutableListOf<LogEntry>()
        LogIndex.forEachEntrySized(lines.asSequence(), "app-log-current.txt") { e, _, _ -> if (e != null) out += e }
        return out
    }

    private fun input(
        vararg lines: String,
        incident: IncidentMarker? = null,
        device: DeviceHealth? = null,
        data: DataHealth? = null,
        live: LiveState? = null,
        info: ReportInfo = ReportInfo(),
    ): FindingsInput {
        val es = entries(lines.toList()).sortedBy { it.epochMs }
        return FindingsInput(
            nowMs = now,
            signals = es.mapNotNull { e -> ReportSignals.classify(e)?.let { Signal(it, e) } },
            crashes = es.filter(LogIndex::isCrash),
            sessionStarts = es.filter { it.message.startsWith("SESSION_START") },
            incident = incident, live = live, device = device, data = data, info = info,
        )
    }

    private fun incident(context: IncidentContext = IncidentContext(), session: String? = "bbb222") = IncidentMarker(
        id = "AB12CD34", epochMs = incidentAt, utcTime = "2026-11-21T19:55:00Z", localTime = "2026-11-21T14:55:00",
        zoneId = "America/New_York", sessionId = session, appVersion = "1.4", screen = "nav_open_in_ar",
        description = "models jumped", context = context,
    )

    private fun rules(i: FindingsInput) = ReportFindings.evaluate(i).map { it.rule }
    private fun find(i: FindingsInput, rule: String) = ReportFindings.evaluate(i).firstOrNull { it.rule == rule }

    // ── Receiver ──

    @Test fun `receiver disconnection - structured and legacy messages`() {
        for (msg in listOf("RECEIVER_LINK state=DISCONNECTED conn=c7", "RECEIVER_LINK state=UNREACHABLE conn=c7 afterS=30",
                "External receiver link down: reconnecting attempt=1 upS=300", "External receiver still unreachable attempts=5 lastError=SocketTimeoutException",
                "External receiver connect failed attempt=2 (will retry)", "Link down: reconnecting (attempt 1), was up 300s")) {
            val f = find(input(line("14:58:00", 'W', "RECEIVER", msg)), "receiver_down")
            assertNotNull(msg, f)
            assertEquals(Finding.Certainty.OBSERVED, f!!.certainty)
            assertTrue(f.evidence.single().text.contains(msg.take(30)))
            assertEquals("app-log-current.txt", f.evidence.single().file)
        }
        // A user disconnect is an I line, not a failure.
        assertNull(find(input(line("14:58:00", 'I', "RECEIVER", "External receiver link down: disconnected upS=10")), "receiver_down"))
    }

    @Test fun `receiver recovered vs not recovered`() {
        val rec = find(input(line("14:50:00", 'W', "RECEIVER", "RECEIVER_LINK state=DISCONNECTED"),
            line("14:51:00", 'I', "RECEIVER", "External receiver link up attempts=3")), "receiver_down")!!
        assertTrue(rec.title, rec.title.contains("recovered") && !rec.title.contains("not recovered"))
        val down = find(input(line("14:50:00", 'W', "RECEIVER", "RECEIVER_LINK state=DISCONNECTED"),
            live = LiveState("EXTERNAL_TCP", null, "RECONNECTING", null, null, null)), "receiver_down")!!
        assertTrue(down.title, down.title.contains("not recovered (link at export: RECONNECTING)"))
    }

    @Test fun `TCP connected but no NMEA`() {
        for (msg in listOf("RECEIVER_LINK state=NO_DATA conn=c7 afterS=10",
                "External receiver link stalled: no NMEA data, reconnecting silentMs=10000",
                "External receiver TCP link dead: no data, reconnecting host=192.168.42.1 port=9001 silentMs=12000")) {
            assertTrue(msg, "tcp_no_nmea" in rules(input(line("14:58:00", 'W', "RECEIVER", msg))))
        }
    }

    @Test fun `repeated reconnects - burst code or three drops in ten minutes`() {
        assertTrue("repeated_reconnects" in rules(input(line("14:58:00", 'W', "RECEIVER", "RECEIVER_RECONNECTS count=5 windowS=600"))))
        val three = input(
            line("14:41:00", 'W', "RECEIVER", "External receiver link down: reconnecting attempt=1 upS=60"),
            line("14:44:00", 'W', "RECEIVER", "External receiver link down: reconnecting attempt=1 upS=60"),
            line("14:49:00", 'W', "RECEIVER", "External receiver link down: reconnecting attempt=1 upS=60"))
        assertTrue("repeated_reconnects" in rules(three))
        val two = input(
            line("14:41:00", 'W', "RECEIVER", "External receiver link down: reconnecting attempt=1 upS=60"),
            line("14:49:00", 'W', "RECEIVER", "External receiver link down: reconnecting attempt=1 upS=60"))
        assertFalse("repeated_reconnects" in rules(two))
    }

    // ── GNSS / RTK / corrections ──

    @Test fun `NMEA but no usable fix, and stale fix`() {
        assertTrue("nmea_no_fix" in rules(input(line("14:58:00", 'W', "GNSS", "GNSS_FEED state=NO_FIX"))))
        assertTrue("nmea_no_fix" in rules(input(line("14:58:00", 'I', "GNSS", "RTK status SINGLE → NONE sats=3 hdop=9.9 corrAgeBucket=none"))))
        assertTrue("gnss_stale" in rules(input(line("14:58:00", 'W', "GNSS", "GNSS_FEED state=STALE ageS=12"))))
        val atMark = find(input(incident = incident(IncidentContext(fixAgeMs = 9_000))), "gnss_stale")!!
        assertEquals("incidents.txt", atMark.evidence.single().file)
        assertTrue(atMark.incidentRelation!!.startsWith("POSSIBLE_CAUSE"))
    }

    @Test fun `RTK FIX lost 40 s before the incident is OBSERVED, with a POSSIBLE_CAUSE relation`() {
        for (msg in listOf("RTK_CHANGE from=FIX to=FLOAT conn=c7", "RTK status FIX → FLOAT sats=12 hdop=0.8 corrAgeBucket=ok")) {
            val f = find(input(line("14:54:20", 'W', "GNSS", msg), incident = incident()), "rtk_lost")
            assertNotNull(msg, f)
            assertEquals(Finding.Certainty.OBSERVED, f!!.certainty)
            assertTrue(f.title, f.title.contains("FIX → FLOAT"))
            assertEquals("POSSIBLE_CAUSE of the reported problem: RTK FIX was lost 40 s before it was marked (timing only, not proof)", f.incidentRelation)
        }
        // FIX regained: still reported, says so.
        val back = find(input(line("14:50:00", 'W', "GNSS", "RTK_CHANGE from=FIX to=FLOAT"),
            line("14:51:00", 'I', "GNSS", "RTK_CHANGE from=FLOAT to=FIX")), "rtk_lost")!!
        assertTrue(back.title.contains("FIX regained"))
    }

    @Test fun `corrections stale or missing`() {
        for (msg in listOf("CORRECTIONS_STATE state=STALE ageS=31", "CORRECTIONS_STATE state=NONE", "Corrections stream lost",
                "Corrections age over threshold thresholdS=10 ageS=12.0", "Correction age over 10 s (12.0 s)")) {
            assertTrue(msg, "corrections_stale" in rules(input(line("14:58:00", 'W', "CORRECTIONS", msg))))
        }
        assertTrue("corrections_stale" in rules(input(incident = incident(IncidentContext(correctionAgeS = 25.0)))))
        assertTrue("corrections_stale" in rules(input(live = LiveState("EXTERNAL_TCP", "FLOAT", "CONNECTED", 40.0, null, null))))
        assertFalse("corrections_stale" in rules(input(live = LiveState("EXTERNAL_TCP", "FIX", "CONNECTED", 1.0, null, null))))
    }

    // ── Permissions ──

    @Test fun `missing camera and location permissions - log, device state and incident context`() {
        assertTrue("camera_permission" in rules(input(line("14:58:00", 'W', "AR", "AR session start blocked: camera permission not granted"))))
        assertTrue("location_permission" in rules(input(line("14:58:00", 'W', "AR", "AR session start blocked: location permission not granted"))))
        val dev = DeviceHealth(permissions = mapOf("Camera" to false, "Fine location" to true))
        val r = rules(input(device = dev))
        assertTrue("camera_permission" in r)
        assertFalse("location_permission" in r)
        assertTrue("location_permission" in rules(input(incident = incident(IncidentContext(fineLocationPermission = false)))))
    }

    // ── AR / models ──

    @Test fun `ARCore unavailable`() {
        for (msg in listOf("AR_UNAVAILABLE reason=NOT_INSTALLED", "ARCore unavailable: AR cannot start availability=UNSUPPORTED_DEVICE_NOT_CAPABLE",
                "AR session resume failed: camera unavailable")) {
            assertTrue(msg, "ar_unavailable" in rules(input(line("14:58:00", 'E', "AR", msg))))
        }
    }

    @Test fun `AR tracking loss - structured, legacy and incident context`() {
        assertTrue("ar_tracking_lost" in rules(input(line("14:58:00", 'W', "AR", "AR_STATE camera=PAUSED earth=STOPPED reason=INSUFFICIENT_FEATURES ar=a3"))))
        assertTrue("ar_tracking_lost" in rules(input(line("14:58:00", 'I', "AR", "Camera tracking changed trackingState=PAUSED failureReason=INSUFFICIENT_LIGHT"))))
        assertFalse("ar_tracking_lost" in rules(input(line("14:58:00", 'I', "AR", "Camera tracking changed trackingState=TRACKING"))))
        assertTrue("ar_tracking_lost" in rules(input(incident = incident(IncidentContext(arSessionActive = true, arCameraTracking = "PAUSED")))))
    }

    @Test fun `geospatial unavailable or poor, and no network`() {
        for (msg in listOf("AR_GEO_ACCURACY level=POOR hAccM=12.0", "AR_STATE camera=TRACKING earth=PAUSED",
                "Earth tracking changed trackingState=PAUSED earthState=ENABLED",
                "AR_QUALITY GOOD → LOW hAccM=9.0 vAccM=4.0 yawDeg=12.0 earthTracking=TRACKING cameraTracking=TRACKING placement=PLACED modelsRendered=1 placedByTimeout=false")) {
            assertTrue(msg, "geospatial_poor" in rules(input(line("14:58:00", 'I', "AR", msg))))
        }
        val noNet = ReportFindings.evaluate(input(line("14:58:00", 'W', "AR", "Geospatial cannot localize: no network connection")))
        assertTrue(noNet.first { it.rule == "geospatial_poor" }.title.contains("no network"))
        assertEquals(Finding.Certainty.OBSERVED, noNet.first { it.rule == "network" }.certainty)
    }

    @Test fun `model load failures and missing files`() {
        for (msg in listOf("MODEL_LOAD result=FAILED reason=parse op=o1 ref=#abc123", "Model preload failed: GLB parse or asset creation failed key=k1",
                "Model GLB validation failed file=#9b03de.glb", "Model import rejected: unsupported file type file=#9b03de.obj")) {
            assertTrue(msg, "model_failed" in rules(input(line("14:58:00", 'E', "MODEL", msg))))
        }
        assertTrue("model_missing_file" in rules(input(line("14:58:00", 'W', "MODEL", "MODEL_MISSING_FILE ref=#abc123"))))
        assertTrue("model_missing_file" in rules(input(line("14:58:00", 'W', "MODEL", "Model viewer setup failed: model file missing or no path"))))
        val links = find(input(info = ReportInfo(missingModelLinks = 2)), "model_missing_file")!!
        assertEquals("coordinates.txt", links.evidence.single().file)
    }

    // ── Data, storage, crashes, network ──

    @Test fun `database integrity failure, unknown check and reset`() {
        val bad = find(input(data = DataHealth(12, emptyMap(), "*** in database main *** Page 5: btreeInitPage() returns error code 11")), "db_integrity")!!
        assertEquals(Finding.Severity.HIGH, bad.severity)
        assertEquals(Finding.Certainty.OBSERVED, bad.certainty)
        assertEquals("data-health.txt", bad.evidence.single().file)
        assertEquals(Finding.Certainty.UNKNOWN, find(input(data = DataHealth(12, emptyMap(), "failed: SQLiteException")), "db_integrity")!!.certainty)
        assertNull(find(input(data = DataHealth(12, emptyMap(), "ok")), "db_integrity"))
        assertTrue("db_reset" in rules(input(line("09:00:00", 'W', "DATA", "Database reset by destructive migration: saved data cleared version=12"))))
    }

    @Test fun `low storage by absolute and relative free space`() {
        val gb = 1024L * 1024 * 1024
        assertTrue("low_storage" in rules(input(device = DeviceHealth(storageFreeBytes = 300L * 1024 * 1024, storageTotalBytes = 128 * gb))))
        assertTrue("low_storage" in rules(input(device = DeviceHealth(storageFreeBytes = 4 * gb, storageTotalBytes = 100 * gb))))
        assertFalse("low_storage" in rules(input(device = DeviceHealth(storageFreeBytes = 12 * gb, storageTotalBytes = 128 * gb))))
        assertTrue("low_storage" in rules(input(incident = incident(IncidentContext(freeStorageBytes = 50L * 1024 * 1024)))))
        assertTrue("low_storage" in rules(input(live = LiveState(null, null, null, null, null, 80L * 1024 * 1024))))
    }

    @Test fun `previous-process crash and ANR`() {
        val crash = input(
            line("13:00:00", 'I', "APP", "SESSION_START session=aaa111 lastSessionClean=true"),
            line("13:30:00", 'E', "ERROR", "Uncaught exception (fatal) thread=main screen=nav_render_map session=aaa111 type=java.lang.IllegalStateException: boom"),
            "java.lang.IllegalStateException: boom",
            line("13:31:00", 'W', "APP", "SESSION_START session=bbb222 lastSessionClean=false previous=aaa111"),
        )
        val f = find(crash, "crash")!!
        assertEquals(Finding.Severity.CRITICAL, f.severity)
        assertTrue(f.title, f.title.startsWith("App crashed") && f.title.contains("previous process"))
        assertEquals("crash", ReportFindings.evaluate(crash).first().rule)          // ranked first

        val anr = find(input(line("13:31:01", 'W', "APP", "Previous process exit: reason=ANR status=0 importance=100 description=anr")), "crash")!!
        assertTrue(anr.title, anr.title.startsWith("App not responding (ANR)"))

        // The problem was marked in the session that then crashed.
        val inCrashed = find(input(
            line("14:56:00", 'E', "ERROR", "Uncaught exception (fatal) thread=main screen=x session=aaa111 type=java.lang.IllegalStateException: boom"),
            incident = incident(session = "aaa111")), "crash")!!
        assertTrue(inCrashed.incidentRelation!!.contains("session the problem was marked in ended in this crash"))
    }

    @Test fun `network - offline is observed, no internet is possible, missing is unknown`() {
        val off = find(input(device = DeviceHealth(network = "none")), "network")!!
        assertEquals(Finding.Certainty.OBSERVED, off.certainty)
        assertTrue(off.title.contains("No network connection at export"))
        assertEquals(Finding.Certainty.POSSIBLE_CAUSE, find(input(device = DeviceHealth(network = "Wi-Fi validated=false")), "network")!!.certainty)
        val unknown = find(input(line("14:58:00", 'W', "CORRECTIONS", "Corrections stream lost"), device = DeviceHealth(network = null)), "network")!!
        assertEquals(Finding.Certainty.UNKNOWN, unknown.certainty)
        assertNull(find(input(device = DeviceHealth(network = "Wi-Fi validated=true")), "network"))
    }

    @Test fun `coded lines with the old sentence in parentheses, and moved tags`() {
        val coded = input(line("14:58:00", 'W', "GNSS", "RTK_CHANGE from=FIX to=FLOAT conn=c7 (RTK status FIX → FLOAT sats=12 to=FIX)"))
        val f = find(coded, "rtk_lost")!!
        assertTrue(f.title, f.title.contains("FIX → FLOAT"))
        assertTrue("corrections_stale" in rules(input(line("14:58:00", 'W', "GNSS", "CORRECTIONS_STATE state=STALE ageS=31 via=nmea (Corrections age over threshold thresholdS=10 ageS=31.0)"))))
        assertTrue("receiver_down" in rules(input(line("14:58:00", 'W', "RECEIVER",
            "RECEIVER_LINK state=DISCONNECTED conn=c7 (External receiver link down: reconnecting attempt=1 upS=300 state=CONNECTED)"))))
        // Model lines under MODEL (new) and AR (older builds).
        for (tag in listOf("MODEL", "AR")) {
            assertTrue(tag, "model_missing_file" in rules(input(line("14:58:00", 'W', tag, "Model skipped: file missing key=k1"))))
            assertTrue(tag, "model_failed" in rules(input(line("14:58:00", 'E', tag, "Model preload failed key=k1"))))
        }
        assertTrue("model_missing_file" in rules(input(line("14:58:00", 'W', "MODEL", "MODEL_MISSING_FILE ref=#a op=op3 (Model skipped: file missing)"))))
    }

    // ── Focus, relation, ranking, cap ──

    @Test fun `with an incident only its window counts, and events after it are not causes`() {
        val old = input(line("14:30:00", 'W', "GNSS", "RTK_CHANGE from=FIX to=FLOAT"), incident = incident())
        assertNull(find(old, "rtk_lost"))                                              // 25 min before: outside the focus
        val after = find(input(line("14:56:00", 'W', "GNSS", "RTK_CHANGE from=FIX to=FLOAT"), incident = incident()), "rtk_lost")!!
        assertEquals("Observed after the problem was marked; not a cause of it", after.incidentRelation)
        val early = find(input(line("14:45:00", 'W', "GNSS", "RTK_CHANGE from=FIX to=FLOAT"),
            line("14:46:00", 'I', "GNSS", "RTK_CHANGE from=FLOAT to=FIX"), incident = incident()), "rtk_lost")!!
        assertNull(early.incidentRelation)                                             // 10 min before and recovered: no relation claimed
    }

    @Test fun `without an incident the focus is the current session`() {
        val i = input(
            line("12:00:00", 'W', "GNSS", "RTK_CHANGE from=FIX to=FLOAT"),
            line("13:31:00", 'I', "APP", "SESSION_START session=bbb222"),
            line("14:00:00", 'W', "CORRECTIONS", "Corrections stream lost"))
        assertEquals(ms("13:31:00")..now, ReportFindings.focus(i))
        val r = rules(i)
        assertFalse("rtk_lost" in r)
        assertTrue("corrections_stale" in r)
    }

    @Test fun `findings are capped and ranked by severity`() {
        val i = input(
            line("13:00:00", 'I', "APP", "SESSION_START session=bbb222"),
            line("14:51:00", 'W', "APP", "Previous process exit: reason=CRASH status=0"),
            line("14:52:00", 'W', "RECEIVER", "RECEIVER_LINK state=DISCONNECTED"),
            line("14:52:10", 'W', "RECEIVER", "RECEIVER_LINK state=NO_DATA"),
            line("14:52:20", 'W', "GNSS", "GNSS_FEED state=NO_FIX"),
            line("14:52:30", 'W', "GNSS", "GNSS_FEED state=STALE"),
            line("14:52:40", 'W', "GNSS", "RTK_CHANGE from=FIX to=FLOAT"),
            line("14:52:50", 'W', "CORRECTIONS", "CORRECTIONS_STATE state=STALE"),
            line("14:53:00", 'E', "AR", "AR_UNAVAILABLE reason=CAMERA_UNAVAILABLE"),
            line("14:53:10", 'W', "AR", "AR_STATE camera=PAUSED earth=PAUSED"),
            line("14:53:20", 'E', "MODEL", "MODEL_LOAD result=FAILED reason=parse"),
            line("14:53:30", 'W', "MODEL", "MODEL_MISSING_FILE ref=#a"),
            incident = incident(), device = DeviceHealth(network = "none", permissions = mapOf("Camera" to false)))
        val f = ReportFindings.evaluate(i)
        assertEquals(ReportFindings.MAX_FINDINGS, f.size)
        assertEquals("crash", f.first().rule)
        assertEquals(f.map { it.severity }.sortedBy { it.ordinal }, f.map { it.severity })
    }

    @Test fun `subsystem status has one line per subsystem`() {
        val s = ReportFindings.subsystemStatus(input(line("14:58:00", 'W', "RECEIVER", "RECEIVER_LINK state=DISCONNECTED"),
            live = LiveState("EXTERNAL_TCP", "FIX", "CONNECTED", 1.2, "receiving", 12L shl 30),
            device = DeviceHealth(network = "none", permissions = mapOf("Camera" to false, "Fine location" to true)),
            data = DataHealth(12, emptyMap(), "ok")))
        assertEquals(listOf("GNSS", "Receiver connection", "RTK corrections", "AR tracking", "Models", "Database & storage", "Permissions", "Network"),
            s.map { it.first })
        val m = s.toMap()
        assertTrue(m.getValue("GNSS"), m.getValue("GNSS").startsWith("source=EXTERNAL_TCP rtk=FIX"))
        assertTrue(m.getValue("Receiver connection").contains("1 drop(s)"))
        assertTrue(m.getValue("Permissions"), m.getValue("Permissions").contains("camera=DENIED fineLocation=granted"))
        assertTrue(m.getValue("Database & storage").contains("integrity=ok"))
        assertEquals("none", m.getValue("Network"))
    }

    @Test fun `evidence timestamps render in the device zone and point at the source file`() {
        val e = ReportFindings.ev(entries(listOf(line("14:58:00", 'W', "GNSS", "RTK_CHANGE from=FIX to=FLOAT"))).single())
        assertEquals(ms("14:58:00"), e.epochMs)
        assertEquals("W GNSS RTK_CHANGE from=FIX to=FLOAT", e.text)
        assertNotNull(LogLineFormat.timestamp(e.epochMs!!))
    }

    // ── AR placement and GNSS configuration (codes added by the logging call sites) ──

    @Test fun `source mismatch names the selected and active source`() {
        val f = find(input(line("14:50:00", 'W', "GNSS", "SOURCE_MISMATCH selected=EXTERNAL active=INTERNAL reason=external_not_configured")),
            "source_mismatch")
        assertNotNull(f)
        assertTrue(f!!.title, f.title.contains("selected=EXTERNAL") && f.title.contains("active=INTERNAL"))
        assertEquals(Finding.Certainty.OBSERVED, f.certainty)
    }

    @Test fun `antenna height problem lists its reasons`() {
        val f = find(input(line("14:50:00", 'W', "GNSS", "ANTENNA_HEIGHT reason=MISSING detail=poleHeightZero mode=EXTERNAL capture=cap5")),
            "antenna_height")
        assertTrue(f!!.title, f.title.contains("MISSING"))
    }

    @Test fun `anchor failures outrank delays, coded and legacy lines both count`() {
        val delayedOnly = find(input(line("14:50:00", 'I', "AR", "AR_ANCHOR result=DELAYED reason=AR_LOCALIZING afterS=14 ar=a2 (Anchors waiting)")), "ar_anchor")
        assertEquals(Finding.Severity.LOW, delayedOnly!!.severity)
        assertTrue(delayedOnly.title, delayedOnly.title.startsWith("AR anchors delayed") && delayedOnly.title.contains("AR_LOCALIZING"))
        val failed = find(input(
            line("14:50:00", 'I', "AR", "AR_ANCHOR result=DELAYED reason=AR_LOCALIZING ar=a2"),
            line("14:51:00", 'W', "AR", "Anchor creation failed"),          // legacy sentence (older build)
        ), "ar_anchor")
        assertEquals(Finding.Severity.MEDIUM, failed!!.severity)
        assertTrue(failed.title, failed.title.startsWith("AR anchors failed (1x)"))
        // An "I" line with the legacy sentence is not a failure (warnings/errors only).
        assertNull(find(input(line("14:51:00", 'I', "AR", "Anchor creation failed: retrying")), "ar_anchor"))
    }

    @Test fun `hidden models report their reasons, and hidden=0 is not a hidden model`() {
        val coded = find(input(
            line("14:50:00", 'I', "AR", "AR_MODEL_HIDDEN reason=OFFSCREEN count=2 ar=a2"),
            line("14:52:00", 'I', "AR", "AR_MODEL_HIDDEN reason=DISTANCE count=1 ar=a2 maxDistanceM=200 hidden=1 (Models hidden by distance filter)"),
        ), "ar_model_hidden")
        assertTrue(coded!!.title, coded.title.contains("OFFSCREEN") && coded.title.contains("DISTANCE"))
        // Older builds: the sentence with a count; with hidden=0 the filter stopped hiding anything.
        assertNotNull(find(input(line("14:50:00", 'I', "AR", "Models hidden by distance filter maxDistanceM=200 hidden=3 ar=a1")), "ar_model_hidden"))
        assertNull(find(input(line("14:50:00", 'I', "AR", "Models hidden by distance filter maxDistanceM=200 hidden=0 ar=a1")), "ar_model_hidden"))
    }

    @Test fun `alignment jumps report the largest jump`() {
        val f = find(input(
            line("14:50:00", 'W', "AR", "AR_ALIGNMENT_JUMP deltaM=2.40 deltaVertM=+0.10 horizM=2.30 rtk=FIX ar=a2"),
            line("14:53:00", 'W', "AR", "AR_ALIGNMENT_JUMP deltaM=5.31 deltaVertM=+1.00 horizM=4.00 rtk=FLOAT ar=a2"),
            incident = incident(),
        ), "ar_alignment_jump")
        assertTrue(f!!.title, f.title.contains("2x") && f.title.contains("up to 5.3 m"))
        assertTrue("timing relation, not a root cause: ${f.incidentRelation}", f.incidentRelation!!.contains("timing only"))
    }

    @Test fun `points without altitude are reported as placed on terrain`() {
        val f = find(input(line("14:50:00", 'W', "AR", "Anchor height unavailable: no stored height and no fix height, using terrain")), "ar_no_altitude")
        assertTrue(f!!.title, f.title.contains("placed on the terrain"))
        assertEquals(Finding.Severity.LOW, f.severity)
    }
}
