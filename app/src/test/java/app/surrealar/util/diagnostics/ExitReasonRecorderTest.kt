package app.surrealar.util.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExitReasonRecorderTest {

    @get:Rule val tmp = TemporaryFolder()
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("exit-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    private fun exit(ts: Long, reason: Int, trace: String? = null) = ExitReasonRecorder.Exit(
        ts, reason, status = 0, importance = 100, pssKb = 1000, rssKb = 2000, description = "d$ts",
        trace = { trace?.byteInputStream() },
    )

    @Test fun `exits are recorded once and a second start does not duplicate them`() {
        val lines = mutableListOf<String>()
        val dir = tmp.newFolder("exits")
        val rec = ExitReasonRecorder(dir, prefs) { _, m -> lines += m }
        val exits = listOf(exit(2_000, ExitReasonRecorder.REASON_ANR, "ANR trace"), exit(1_000, 10))
        assertEquals(2, rec.record(exits))
        assertEquals(0, ExitReasonRecorder(dir, prefs) { _, m -> lines += m }.record(exits))
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("reason=USER_REQUESTED"))
        assertTrue(lines[1].contains("reason=ANR") && lines[1].contains("pssKb=1000 rssKb=2000"))
        // A newer exit later on is still picked up.
        assertEquals(1, rec.record(exits + exit(3_000, ExitReasonRecorder.REASON_CRASH)))
    }

    @Test fun `the system exit description survives the report redactor`() {
        val lines = mutableListOf<String>()
        val rec = ExitReasonRecorder(tmp.newFolder("exits"), prefs) { _, m -> lines += m }
        rec.record(listOf(ExitReasonRecorder.Exit(1_000, ExitReasonRecorder.REASON_ANR, 0, 100, 1000, 2000,
            description = "Input dispatching timed out", trace = { null })))
        val scrubbed = app.surrealar.util.diagnostics.report.ReportRedactor.scrub(lines.single())
        // A description= value is hidden as user text; this is Android's text and is crash evidence.
        assertTrue(scrubbed, scrubbed.contains("exitDescription=\"Input dispatching timed out\""))
    }

    @Test fun `ANR and native traces are saved and only the newest 5 kept`() {
        val dir = tmp.newFolder("exits")
        val rec = ExitReasonRecorder(dir, prefs) { _, _ -> }
        rec.record((1..7).map { exit(it * 1_000L, if (it % 2 == 0) 6 else 5, "trace $it") } + exit(9_000, 4, "java crash"))
        val files = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(5, files.size)
        assertTrue(files.all { it.endsWith("-ANR.txt") || it.endsWith("-CRASH_NATIVE.txt") })
    }
}