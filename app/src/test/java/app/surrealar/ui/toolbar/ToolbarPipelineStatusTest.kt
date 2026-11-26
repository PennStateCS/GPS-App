package app.surrealar.ui.toolbar

import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.status.CorrectionHealth
import app.surrealar.receiver.selection.LiveSourceKind
import app.surrealar.receiver.selection.ReceiverTarget
import app.surrealar.receiver.selection.SourceTransition
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.ui.toolbar.ToolbarPipeline.next
import io.github.pennstatecs.geokit.gnss.capture.CapturePreset
import io.github.pennstatecs.geokit.gnss.capture.GnssCaptureSettings
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import io.github.pennstatecs.geokit.gnss.model.TimestampSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Pipeline state: correction hysteresis stage, last-fix memory and attribution, aging threshold source. */
class ToolbarPipelineStatusTest {

    private val target = ReceiverTarget("192.168.42.1", 9001)

    private fun fix(age: Double?, provider: Provider = Provider.EXTERNAL_TCP) = Fix(
        provider = provider, timeUtc = Instant.EPOCH, timestampSource = TimestampSource.NMEA_ZDA,
        latDeg = 41.2, lonDeg = -76.0, altEllipsoidalM = 300.0, altMslM = 333.0, geoidSeparationM = -33.0,
        hDop = 0.8, vDop = 1.2, pDop = null, hAccM = 0.02, vAccM = 0.03,
        stdDevEastM = null, stdDevNorthM = null, stdDevUpM = null, rtkStatus = RtkStatus.FIX,
        satsUsed = 20, satsVisible = 25, diffAgeS = age, speedMps = null, courseDeg = null,
    )

    private fun inputs(f: Fix?, key: String = "rs4@a", live: ToolbarLiveSource? = ToolbarLiveSource(LiveSourceKind.EXTERNAL, null)) =
        ToolbarInputs(LocationSourceType.EXTERNAL, f, SkySnapshot(), CoordinateSystemSettings(), "RS4", receiverKey = key, live = live)

    @Test fun `correction stage - immediate entry, held exit, reset on receiver change`() {
        val stage = ToolbarPipeline.CorrectionHealthStage()
        assertEquals(CorrectionHealth.OK, stage.update(inputs(fix(1.0)), 0))
        assertEquals(CorrectionHealth.STALE, stage.update(inputs(fix(16.0)), 250))
        assertEquals(CorrectionHealth.STALE, stage.update(inputs(fix(1.0)), 500))
        assertEquals("another receiver starts clean", CorrectionHealth.OK, stage.update(inputs(fix(1.0), key = "rs4@b"), 750))
        assertEquals(CorrectionHealth.STALE, stage.update(inputs(fix(16.0), key = "rs4@b"), 1_000))
        assertEquals("unattributed fix: not applicable", CorrectionHealth.OK,
            stage.update(inputs(fix(16.0), key = "rs4@b", live = ToolbarLiveSource(LiveSourceKind.NONE, SourceTransition.Switching(target))), 1_250))
    }

    @Test fun `correction stage follows the capture aging limit`() {
        val stage = ToolbarPipeline.CorrectionHealthStage()
        assertEquals(CorrectionHealth.OK, stage.update(inputs(fix(7.0)), 0))
        assertEquals(CorrectionHealth.AGING, stage.update(inputs(fix(7.0)).copy(correctionAgingS = 5.0), 250))
    }

    @Test fun `memory - unattributed external fixes are not remembered, a switch forgets`() {
        val m1 = (null as ToolbarPipeline.FixMemory?).next(inputs(fix(1.0)), null, 0)
        assertNotNull(m1)
        val unattributed = inputs(fix(1.0), live = ToolbarLiveSource(LiveSourceKind.NONE, SourceTransition.Connecting(target)))
        assertEquals("kept, not replaced", m1, m1.next(unattributed, null, 500))
        assertNull(m1.next(inputs(null, live = ToolbarLiveSource(LiveSourceKind.NONE, SourceTransition.Switching(target))), null, 600))
        assertNull("receiver change forgets", m1.next(inputs(null, key = "rs2@b"), null, 700))
    }

    @Test fun `aging threshold comes from the external capture policy`() {
        assertEquals(10.0, GnssCaptureSettings().toolbarAgingThresholdS(), 0.0)
        assertEquals(10.0, ToolbarInputs.DEFAULT_CORRECTION_AGING_S, 0.0)
        val custom = GnssCaptureSettings(preset = CapturePreset.CUSTOM, maxDiffAgeSec = 7)
        assertEquals(7.0, custom.toolbarAgingThresholdS(), 0.0)
    }
}
