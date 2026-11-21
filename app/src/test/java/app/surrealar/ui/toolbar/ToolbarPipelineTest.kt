package app.surrealar.ui.toolbar

import app.surrealar.coords.ConvertedPosition
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.ui.toolbar.ToolbarPipeline.retryForever
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ToolbarPipelineTest {

    private fun fix(i: Int) = Fix(
        provider = Provider.EXTERNAL_TCP, timeUtc = Instant.EPOCH, timestampSource = TimestampSource.NMEA_ZDA,
        latDeg = 41.0 + i * 1e-6, lonDeg = -76.0, altEllipsoidalM = 300.0, altMslM = 333.0, geoidSeparationM = -33.0,
        hDop = 0.8, vDop = 1.2, pDop = null, hAccM = 0.02, vAccM = 0.03,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null, rtkStatus = RtkStatus.FIX,
        satsUsed = 20, satsVisible = 25, diffAgeS = 1.0, speedMps = null, courseDeg = null,
    )

    private fun inputs(f: Fix?) = ToolbarInputs(LocationSourceType.EXTERNAL, f, SkySnapshot(), CoordinateSystemSettings(), "RS2+")

    @Test fun `1b - conversion runs only for sampled inputs, on the compute dispatcher`() = runTest {
        val compute = StandardTestDispatcher(testScheduler, name = "toolbar-compute")
        var conversions = 0
        val source = MutableStateFlow(inputs(fix(0)))
        val frames = mutableListOf<ToolbarFrame>()
        val convert = { _: Fix, _: CoordinateSystemSettings -> conversions++; null as ConvertedPosition? }
        ToolbarPipeline.frames(source, convert, computeDispatcher = compute)
            .onEach { frames += it }
            .launchIn(backgroundScope)

        // A 5 Hz receiver for 2 s: 10 fixes, but the toolbar only maps the sampled ones (~8).
        repeat(10) { i -> source.value = inputs(fix(i + 1)); advanceTimeBy(200); runCurrent() }
        advanceTimeBy(300); runCurrent()
        assertTrue("sampled, not every fix: ${frames.size} frames", frames.size in 6..9)
        assertEquals("one conversion per frame", frames.size, conversions)
        assertTrue(frames.all { it.result is ToolbarMapResult.Render })
    }

    @Test fun `1a - the pipeline survives an error and resubscribes with backoff`() = runTest {
        var subscriptions = 0
        val errors = mutableListOf<Long>()
        val seen = mutableListOf<Int>()
        flow {
            subscriptions++
            if (subscriptions == 1) throw IllegalStateException("boom")
            emit(subscriptions)
            kotlinx.coroutines.awaitCancellation()
        }.retryForever { _, attempt -> errors += attempt }
            .onEach { seen += it }
            .launchIn(backgroundScope)

        runCurrent()
        assertEquals("error reported once", listOf(0L), errors)
        assertTrue("nothing yet: still backing off", seen.isEmpty())
        advanceTimeBy(1_001); runCurrent()
        assertEquals("resubscribed after the 1 s backoff and kept going", listOf(2), seen)
    }

    @Test fun `backoff grows 1 s, 2 s, 4 s and stays at 5 s`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 5_000L, 5_000L), (0L..4L).map { ToolbarPipeline.backoffMs(it) })
    }
}
