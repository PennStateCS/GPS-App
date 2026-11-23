package io.github.pennstatecs.geokit.gnss.link

import io.github.pennstatecs.geokit.gnss.bus.adapters.GsvMessage
import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaFuser
import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaSource
import io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter.ConnectionState
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import io.github.pennstatecs.geokit.gnss.nmea.parse.DefaultNmeaRegistry
import io.github.pennstatecs.geokit.gnss.satellites.SatelliteInventory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The reconnect loop with a scripted source, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectingNmeaAdapterTest {

    /** A source whose connection and data the test controls. */
    private class FakeSource : NmeaSource {
        val fixes = MutableSharedFlow<Fix>(replay = 1, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        private val gsv = MutableSharedFlow<GsvMessage>(extraBufferCapacity = 8)
        private val raw = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        private val fuser = NmeaFuser(Provider.EXTERNAL_TCP, DefaultNmeaRegistry.create(verifyChecksum = false), {}, {})
        @Volatile var active = false
        var starts = 0
        override fun start() { starts++; active = true }
        override fun stop() { active = false; fixes.resetReplayCache() }
        override fun parsedFixes(): SharedFlow<Fix> = fixes
        override fun gsvStream(): SharedFlow<GsvMessage> = gsv
        override fun rawNmeaEvents(): SharedFlow<Unit> = raw
        override fun nmeaTimingStats() = fuser.timingStats
        override fun nmeaCustomStats() = fuser.nmeaCustomStats
        override fun isConnectionActive(): Boolean = active
    }

    /** Records every line (and throttle reset) the adapter writes. */
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

    private fun fix() = Fix(
        provider = Provider.EXTERNAL_TCP, timeUtc = Instant.EPOCH, timestampSource = TimestampSource.UNKNOWN,
        latDeg = 41.0, lonDeg = -76.0, altEllipsoidalM = null, altMslM = null, geoidSeparationM = null,
        hDop = 1.0, vDop = null, pDop = null, hAccM = null, vAccM = null,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null, rtkStatus = RtkStatus.FIX,
        satsUsed = 20, satsVisible = null, diffAgeS = null, speedMps = null, courseDeg = null,
    )

    private fun TestScope.adapter(src: FakeSource, log: LinkLog, stamp: (Fix) -> Fix = { it }) =
        ReconnectingNmeaAdapter(
            scope = backgroundScope, nmea = src, inv = SatelliteInventory(), stampFix = stamp,
            linkLog = log, monotonicNowMs = { testScheduler.currentTime },
        )

    @Test fun `first data connects and every fix is stamped`() = runTest {
        val src = FakeSource(); val log = RecordingLog()
        val a = adapter(src, log) { it.copy(receiverLabel = "Test RX") }
        val out = mutableListOf<Fix>()
        backgroundScope.launch { a.fixes.collect { out += it } }
        a.start(); runCurrent()
        assertEquals(ConnectionState.CONNECTING, a.connectionState.value)

        src.fixes.emit(fix()); advanceTimeBy(300); runCurrent()
        assertEquals(ConnectionState.CONNECTED, a.connectionState.value)
        assertEquals(listOf("Test RX"), out.map { it.receiverLabel })
        assertTrue(log.lines.contains("I RECEIVER External receiver connected attempt=1"))
        assertTrue(log.lines.contains("reset ${ReconnectingNmeaAdapter.RETRY_LOG_PREFIX}"))
        a.stop()
    }

    @Test fun `a stamping error publishes the unstamped fix`() = runTest {
        val src = FakeSource(); val log = RecordingLog()
        val a = adapter(src, log) { error("boom") }
        val out = mutableListOf<Fix>()
        backgroundScope.launch { a.fixes.collect { out += it } }
        a.start(); runCurrent()
        src.fixes.emit(fix()); runCurrent()
        assertEquals(1, out.size)
        assertEquals(null, out[0].receiverLabel)
        assertTrue(log.lines.contains("E RECEIVER External receiver fix stamping failed: publishing unstamped fix"))
        a.stop()
    }

    @Test fun `a connection that ends before data is retried on the backoff schedule`() = runTest {
        val src = FakeSource(); val log = RecordingLog()
        val a = adapter(src, log)
        a.start(); runCurrent()
        assertEquals(1, src.starts)
        src.active = false                       // refused / unreachable
        advanceTimeBy(300); runCurrent()
        assertEquals(ConnectionState.ERROR, a.connectionState.value)
        assertTrue(log.lines.any { it.startsWith("W RECEIVER External receiver connect failed attempt=1 (will retry)") })
        advanceTimeBy(1_600); runCurrent()       // attempt 1 backoff: 1 s + jitter < 0.5 s
        assertEquals(2, src.starts)
        assertEquals(ConnectionState.RECONNECTING, a.connectionState.value)
        assertEquals(2, a.attemptCount.value)
        a.stop()
        assertEquals(ConnectionState.DISCONNECTED, a.connectionState.value)
        assertEquals(0, a.attemptCount.value)
    }

    @Test fun `a connected link that drops reconnects`() = runTest {
        val src = FakeSource(); val log = RecordingLog()
        val a = adapter(src, log)
        a.start(); runCurrent()
        src.fixes.emit(fix()); advanceTimeBy(300); runCurrent()
        assertEquals(ConnectionState.CONNECTED, a.connectionState.value)
        src.active = false
        advanceTimeBy(1_100); runCurrent()       // next health check
        assertTrue(log.lines.contains("W RECEIVER External receiver connection ended: reconnecting"))
        advanceTimeBy(1_600); runCurrent()
        assertEquals(2, src.starts)
        a.stop()
    }

    @Test fun `reconnectNow restarts a running adapter and is ignored when stopped`() = runTest {
        val src = FakeSource(); val log = RecordingLog()
        val a = adapter(src, log)
        a.reconnectNow("idle"); runCurrent()
        assertEquals(0, src.starts)
        a.start(); runCurrent()
        a.reconnectNow("new receiver"); runCurrent()
        assertTrue(log.lines.contains("I RECEIVER External receiver reconnect requested reason=\"new receiver\""))
        assertEquals(2, src.starts)
        a.stop()
    }
}
