package app.surrealar.gnss.diagnostics.raw

import app.surrealar.gnss.diagnostics.raw.RawNmeaRecorderTest.Companion.gga
import app.surrealar.gnss.diagnostics.raw.RawNmeaRecorderTest.Companion.nmea
import app.surrealar.gnss.diagnostics.raw.RawNmeaRecorderTest.Companion.rmc
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaFuser
import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter.ConnectionState
import io.github.pennstatecs.geokit.gnss.link.TcpNmeaSource
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.nmea.parse.DefaultNmeaRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * The recording tap does not change GNSS processing: the existing hooks still run (same lines, same order),
 * the tap never blocks, and fusion output for an existing Emlid fixture is identical with and without a
 * recording. Also shows where line framing happens (TcpNmeaSource's BufferedReader, before the hook).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RawNmeaTapsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val externalMeta = RawNmeaSourceMetadata(profileKey = "p", profileLabel = "Profile", fileLabel = "RS4 Pro")

    private fun TestScope.recorder(capacity: Int): RawNmeaRecorder = RawNmeaRecorder(
        scope = detachedScope(),
        ioDispatcher = StandardTestDispatcher(testScheduler),
        files = RawNmeaFiles { File(tmp.root, "rec") },
        metadata = { externalMeta },
        monoClockMs = { testScheduler.currentTime },
        zone = { ZoneId.of("UTC") },
        queueCapacity = capacity,
        statsPublishIntervalMs = 0,
    ).also {
        it.watchSource(MutableStateFlow(ProviderChoice.EXTERNAL_TCP), MutableStateFlow(ConnectionState.CONNECTED), MutableStateFlow("p"))
        runCurrent()
    }

    @Test(timeout = 10_000)
    fun `existing hooks still get every line in order, and a full queue never blocks the caller`() = runTest {
        val r = recorder(capacity = 1)
        val calls = mutableListOf<String>()
        val hook = RawNmeaTaps.externalOnLine(r, { calls += "diag:$it" }, { calls += "tilt:$it" })
        r.start(RawNmeaDuration.MANUAL)
        val lines = (0 until 10_000).map { gga("12%04d.00".format(it % 10000), "4118.0") }
        // The writer never runs during this loop (no runCurrent), so the queue is full after one line: a
        // blocking send would hang here (test timeout).
        lines.forEach(hook)
        assertEquals(lines.flatMap { listOf("diag:$it", "tilt:$it") }, calls)
        advanceUntilIdle()
        val res = (r.state.value as RawNmeaState.Finished).result
        assertEquals(RawNmeaTermination.BUFFER_OVERFLOW, res.termination)
        assertEquals(1L, res.stats.droppedLines)
    }

    @Test fun `a hook that throws later in the chain does not stop the line being recorded`() = runTest {
        val r = recorder(capacity = 16)
        val hook = RawNmeaTaps.externalOnLine(r, { throw IllegalStateException("SYNTHETIC") }, { })
        r.start(RawNmeaDuration.MANUAL)
        val line = gga("120000.00", "4118.0")
        hook(line)                                         // never throws (stabilization item 8)
        r.stop(); advanceUntilIdle()
        assertEquals("$line\r\n", (r.state.value as RawNmeaState.Finished).result.nmeaFile.readText())
    }

    @Test fun `each hook is isolated - a throwing hook neither throws out nor skips the hooks after it`() = runTest {
        val r = recorder(capacity = 64)
        val calls = mutableListOf<String>()
        val hook = RawNmeaTaps.externalOnLine(
            r,
            recordDiagnostics = { calls += "diag:$it"; if (it.contains("120000.20")) throw IllegalStateException("SYNTHETIC diag") },
            tiltMonitor = { calls += "tilt:$it"; throw RuntimeException("SYNTHETIC tilt") },
        )
        r.start(RawNmeaDuration.MANUAL)
        val lines = listOf(gga("120000.00", "4118.0"), gga("120000.20", "4118.0"), gga("120000.40", "4118.0"))
        lines.forEach(hook)                                 // a throw here would fail the test
        assertEquals(lines.flatMap { listOf("diag:$it", "tilt:$it") }, calls)
        r.stop(); advanceUntilIdle()
        val res = (r.state.value as RawNmeaState.Finished).result
        assertEquals(lines.joinToString("") { "$it\r\n" }, res.nmeaFile.readText())
        assertTrue(res.complete)
    }

    @Test fun `isolated swallows a hook failure and continues`() {
        val calls = mutableListOf<String>()
        RawNmeaTaps.isolated("test.a") { calls += "a"; throw IllegalStateException("SYNTHETIC") }
        RawNmeaTaps.isolated("test.b") { calls += "b" }
        assertEquals(listOf("a", "b"), calls)
    }

    /**
     * Real TcpNmeaSource (geokit, unchanged) over loopback: its `onLine` runs inside the same `try` as
     * `fuser.accept`, so a throwing raw hook drops every sentence before parsing (no fixes at all). Through
     * RawNmeaTaps, with every diagnostic hook throwing, the sentences still reach the fuser and fixes come out.
     */
    @Test(timeout = 60_000)
    fun `throwing diagnostic hooks no longer drop TCP sentences before the fuser`() = runBlocking {
        val lines = (0 until 6).flatMap { i ->
            val t = "1200%02d.00".format(i)
            listOf(gga(t, "4118.0"), rmc(t, "4118.0"))
        }

        fun fixesWith(onLine: (String) -> Unit, scope: CoroutineScope): Int {
            val server = ServerSocket(0)
            val serverThread = thread {
                server.accept().use { s ->
                    val out = s.getOutputStream()
                    lines.forEach { out.write("$it\r\n".toByteArray(Charsets.US_ASCII)); out.flush(); Thread.sleep(20) }
                    Thread.sleep(800)
                }
            }
            val fixes = java.util.Collections.synchronizedList(mutableListOf<Fix>())
            val tcp = TcpNmeaSource(
                scope = scope, endpoint = { "127.0.0.1" to server.localPort },
                registry = DefaultNmeaRegistry.create(verifyChecksum = true, requireChecksum = true),
                onLine = onLine, readTimeoutMs = 2_000,
            )
            val collector = scope.launch { tcp.parsedFixes().collect { fixes += it } }
            try {
                tcp.start()
                serverThread.join(10_000)
                Thread.sleep(300)
                return fixes.size
            } finally {
                tcp.stop(); collector.cancel(); server.close()
            }
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // Before: a raw throwing hook (what an unguarded diagnostics/tilt hook did) → nothing is fused.
            val rawFixes = fixesWith({ throw IllegalStateException("SYNTHETIC") }, scope)
            assertEquals("unguarded throwing hook: TcpNmeaSource drops every sentence", 0, rawFixes)

            // After: all three hooks throw inside RawNmeaTaps; the fuser still gets every sentence.
            val idleRecorder = RawNmeaRecorder(
                scope = scope, ioDispatcher = Dispatchers.IO, files = RawNmeaFiles { File(tmp.root, "idle") },
                metadata = { externalMeta }, monoClockMs = { System.nanoTime() / 1_000_000 }, statsPublishIntervalMs = 0,
            )
            val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
            val guarded = RawNmeaTaps.externalOnLine(
                idleRecorder,
                recordDiagnostics = { seen += it; throw IllegalStateException("SYNTHETIC diag") },
                tiltMonitor = { throw IllegalStateException("SYNTHETIC tilt") },
            )
            val guardedFixes = fixesWith(guarded, scope)
            assertTrue("sentences reached the fuser despite throwing hooks: fixes=$guardedFixes", guardedFixes >= 5)
            assertEquals("hooks still see every line, in order", lines, seen.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test fun `fixture fusion output is identical with and without an active recording`() = runTest {
        val lines = javaClass.getResourceAsStream("/nmea/reach_nmea_withimu.txt")!!.bufferedReader().readLines()

        fun fuse(hook: ((String) -> Unit)?): List<Fix> {
            val out = mutableListOf<Fix>()
            val fuser = NmeaFuser(
                Provider.EXTERNAL_TCP,
                DefaultNmeaRegistry.create(verifyChecksum = true, requireChecksum = true),
                onFix = { out += it }, onGsv = { }, clock = { 0L },
            )
            // As TcpNmeaSource does: trimmed '$' lines, hook before fuser.accept.
            lines.map { it.trim() }.filter { it.startsWith("$") }.forEach { l -> hook?.invoke(l); fuser.accept(l) }
            fuser.flush()
            return out
        }

        val without = fuse(null)
        val r = recorder(capacity = 1_000_000)
        r.start(RawNmeaDuration.MANUAL)
        val with = fuse(RawNmeaTaps.externalOnLine(r, { }, { }))
        r.stop(); advanceUntilIdle()

        assertTrue(without.isNotEmpty())
        assertEquals(without, with)
        val res = (r.state.value as RawNmeaState.Finished).result
        assertTrue(res.complete)
        val expected = lines.map { it.trim() }.filter { it.startsWith("$") }.joinToString("") { "$it\r\n" }
        assertEquals(expected, res.nmeaFile.readText())
    }

    /**
     * Real TcpNmeaSource over a loopback socket (SYNTHETIC sentences). Several sentences in one read and a
     * sentence split across reads arrive as whole lines: framing is BufferedReader.readLine's, before onLine.
     * What the hook cannot preserve: blank and non-'$' lines are dropped, surrounding whitespace is trimmed,
     * the original terminator (CRLF / LF) is not kept, and a partial last line delivered at EOF is recorded
     * (with CRLF added) — it is then counted as malformed.
     */
    @Test(timeout = 30_000)
    fun `TCP framing happens before the hook - recorder sees whole trimmed lines`() = runBlocking {
        val a1 = gga("120000.00", "4118.1"); val a2 = rmc("120000.00", "4118.1"); val a3 = gga("120000.20", "4118.2")
        val a4 = nmea("GNETC,120000.20"); val partial = "\$GNGGA,120000.40,4118"
        val chunks = listOf(
            "$a1\r\n$a2\r\n${a3.take(20)}",          // two whole sentences + the start of a third
            "${a3.drop(20)}\r\n",                     // the rest of it
            "\r\n   \r\n",                            // blank lines
            "  $a4  \n",                              // LF only, surrounding spaces
            "not nmea\r\n",                           // no '$'
            partial,                                  // cut off by the close
        )
        val server = ServerSocket(0)
        val serverThread = thread {
            server.accept().use { s ->
                val out = s.getOutputStream()
                chunks.forEach { out.write(it.toByteArray(Charsets.US_ASCII)); out.flush(); Thread.sleep(50) }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val r = RawNmeaRecorder(
            scope = scope, ioDispatcher = Dispatchers.IO, files = RawNmeaFiles { File(tmp.root, "tcp") },
            metadata = { externalMeta }, monoClockMs = { System.nanoTime() / 1_000_000 }, statsPublishIntervalMs = 0,
        )
        r.watchSource(MutableStateFlow(ProviderChoice.EXTERNAL_TCP), MutableStateFlow(ConnectionState.CONNECTED), MutableStateFlow("p"))
        waitFor { r.availability.value is RawNmeaAvailability.Available }
        assertTrue(r.start(RawNmeaDuration.MANUAL) is RawNmeaRecorder.StartResult.Started)
        val diag = java.util.Collections.synchronizedList(mutableListOf<String>())
        val tcp = TcpNmeaSource(
            scope = scope, endpoint = { "127.0.0.1" to server.localPort },
            registry = DefaultNmeaRegistry.create(verifyChecksum = true, requireChecksum = true),
            onLine = RawNmeaTaps.externalOnLine(r, { diag += it }, { }), readTimeoutMs = 2_000,
        )
        try {
            tcp.start()
            waitFor { diag.size >= 5 }
            serverThread.join(5_000)
            Thread.sleep(200)
            r.stop()
            waitFor { r.state.value is RawNmeaState.Finished }
            val res = (r.state.value as RawNmeaState.Finished).result
            assertEquals(listOf(a1, a2, a3, a4, partial), diag.toList())
            assertEquals(listOf(a1, a2, a3, a4, partial).joinToString("") { "$it\r\n" }, res.nmeaFile.readText())
            assertEquals(4L, res.stats.checksumValid)
            assertEquals(1L, res.stats.malformed)
        } finally {
            tcp.stop(); server.close(); scope.cancel()
        }
    }

    private fun TestScope.detachedScope() = CoroutineScope(StandardTestDispatcher(testScheduler) + kotlinx.coroutines.Job())

    private fun waitFor(cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!cond()) {
            check(System.currentTimeMillis() < until) { "timed out" }
            Thread.sleep(20)
        }
    }
}
