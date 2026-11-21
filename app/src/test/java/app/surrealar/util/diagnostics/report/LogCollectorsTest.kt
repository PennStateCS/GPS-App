package app.surrealar.util.diagnostics.report

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.TimeZone

/** Golden tests for the log-based report files. */
class LogCollectorsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val tz = TimeZone.getDefault()
    @Before fun ny() = TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    @After fun restore() = TimeZone.setDefault(tz)

    private fun idx(): LogIndex { val s = FakeReportSources(tmp.newFolder("logs")); return LogIndex.build(s.logFiles() + s.errorFiles(), s.nowMs) }

    @Test fun `golden timeline - last 2 hours, story tags, warnings, crash once with its trace`() {
        assertEquals("""
            === Timeline (last 2 hours) ===
            Warnings, errors and LIFECYCLE / NAV / RECEIVER / CORRECTIONS / CAPTURE / SESSION lines from all log files.

            2026-11-21T13:00:00.000-05:00 I APP [main] SESSION_START session=aaa111 install=i-1 version=1.4 code=14 build=debug commit=abc device="Samsung SM-X940" android=15 sdk=35 locale=en-US tz=America/New_York lastSessionClean=unknown(first run) previous=none
            2026-11-21T13:05:00.000-05:00 I LIFECYCLE [main] App foreground
            2026-11-21T13:05:01.000-05:00 I NAV [main] Screen nav_home
            2026-11-21T13:10:00.000-05:00 W RECEIVER [diag] Link down: reconnecting (attempt 1), was up 300s
            2026-11-21T13:10:05.000-05:00 I RECEIVER [diag] Link up after 2 attempts
            2026-11-21T13:30:00.000-05:00 E ERROR [main] Uncaught exception (fatal) thread=main screen=nav_render_map session=aaa111 type=java.lang.IllegalStateException: boom
            java.lang.IllegalStateException: boom
            	at app.surrealar.Foo.bar(Foo.kt:1)
            2026-11-21T13:31:00.000-05:00 W APP [main] SESSION_START session=bbb222 install=i-1 version=1.4 code=14 build=debug commit=abc device="Samsung SM-X940" android=15 sdk=35 locale=en-US tz=America/New_York lastSessionClean=false previous=aaa111
            2026-11-21T13:31:01.000-05:00 W APP [diag-exits] Previous process exit: reason=CRASH status=0 importance=100 pss=1000kB rss=2000kB at=2026-11-21T13:30:00.100-05:00 description=crash
            2026-11-21T14:00:00.000-05:00 W CORRECTIONS [diag] Corrections stream lost
            2026-11-21T14:00:01.000-05:00 W GNSS [diag] Correction age over 10 s (12.0 s)
            2026-11-21T14:00:30.000-05:00 W GNSS [diag] Correction age over 10 s (14.0 s)
            2026-11-21T14:10:00.000-05:00 I CAPTURE [main] Capture saved name="CP-1" mode=EXTERNAL samples=150 duration=60s rtk=FIX hAcc=<2cm vAcc=<5cm rejected=0/150

        """.trimIndent().replace("\n\n2026", "\n\n2026") + "\n", TimelineCollector.render(idx()).trimEnd() + "\n\n")
    }

}