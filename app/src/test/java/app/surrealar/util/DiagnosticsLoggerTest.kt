package app.surrealar.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DiagnosticsLoggerTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File

    @Before fun setUp() { dir = tmp.newFolder("logs"); DiagnosticsLogger.initForTest(dir) }
    @After fun tearDown() { DiagnosticsLogger.currentScreen = null; DiagnosticsLogger.sessionId = null }

    @Test fun `crash write is on disk as soon as the handler returns`() {
        DiagnosticsLogger.currentScreen = "homeFragment"
        DiagnosticsLogger.sessionId = "s-1234"
        val t = Thread(null, {}, "worker-7")
        val crash = IllegalStateException("outer", IllegalArgumentException("root cause"))
        DiagnosticsLogger.writeCrashSync(t, crash, fatal = true)
        // No flush, no waiting: the write must already be there.
        for (name in listOf("app-errors-current.txt", "app-log-current.txt")) {
            val text = File(dir, name).readText()
            assertTrue(name, text.contains("thread=worker-7"))
            assertTrue(name, text.contains("screen=homeFragment"))
            assertTrue(name, text.contains("session=s-1234"))
            assertTrue(name, text.contains("IllegalStateException: outer"))
            assertTrue("cause included in $name", text.contains("Caused by: java.lang.IllegalArgumentException: root cause"))
        }
    }

    @Test fun `every written line is in the new format`() {
        DiagnosticsLogger.w("RECEIVER", "TCP: no data for 10000ms")
        assertTrue(DiagnosticsLogger.flush())
        val p = app.surrealar.util.diagnostics.LogLineFormat.parse(File(dir, "app-log-current.txt").readLines().last())!!
        assertTrue(p.level == 'W' && p.tag == "RECEIVER" && p.message == "TCP: no data for 10000ms")
        assertTrue(p.thread, p.thread.isNotBlank())
    }

    private fun lines(): List<String> { DiagnosticsLogger.flush(); return File(dir, "app-log-current.txt").takeIf { it.exists() }?.readLines() ?: emptyList() }

    @Test fun `logOnChange writes only when the value changes, and only builds the message then`() {
        DiagnosticsLogger.resetThrottles()
        var built = 0
        listOf("FIX", "FIX", "FLOAT", "FLOAT", null, null, "FIX").forEach { v ->
            DiagnosticsLogger.logOnChange("GNSS", "test.rtk", v) { built++; "rtk=$v" }
        }
        assertEquals(listOf("rtk=FIX", "rtk=FLOAT", "rtk=null", "rtk=FIX"), lines().map { it.substringAfter("] ") }.filter { it.startsWith("rtk=") })
        assertEquals(4, built)
    }

    @Test fun `every writes at most once per interval per key`() {
        DiagnosticsLogger.resetThrottles()
        var now = 0L
        DiagnosticsLogger.nowMs = { now }
        try {
            for (t in 0L..250_000L step 1_000L) {
                now = t
                DiagnosticsLogger.every("GNSS", "test.hb", 60_000L) { "hb $t" }
                DiagnosticsLogger.every("GNSS", "test.other", 100_000L) { "other $t" }
            }
        } finally { DiagnosticsLogger.nowMs = { System.nanoTime() / 1_000_000L } }
        assertEquals(listOf("hb 0", "other 0", "hb 60000", "other 100000", "hb 120000", "hb 180000", "other 200000", "hb 240000"),
            lines().map { it.substringAfter("] ") }.filter { it.startsWith("hb ") || it.startsWith("other ") })
    }

    @Test fun `flush returns false at once when the write queue is full`() {
        val release = java.util.concurrent.CountDownLatch(1)
        DiagnosticsLogger.blockWriterForTest(release)
        try {
            repeat(DiagnosticsLogger.MAX_QUEUED + 5) { DiagnosticsLogger.i("TEST", "fill $it") }
            val start = System.nanoTime()
            val ok = DiagnosticsLogger.flush(timeoutMs = 5_000L)
            val tookMs = (System.nanoTime() - start) / 1_000_000L
            assertTrue("flush must report failure", !ok)
            assertTrue("flush returned in ${tookMs} ms", tookMs < 1_000L)
        } finally {
            release.countDown()
        }
        // Right after the release the queue is still full, so flush is (correctly) refused until the
        // writer has made room. Retry for up to two minutes.
        val deadline = System.nanoTime() + 120_000_000_000L
        var drained = false
        while (!drained && System.nanoTime() < deadline) {
            drained = DiagnosticsLogger.flush(timeoutMs = 120_000L)
            if (!drained) Thread.sleep(50)
        }
        assertTrue("drains once the writer runs again", drained)
        assertTrue(File(dir, "app-log-current.txt").readText().contains("log lines dropped"))
    }

    @Test fun `coroutine crash is marked not fatal`() {
        DiagnosticsLogger.writeCrashSync(Thread.currentThread(), RuntimeException("x"), fatal = false)
        assertTrue(File(dir, "app-errors-current.txt").readText().contains("not fatal"))
    }
}