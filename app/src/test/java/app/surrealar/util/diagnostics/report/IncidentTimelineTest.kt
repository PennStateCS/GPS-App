package app.surrealar.util.diagnostics.report

import app.surrealar.util.diagnostics.incident.IncidentContext
import app.surrealar.util.diagnostics.incident.IncidentMarker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.OffsetDateTime
import java.util.TimeZone

/** Incident windows from the single log pass: bounds, cap, several incidents in different sessions; timeline highlighting. */
class IncidentTimelineTest {

    @get:Rule val tmp = TemporaryFolder()
    private val tz = TimeZone.getDefault()
    @Before fun ny() = TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    @After fun restore() = TimeZone.setDefault(tz)

    private val now = ms("15:00:00")
    private fun ms(hms: String) = OffsetDateTime.parse("2026-11-21T$hms-05:00").toInstant().toEpochMilli()
    private fun line(hms: String, level: Char, tag: String, msg: String) = "2026-11-21T$hms.000-05:00 $level $tag [main] $msg"

    private fun marker(id: String, hms: String, session: String?, note: String? = null) = IncidentMarker(
        id = id, epochMs = ms(hms), utcTime = "u", localTime = "l", zoneId = "America/New_York", sessionId = session,
        appVersion = "1.4", screen = "nav_open_in_ar", description = note, context = IncidentContext(rtkStatus = "FLOAT"),
    )

    private fun index(lines: List<String>, incidents: List<IncidentMarker>): LogIndex {
        val f = File(tmp.root, "app-log-current.txt").apply { writeText(lines.joinToString("\n") + "\n") }
        return LogIndex.build(listOf(f), now, incidents.map { it.epochMs })
    }

    @Test fun `window is 5 min before to 2 min after, inclusive`() {
        val inc = marker("INC1", "14:30:00", "s2")
        val idx = index(listOf(
            line("14:24:59", 'I', "NAV", "Screen too_early"),
            line("14:25:00", 'I', "NAV", "Screen first_in"),
            line("14:29:30", 'W', "GNSS", "RTK_CHANGE from=FIX to=FLOAT"),
            line("14:32:00", 'I', "NAV", "Screen last_in"),
            line("14:32:01", 'I', "NAV", "Screen too_late"),
        ), listOf(inc))
        val w = idx.incidentWindows.single()
        assertEquals(listOf("Screen first_in", "RTK_CHANGE from=FIX to=FLOAT", "Screen last_in"), w.entries.map { it.message })
        assertEquals(0, w.omitted)
    }

    @Test fun `over the cap key lines are kept first, chronological order is kept`() {
        val inc = marker("INC1", "14:30:00", "s2")
        val lines = mutableListOf<String>()
        // 300 ordinary lines (every second from 14:26:00) and 5 warnings spread through the window.
        for (i in 0 until 300) lines += line("14:%02d:%02d".format(26 + i / 60, i % 60), 'I', "SETTINGS", "Setting changed n=$i")
        listOf("14:25:10", "14:27:30", "14:29:59", "14:30:30", "14:31:50").forEach { lines += line(it, 'W', "RECEIVER", "RECEIVER_LINK state=NO_DATA at=$it") }
        val idx = index(lines.sortedBy { it.substring(0, 29) }, listOf(inc))
        val w = idx.incidentWindows.single()
        assertEquals(LogIndex.MAX_INCIDENT_LINES, w.entries.size)
        assertEquals(305 - LogIndex.MAX_INCIDENT_LINES, w.omitted)
        assertEquals(5, w.entries.count { it.level == 'W' })                   // every warning survives
        assertEquals(w.entries.sortedBy { it.epochMs }, w.entries)
        // Ordinary lines kept are the ones nearest the mark.
        val kept = w.entries.filter { it.level == 'I' }.map { it.epochMs }
        assertTrue(kept.maxOf { kotlin.math.abs(it - inc.epochMs) } <= kept.size * 1_000L)
    }

    @Test fun `several incidents in different sessions each get their window and session label`() {
        val older = marker("OLD00001", "13:20:00", "aaa111", note = "first problem")
        val newer = marker("NEW00002", "14:50:00", "bbb222", note = "AR models jumped")
        val idx = index(listOf(
            line("13:00:00", 'I', "APP", "SESSION_START session=aaa111"),
            line("13:18:00", 'W', "RECEIVER", "External receiver link down: reconnecting attempt=1 upS=60"),
            line("13:31:00", 'I', "APP", "SESSION_START session=bbb222"),
            line("14:49:00", 'W', "GNSS", "RTK status FIX → FLOAT sats=9 hdop=1.2 corrAgeBucket=stale"),
            line("14:51:00", 'I', "AR", "AR_STATE camera=TRACKING earth=TRACKING ar=a1"),
        ), listOf(newer, older))
        assertEquals("bbb222", idx.lastSessionId())
        val text = IncidentsCollector.render(listOf(newer, older), idx, "bbb222")
        assertTrue(text, text.contains("--- Latest: incident NEW00002 (current session) ---"))
        assertTrue(text, text.contains("--- #2: incident OLD00001 (earlier session (session=aaa111)) ---"))
        assertTrue(text, text.contains("description: \"AR models jumped\""))
        // Each window holds only its own lines, and the mark sits in time order.
        val newBlock = text.substringAfter("incident NEW00002").substringBefore("incident OLD00001")
        val oldBlock = text.substringAfter("--- #2: incident OLD00001")
        assertTrue(newBlock.contains("RTK status FIX → FLOAT") && !newBlock.contains("External receiver link down"))
        assertTrue(oldBlock.contains("External receiver link down") && !oldBlock.contains("RTK status"))
        val lines = newBlock.lines()
        val markAt = lines.indexOfFirst { it.startsWith(">> ") && it.contains("PROBLEM MARKED here: incident NEW00002") }
        assertTrue(markAt > lines.indexOfFirst { it.contains("RTK status FIX → FLOAT") })
        assertTrue(markAt < lines.indexOfFirst { it.contains("AR_STATE") })
    }

    @Test fun `no incidents says so`() {
        assertTrue(IncidentsCollector.render(emptyList(), LogIndex(now).finish(), null).contains("No problem was marked"))
    }

    @Test fun `timeline marks lines in an incident window and the mark itself, keeping chronology`() {
        val inc = marker("INC1", "14:30:00", "s2")
        val idx = index(listOf(
            line("14:00:00", 'W', "GNSS", "RTK_CHANGE from=FIX to=FLOAT"),
            line("14:28:00", 'I', "MODEL", "Model preload started key=k1"),
            line("14:31:00", 'E', "AR", "AR_UNAVAILABLE reason=CAMERA_UNAVAILABLE"),
            line("14:40:00", 'I', "DATA", "Backup export completed rows=3"),
            line("14:41:00", 'I', "SETTINGS", "Setting x: a → b"),                   // not a timeline tag
        ), listOf(inc))
        val body = TimelineCollector.render(idx, listOf(inc)).lines().filter { it.contains("2026-11-21T") }
        assertEquals(listOf(
            "2026-11-21T14:00:00.000-05:00 W GNSS [main] RTK_CHANGE from=FIX to=FLOAT",
            ">> 2026-11-21T14:28:00.000-05:00 I MODEL [main] Model preload started key=k1",
            ">> 2026-11-21T14:30:00.000-05:00 ===== PROBLEM MARKED here: incident INC1 screen=nav_open_in_ar =====",
            ">> 2026-11-21T14:31:00.000-05:00 E AR [main] AR_UNAVAILABLE reason=CAMERA_UNAVAILABLE",
            "2026-11-21T14:40:00.000-05:00 I DATA [main] Backup export completed rows=3",
        ), body)
        assertFalse(TimelineCollector.render(idx).contains(">> "))
    }

    @Test fun `timeline is bounded, dropping the oldest ordinary lines first`() {
        val lines = mutableListOf<String>()
        for (i in 0 until LogIndex.MAX_TIMELINE + 500) lines += line("%02d:%02d:%02d".format(13 + i / 3600, (i / 60) % 60, i % 60), 'I', "NAV", "Screen s$i")
        lines += line("13:00:01", 'W', "RECEIVER", "old warning kept")
        val idx = index(lines.sortedBy { it.substring(0, 29) }, emptyList())
        assertEquals(LogIndex.MAX_TIMELINE, idx.timeline.size)
        assertTrue(idx.timeline.any { it.message == "old warning kept" })
        assertEquals("Screen s${LogIndex.MAX_TIMELINE + 499}", idx.timeline.last().message)
        assertTrue(TimelineCollector.render(idx).contains("older ordinary lines left out"))
    }
}
