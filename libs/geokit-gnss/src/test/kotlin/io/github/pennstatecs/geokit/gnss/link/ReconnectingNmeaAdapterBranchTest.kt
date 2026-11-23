package io.github.pennstatecs.geokit.gnss.link

import io.github.pennstatecs.geokit.gnss.bus.adapters.GsvEntry
import io.github.pennstatecs.geokit.gnss.bus.adapters.GsvMessage
import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaFuser
import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaSource
import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter.ConnectionState
import io.github.pennstatecs.geokit.gnss.log.DiagnosticLevel
import io.github.pennstatecs.geokit.gnss.log.GnssLog
import io.github.pennstatecs.geokit.gnss.log.GnssLogger
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import io.github.pennstatecs.geokit.gnss.nmea.parse.DefaultNmeaRegistry
import io.github.pennstatecs.geokit.gnss.satellites.SatelliteInventory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Branches of the reconnect loop not covered by ReconnectingNmeaAdapterTest: double start, the
 * connect timeout, repeated failures (retry summary + failedAttempts on recovery), a stalled link,
 * GSV/raw-only first data, scope cancellation (CANCELLED) and the debug traces. Virtual time only;
 * the backoff jitter (< 0.5 s) is absorbed by the time steps chosen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectingNmeaAdapterBranchTest {

    private class FakeSource : NmeaSource {
        val fixes = MutableSharedFlow<Fix>(replay = 1, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val gsv = MutableSharedFlow<GsvMessage>(extraBufferCapacity = 8)
        val raw = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        private val fuser = NmeaFuser(Provider.EXTERNAL_TCP, DefaultNmeaRegistry.create(verifyChecksum = false), {}, {})
        @Volatile var active = false
        @Volatile var connectSucceeds = true
        var starts = 0
        var stops = 0
        override fun start() { starts++; active = connectSucceeds }
        override fun stop() { stops++; active = false; fixes.resetReplayCache() }
        override fun parsedFixes(): SharedFlow<Fix> = fixes
        override fun gsvStream(): SharedFlow<GsvMessage> = gsv
        override fun rawNmeaEvents(): SharedFlow<Unit> = raw
        override fun nmeaTimingStats() = fuser.timingStats
        override fun nmeaExtraStats() = fuser.extraSentenceStats
        override fun isConnectionActive(): Boolean = active
    }

    private class RecordingLog : LinkLog {
        val lines = mutableListOf<String>()
        override fun d(tag: String, message: () -> String) { lines += "D $tag ${message()}" }
        override fun i(tag: String, message: String) { lines += "I $tag $message" }
        override fun w(tag: String, message: String, throwable: Throwable?) { lines += "W $tag $message" }
        override fun logOnChange(tag: String, key: String, value: Any?, level: LinkLogLevel, throwable: Throwable?, message: () -> String): Boolean {
            lines += "${level.name[0]} $tag ${message()}"; return true
        }
        override fun every(tag: String, key: String, intervalMs: Long, level: LinkLogLevel, throwable: Throwable?, message: () -> String): Boolean {
            lines += "${level.name[0]} $tag ${message()}"; return true
        }
        override fun resetThrottles(keyPrefix: String) { lines += "reset $keyPrefix" }
    }

    private class Traces : GnssLogger {
        val lines = mutableListOf<String>()
        override val enabled = true
        override fun d(tag: String, message: String) { lines += "D $message" }
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String, throwable: Throwable?) {}
        override fun e(tag: String, message: String, throwable: Throwable?) {}
        override fun diagnostic(tag: String, message: String, level: DiagnosticLevel, throwable: Throwable?) {}
        override fun isDue(key: String, intervalMs: Long) = true
        override fun trace(area: String, message: String) { lines += "[$area] $message" }
    }

    @After fun tearDown() = GnssLog.uninstall()

    private fun fix() = Fix(
        provider = Provider.EXTERNAL_TCP, timeUtc = Instant.EPOCH, timestampSource = TimestampSource.UNKNOWN,
        latDeg = 41.0, lonDeg = -76.0, altEllipsoidalM = null, altMslM = null, geoidSeparationM = null,
        hDop = 1.0, vDop = null, pDop = null, hAccM = null, vAccM = null,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null, rtkStatus = RtkStatus.FIX,
        satsUsed = 20, satsVisible = null, diffAgeS = null, speedMps = null, courseDeg = null,
    )

    private fun TestScope.adapter(src: FakeSource, log: LinkLog, scope: CoroutineScope = backgroundScope) =
        ReconnectingNmeaAdapter(
            scope = scope, nmea = src, inv = SatelliteInventory(), linkLog = log,
            monotonicNowMs = { testScheduler.currentTime },
        )

    @Test fun `a second start while running is a no-op`() = runTest {
        val src = FakeSource(); val a = adapter(src, RecordingLog())
        a.start(); runCurrent()
        a.start(); runCurrent()
        assertEquals(1, src.starts)
        a.stop()
    }

    @Test fun `an open link that never sends data times out and retries`() = runTest {
        val src = FakeSource(); val log = RecordingLog()
        val a = adapter(src, log)
        a.start(); runCurrent()
        advanceTimeBy(9_000); runCurrent()
        assertEquals(ConnectionState.CONNECTING, a.connectionState.value)
        advanceTimeBy(1_100); runCurrent()
        assertTrue(log.lines.contains("D RECEIVER External receiver connect timed out: no NMEA data timeoutMs=10000 (will retry)"))
        assertTrue(log.lines.any { it.startsWith("W RECEIVER External receiver connect failed attempt=1") })
        assertEquals(ConnectionState.ERROR, a.connectionState.value)
        advanceTimeBy(1_600); runCurrent()
        assertEquals(2, src.starts)
        a.stop()
    }

    @Test fun `repeated failures log a summary and recovery reports failed attempts`() = runTest {
        val src = FakeSource().apply { connectSucceeds = false }
        val log = RecordingLog()
        val a = adapter(src, log)
        a.start(); runCurrent()
        advanceTimeBy(1_600); runCurrent()     // attempt 2 (after 1 s + jitter) fails too
        assertEquals(2, src.starts)
        assertTrue(log.lines.any {
            it.startsWith("W RECEIVER External receiver still unreachable attempts=2 lastError=IllegalStateException " +
                "reason=\"Connection ended before data\"")
        })
        src.connectSucceeds = true
        advanceTimeBy(2_600); runCurrent()     // attempt 3 (after 2 s + jitter) connects
        assertEquals(3, src.starts)
        src.fixes.emit(fix()); advanceTimeBy(300); runCurrent()
        assertEquals(ConnectionState.CONNECTED, a.connectionState.value)
        assertTrue(log.lines.contains("I RECEIVER External receiver connected attempt=3 failedAttempts=2"))
        assertEquals(3, a.attemptCount.value)
        a.stop()
    }

    @Test fun `a connected link that goes silent is reconnected after 30 s`() = runTest {
        val src = FakeSource(); val log = RecordingLog()
        val a = adapter(src, log)
        a.start(); runCurrent()
        // Data at t = 100 ms (a last-data time of 0 reads as "no data yet" and is never stale).
        advanceTimeBy(100); src.fixes.emit(fix()); advanceTimeBy(300); runCurrent()
        assertEquals(ConnectionState.CONNECTED, a.connectionState.value)
        advanceTimeBy(29_000); runCurrent()   // t = 29.4 s
        assertEquals(ConnectionState.CONNECTED, a.connectionState.value)
        advanceTimeBy(1_000); runCurrent()    // health check at 30.25 s: silent for 30.15 s
        assertTrue(log.lines.contains("W RECEIVER External receiver link stalled: no NMEA data, reconnecting silentMs=30000"))
        assertEquals(ConnectionState.ERROR, a.connectionState.value)
        advanceTimeBy(1_600); runCurrent()
        assertEquals(2, src.starts)
        a.stop()
    }

    @Test fun `satellite messages alone count as first data and update the sky`() = runTest {
        val src = FakeSource(); val a = adapter(src, RecordingLog())
        a.start(); runCurrent()
        src.gsv.emit(GsvMessage("GP", listOf(GsvEntry(5, 45, 120, 40.0, true), GsvEntry(7, 20, 300, 30.0, false))))
        advanceTimeBy(300); runCurrent()
        assertEquals(ConnectionState.CONNECTED, a.connectionState.value)
        assertEquals(2, a.sky.value.totalVisible)
        a.stop()
        assertEquals("stop clears the sky", 0, a.sky.value.totalVisible)
        assertEquals(ConnectionState.DISCONNECTED, a.connectionState.value)
    }

    @Test fun `raw NMEA events alone count as first data and are forwarded`() = runTest {
        val src = FakeSource(); val a = adapter(src, RecordingLog())
        var raw = 0
        backgroundScope.launch { a.rawNmea.collect { raw++ } }
        a.start(); runCurrent()
        src.raw.emit(Unit); src.raw.emit(Unit)
        advanceTimeBy(300); runCurrent()
        assertEquals(ConnectionState.CONNECTED, a.connectionState.value)
        assertEquals(2, raw)
        a.stop()
    }

    @Test fun `cancelling the owning scope reports CANCELLED`() = runTest {
        val job = Job()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job)
        val src = FakeSource(); val a = adapter(src, RecordingLog(), scope)
        a.start(); runCurrent()
        job.cancel(); runCurrent()
        assertEquals(ConnectionState.CANCELLED, a.connectionState.value)
    }

    @Test fun `stop is idempotent and stops the source`() = runTest {
        val src = FakeSource(); val log = RecordingLog()
        val a = adapter(src, log)
        a.stop()
        a.start(); runCurrent()
        a.stop(); a.stop()
        assertEquals(ConnectionState.DISCONNECTED, a.connectionState.value)
        assertTrue(src.stops >= 3)
        assertEquals(3, log.lines.count { it == "I RECEIVER External receiver adapter stopped" })
    }

    @Test fun `debug traces describe connect and failed attempts`() = runTest {
        val traces = Traces()
        GnssLog.install(traces)
        val src = FakeSource().apply { connectSucceeds = false }
        val a = adapter(src, RecordingLog())
        a.start(); runCurrent()
        advanceTimeBy(1_600); runCurrent()
        assertTrue(traces.lines.any { it.startsWith("[external] attempt #1 failed (IllegalStateException: Connection ended before data)") })
        assertTrue(traces.lines.contains("D Reconnecting external receiver attempt #2"))
        src.connectSucceeds = true
        advanceTimeBy(2_600); runCurrent()
        src.fixes.emit(fix()); advanceTimeBy(300); runCurrent()
        assertTrue(traces.lines.any { it.startsWith("[external] CONNECTED after 3 attempt(s)") })
        a.stop()
    }
}
