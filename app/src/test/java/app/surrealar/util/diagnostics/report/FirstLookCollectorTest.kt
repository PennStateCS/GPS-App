package app.surrealar.util.diagnostics.report

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.TimeZone

class FirstLookCollectorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val tz = TimeZone.getDefault()
    @Before fun ny() = TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    @After fun restore() = TimeZone.setDefault(tz)

    @Test fun `golden first look`() {
        val src = FakeReportSources(tmp.newFolder("logs"))
        val idx = LogIndex.build(src.logFiles() + src.errorFiles(), src.nowMs)
        assertEquals("""
            === First look ===
            Report ID          : 7K3M-QX2D
            Time span covered  : 2026-11-21T13:00:00.000-05:00 → 2026-11-21T14:20:00.000-05:00 (1.3 h)
            Sessions           : 2
            Last session clean : NO — the session before this one ended in a crash, ANR or kill while in use
            Crashes/ANRs (24h) : 2 — newest: Previous process exit: reason=CRASH status=0 importance=100 pss=1000kB rss=2000kB at=2026-11-21T13:30:00.100-05:00 description=crash
            Source / RTK       : EXTERNAL_TCP / FIX
            Receiver link      : CONNECTED
            Correction age     : 1.2 s (receiving station=PAWB age=1.2s)
            Free storage       : 12.0 GB
            Top warnings/errors (24h):
                  2 × W GNSS Correction age over 10 s (12.0 s)
                  1 × E ERROR Uncaught exception (fatal) thread=main screen=nav_render_map session=aaa111 type=java.lang.IllegalStateException: boom
                  1 × W APP Previous process exit: reason=CRASH status=0 importance=100 pss=1000kB rss=2000kB at=2026-11-21T13:30:00.100-05:00 description=crash
                  1 × W APP SESSION_START session=bbb222 install=i-1 version=1.4 code=14 build=debug commit=abc device="Samsung SM-X940" android=15 sdk=35 locale=en-US tz=America/New
                  1 × W CORRECTIONS Corrections stream lost

        """.trimIndent() + "\n", FirstLookCollector.render(src.reportId, idx, src.liveState()))
    }
}