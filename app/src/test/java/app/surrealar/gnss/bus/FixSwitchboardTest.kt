package app.surrealar.gnss.bus

import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.model.RtkStatus
import app.surrealar.gnss.model.TimestampSource
import app.surrealar.gnss.source.ConnectionProfile
import app.surrealar.gnss.source.SourceSettings
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Unit tests for [FixSwitchboard] source/provider routing — the layer that decides which GNSS
 * source the app shows/saves. A bug here is a survey-integrity bug (wrong position source), so these
 * focus on: provider switching, structural stale-fix suppression on switch, wrong-provider gating,
 * and no-data states.
 *
 * NOTE on staleness: [FixSwitchboard] does NOT compare fix timestamps. Its stale suppression is
 * structural — on a provider switch it nulls currentFix, clears the replay cache, and bumps the
 * bind generation so the OLD provider's fix can never carry over. Timestamp-based ordering belongs
 * to upstream adapters, so these tests assert the switch-time guarantees, not clock comparisons.
 *
 * No Android framework, network, or real adapters are used — a controllable [FakeSourceAdapter]
 * stands in for internal/external sources, and coroutines run on the test scheduler.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FixSwitchboardTest {

    // ── Test doubles ─────────────────────────────────────────────────────────────────────────

    private class FakeSourceAdapter : SourceAdapter, Startable {
        private val flow = MutableSharedFlow<Fix>(
            replay = 1, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        override val fixes: SharedFlow<Fix> = flow
        var startCount = 0; private set
        var stopCount = 0;  private set
        override fun start() { startCount++ }
        override fun stop() { stopCount++ }
        fun emit(fix: Fix) { flow.tryEmit(fix) }
    }

    private fun settings(initial: ProviderChoice) =
        MutableStateFlow(initial).let { flow ->
            flow to SourceSettings(
                flow,
                MutableStateFlow(emptyList<ConnectionProfile>()),
                MutableStateFlow(null),
            )
        }

    private var fixSeq = 0
    private fun fix(provider: Provider, rtk: RtkStatus = RtkStatus.FIX): Fix = Fix(
        provider = provider, timeUtc = Instant.ofEpochSecond(1000L + fixSeq++),
        timestampSource = TimestampSource.UNKNOWN, latDeg = 41.0 + fixSeq * 1e-6, lonDeg = -76.0,
        altEllipsoidalM = 100.0, altMslM = null, geoidSeparationM = null,
        hDop = null, vDop = null, pDop = null, hAccM = 0.1, vAccM = 0.1,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null,
        rtkStatus = rtk, satsUsed = 12, satsVisible = null, diffAgeS = null,
        speedMps = null, courseDeg = null,
    )

    // ── Startup / default binding ──────────────────────────────────────────────────────────────

    @Test fun `start binds the initial provider and forwards its fixes`() = runTest(UnconfinedTestDispatcher()) {
        val (_, src) = settings(ProviderChoice.INTERNAL)
        val internal = FakeSourceAdapter(); val external = FakeSourceAdapter()
        val sb = FixSwitchboard(backgroundScope, src,
            mapOf(ProviderChoice.INTERNAL to internal, ProviderChoice.EXTERNAL_TCP to external))

        sb.start(); advanceUntilIdle()
        assertEquals("initial provider's adapter must be started", 1, internal.startCount)
        assertEquals(0, external.startCount)
        assertNull("no fix before the provider emits", sb.currentFix.value)

        val f = fix(Provider.INTERNAL)
        internal.emit(f); advanceUntilIdle()
        assertEquals(f, sb.currentFix.value)
        assertEquals(listOf(f), sb.fixes.replayCache)
    }

    @Test fun `unregistered provider unbinds and emits no current fix`() = runTest(UnconfinedTestDispatcher()) {
        val (_, src) = settings(ProviderChoice.EXTERNAL_TCP)
        val internal = FakeSourceAdapter()
        // Only INTERNAL registered → EXTERNAL_TCP has no adapter (not configured).
        val sb = FixSwitchboard(backgroundScope, src, mapOf(ProviderChoice.INTERNAL to internal))

        sb.start(); advanceUntilIdle()
        assertEquals(0, internal.startCount)
        assertNull(sb.currentFix.value)   // no adapter → nothing masquerades as a fix
    }

    // ── Provider switching + structural stale suppression ──────────────────────────────────────

    @Test fun `switching to external clears the internal fix until external emits`() = runTest(UnconfinedTestDispatcher()) {
        val (flow, src) = settings(ProviderChoice.INTERNAL)
        val internal = FakeSourceAdapter(); val external = FakeSourceAdapter()
        val sb = FixSwitchboard(backgroundScope, src,
            mapOf(ProviderChoice.INTERNAL to internal, ProviderChoice.EXTERNAL_TCP to external))
        sb.start(); advanceUntilIdle()

        val internalFix = fix(Provider.INTERNAL)
        internal.emit(internalFix); advanceUntilIdle()
        assertEquals(internalFix, sb.currentFix.value)

        flow.value = ProviderChoice.EXTERNAL_TCP; advanceUntilIdle()
        // The instant the switch begins, the old source's fix is gone (currentFix + replay cache).
        assertNull("old provider fix must not carry over", sb.currentFix.value)
        assertTrue("replay cache must be cleared on switch", sb.fixes.replayCache.isEmpty())
        assertEquals("new adapter started", 1, external.startCount)
        assertEquals("old adapter stopped", 1, internal.stopCount)

        val externalFix = fix(Provider.RS2_EXTERNAL)
        external.emit(externalFix); advanceUntilIdle()
        assertEquals(externalFix, sb.currentFix.value)
    }

    @Test fun `internal fixes are ignored after switching to external (wrong-provider gating)`() = runTest(UnconfinedTestDispatcher()) {
        val (flow, src) = settings(ProviderChoice.INTERNAL)
        val internal = FakeSourceAdapter(); val external = FakeSourceAdapter()
        val sb = FixSwitchboard(backgroundScope, src,
            mapOf(ProviderChoice.INTERNAL to internal, ProviderChoice.EXTERNAL_TCP to external))
        sb.start(); advanceUntilIdle()

        flow.value = ProviderChoice.EXTERNAL_TCP; advanceUntilIdle()
        val externalFix = fix(Provider.RS2_EXTERNAL)
        external.emit(externalFix); advanceUntilIdle()
        assertEquals(externalFix, sb.currentFix.value)

        // The de-selected internal source keeps producing fixes — they must NOT be routed.
        internal.emit(fix(Provider.INTERNAL)); advanceUntilIdle()
        assertEquals("internal fix must be ignored while external is selected",
            externalFix, sb.currentFix.value)
    }

    @Test fun `switching back to internal restarts it and drops the external fix`() = runTest(UnconfinedTestDispatcher()) {
        val (flow, src) = settings(ProviderChoice.INTERNAL)
        val internal = FakeSourceAdapter(); val external = FakeSourceAdapter()
        val sb = FixSwitchboard(backgroundScope, src,
            mapOf(ProviderChoice.INTERNAL to internal, ProviderChoice.EXTERNAL_TCP to external))
        sb.start(); advanceUntilIdle()

        flow.value = ProviderChoice.EXTERNAL_TCP; advanceUntilIdle()
        external.emit(fix(Provider.RS2_EXTERNAL)); advanceUntilIdle()

        flow.value = ProviderChoice.INTERNAL; advanceUntilIdle()
        assertNull("external fix must not carry into internal session", sb.currentFix.value)
        assertEquals("internal restarted", 2, internal.startCount)
        assertEquals("external stopped", 1, external.stopCount)
    }

    @Test fun `start() twice does not restart the already-bound adapter`() = runTest(UnconfinedTestDispatcher()) {
        val (_, src) = settings(ProviderChoice.INTERNAL)
        val internal = FakeSourceAdapter()
        val sb = FixSwitchboard(backgroundScope, src, mapOf(ProviderChoice.INTERNAL to internal))

        sb.start(); advanceUntilIdle()
        sb.start(); advanceUntilIdle()   // replaces the provider watcher; same provider value
        assertEquals("no duplicate start for the same bound adapter", 1, internal.startCount)
    }

    @Test fun `rapid switching settles consistently on the final provider`() = runTest(UnconfinedTestDispatcher()) {
        val (flow, src) = settings(ProviderChoice.INTERNAL)
        val internal = FakeSourceAdapter(); val external = FakeSourceAdapter()
        val sb = FixSwitchboard(backgroundScope, src,
            mapOf(ProviderChoice.INTERNAL to internal, ProviderChoice.EXTERNAL_TCP to external))
        sb.start(); advanceUntilIdle()

        // Several flips before the scheduler runs — StateFlow conflates to the last value.
        flow.value = ProviderChoice.EXTERNAL_TCP
        flow.value = ProviderChoice.INTERNAL
        flow.value = ProviderChoice.EXTERNAL_TCP
        advanceUntilIdle()

        assertNull(sb.currentFix.value)
        // Final provider is EXTERNAL: external fixes route, internal fixes do not.
        external.emit(fix(Provider.RS2_EXTERNAL)); advanceUntilIdle()
        val ext = sb.currentFix.value
        assertEquals(Provider.RS2_EXTERNAL, ext?.provider)
        internal.emit(fix(Provider.INTERNAL)); advanceUntilIdle()
        assertEquals("only the final provider routes", ext, sb.currentFix.value)
    }

    // ── No-data states ──────────────────────────────────────────────────────────────────────────

    @Test fun `external selected but silent keeps currentFix null (no masquerade)`() = runTest(UnconfinedTestDispatcher()) {
        val (flow, src) = settings(ProviderChoice.INTERNAL)
        val internal = FakeSourceAdapter(); val external = FakeSourceAdapter()
        val sb = FixSwitchboard(backgroundScope, src,
            mapOf(ProviderChoice.INTERNAL to internal, ProviderChoice.EXTERNAL_TCP to external))
        sb.start(); advanceUntilIdle()
        internal.emit(fix(Provider.INTERNAL)); advanceUntilIdle()

        flow.value = ProviderChoice.EXTERNAL_TCP; advanceUntilIdle()
        // External connected but produces no fix → must read "no fix", never the old internal one.
        assertNull(sb.currentFix.value)
        assertTrue(sb.fixes.replayCache.isEmpty())
    }

    @Test fun `stop unbinds, clears the current fix, and stops the adapter`() = runTest(UnconfinedTestDispatcher()) {
        val (_, src) = settings(ProviderChoice.INTERNAL)
        val internal = FakeSourceAdapter()
        val sb = FixSwitchboard(backgroundScope, src, mapOf(ProviderChoice.INTERNAL to internal))
        sb.start(); advanceUntilIdle()
        internal.emit(fix(Provider.INTERNAL)); advanceUntilIdle()
        assertTrue(sb.currentFix.value != null)

        sb.stop(); advanceUntilIdle()
        assertEquals(1, internal.stopCount)
        assertNull(sb.currentFix.value)
        assertTrue(sb.fixes.replayCache.isEmpty())
    }

    // ── Diagnostic / state reporting ──────────────────────────────────────────────────────────

    @Test fun `lastProviderSwitchAtMs is zero before any switch and set after one`() = runTest(UnconfinedTestDispatcher()) {
        val (flow, src) = settings(ProviderChoice.INTERNAL)
        val internal = FakeSourceAdapter(); val external = FakeSourceAdapter()
        val sb = FixSwitchboard(backgroundScope, src,
            mapOf(ProviderChoice.INTERNAL to internal, ProviderChoice.EXTERNAL_TCP to external))
        sb.start(); advanceUntilIdle()
        assertEquals("no switch recorded on the initial bind", 0L, sb.lastProviderSwitchAtMs)

        flow.value = ProviderChoice.EXTERNAL_TCP; advanceUntilIdle()
        assertTrue("a real provider change is timestamped", sb.lastProviderSwitchAtMs > 0L)
    }

    @Test fun `selected provider is reflected by which adapter is routed`() = runTest(UnconfinedTestDispatcher()) {
        // FixSwitchboard always routes to the SELECTED provider's adapter (no internal mismatch);
        // selected vs active divergence is a higher-layer concern. Verify the routing contract.
        val (flow, src) = settings(ProviderChoice.INTERNAL)
        val internal = FakeSourceAdapter(); val external = FakeSourceAdapter()
        val sb = FixSwitchboard(backgroundScope, src,
            mapOf(ProviderChoice.INTERNAL to internal, ProviderChoice.EXTERNAL_TCP to external))
        sb.start(); advanceUntilIdle()
        internal.emit(fix(Provider.INTERNAL)); advanceUntilIdle()
        assertEquals(Provider.INTERNAL, sb.currentFix.value?.provider)
        assertEquals(ProviderChoice.INTERNAL, src.activeProvider.value)

        flow.value = ProviderChoice.EXTERNAL_TCP; advanceUntilIdle()
        external.emit(fix(Provider.RS2_EXTERNAL)); advanceUntilIdle()
        assertEquals(Provider.RS2_EXTERNAL, sb.currentFix.value?.provider)
        assertEquals(ProviderChoice.EXTERNAL_TCP, src.activeProvider.value)
    }
}
