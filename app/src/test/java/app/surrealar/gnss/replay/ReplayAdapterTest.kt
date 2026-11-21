package app.surrealar.gnss.replay

import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.model.RtkStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Replay now runs through the same NmeaFuser as live sources and publishes like a live adapter.
 * Robolectric: android.util.Log in the fuser/inventory/diagnostics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplayAdapterTest {

    private class ResourceSource(private val res: String) : NmeaLineSource {
        override val name = "test $res"
        // Yield per line like a paced replay, so the collector drains the adapter's buffer.
        override fun lines(): Flow<String> =
            ReplayAdapterTest::class.java.getResourceAsStream(res)!!.bufferedReader().readLines().asFlow()
                .onEach { kotlinx.coroutines.yield() }
    }

    @Test fun `Emlid capture replays through NmeaFuser with fuser-quality output`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val adapter = ReplayAdapter(backgroundScope, dispatcher = dispatcher)
        val fixes = mutableListOf<Fix>()
        backgroundScope.launch(dispatcher) { adapter.fixes.collect { fixes.add(it) } }

        adapter.load(ResourceSource("/nmea/reach_nmea_capture.txt"))
        adapter.start()
        testScheduler.advanceUntilIdle()

        assertTrue("replay emits fixes (${fixes.size})", fixes.size > 100)
        val f = fixes.first { it.hAccM != null }
        assertEquals("replay fixes are tagged so captures refuse them", Provider.REPLAY, f.provider)
        assertEquals(RtkStatus.FIX, f.rtkStatus)
        // What FixAccumulator never produced for Emlid: accuracy from GST lat/lon σ (68 % radius).
        assertEquals(0.0152, f.hAccM!!, 1e-4)
        assertTrue("constellation-aware satellite counts", (f.satsUsed ?: 0) >= 20)
        assertTrue("sky view populated from GSV", adapter.sky.value.satellites.isNotEmpty())
        adapter.stop()
    }
}
