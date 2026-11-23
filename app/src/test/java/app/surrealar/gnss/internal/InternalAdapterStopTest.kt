package app.surrealar.gnss.internal

import io.github.pennstatecs.geokit.gnss.bus.adapters.GsvMessage
import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaFuser
import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaSource
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.satellites.SatelliteInventory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/** stop() without a start is a no-op: the source isn't stopped (no "stopped" line, no parse summary). */
class InternalAdapterStopTest {

    private class CountingSource : NmeaSource {
        var starts = 0
        var stops = 0
        override fun start() { starts++ }
        override fun stop() { stops++ }
        override fun parsedFixes(): SharedFlow<Fix> = MutableSharedFlow()
        override fun gsvStream(): SharedFlow<GsvMessage> = MutableSharedFlow()
        override fun rawNmeaEvents(): SharedFlow<Unit> = MutableSharedFlow()
        override fun nmeaTimingStats(): NmeaFuser.FuserTimingStats = error("unused")
        override fun nmeaExtraStats(): NmeaFuser.ExtraSentenceStats = error("unused")
    }

    @Test fun `stop when not started does nothing, stop after start stops once`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val source = CountingSource()
        val adapter = InternalAdapter(scope, source, SatelliteInventory())

        adapter.stop()
        assertEquals("not started: source untouched", 0, source.stops)

        adapter.start()
        adapter.stop()
        adapter.stop()
        assertEquals(1, source.starts)
        assertEquals("second stop is a no-op", 1, source.stops)
        scope.cancel()
    }
}
