package app.surrealar.util

import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests that the diagnostic exporter's selected-vs-active source mismatch warning is generated from
 * the SAME classifier the coordinator uses ([app.surrealar.gnss.source.SourceRoutingDecisions]), so
 * the export can't describe the mismatch differently from how routing decided it.
 *
 * Exercises [DiagnosticReportExporter.sourceMismatchWarning] directly — pure (enums + string), no
 * Android/Context/network.
 */
class DiagnosticSourceWarningTest {

    private fun warn(selected: LocationSourceType?, active: ProviderChoice?, configured: Boolean) =
        DiagnosticReportExporter.sourceMismatchWarning(selected, active, configured)

    @Test fun `external selected, internal active, not configured`() {
        assertEquals(
            "SOURCE mismatch: selected=EXTERNAL active=INTERNAL reason=\"external_not_configured\"",
            warn(LocationSourceType.EXTERNAL, ProviderChoice.INTERNAL, configured = false)
        )
    }

    @Test fun `external selected, internal active, configured but unavailable or connecting`() {
        assertEquals(
            "SOURCE mismatch: selected=EXTERNAL active=INTERNAL reason=\"external_unavailable_or_connecting\"",
            warn(LocationSourceType.EXTERNAL, ProviderChoice.INTERNAL, configured = true)
        )
    }

    @Test fun `external selected and external active is aligned (no warning)`() {
        assertNull(warn(LocationSourceType.EXTERNAL, ProviderChoice.EXTERNAL_TCP, configured = true))
    }

    @Test fun `internal selected and internal active is aligned (no warning)`() {
        assertNull(warn(LocationSourceType.INTERNAL, ProviderChoice.INTERNAL, configured = false))
    }

    @Test fun `reverse mismatch - internal selected but external active is flagged`() {
        assertEquals(
            "SOURCE mismatch: selected=INTERNAL active=EXTERNAL_TCP reason=\"active_external_while_internal_selected\"",
            warn(LocationSourceType.INTERNAL, ProviderChoice.EXTERNAL_TCP, configured = true)
        )
    }

    @Test fun `simulator selected with internal active is NOT a false mismatch`() {
        // Regression guard: the old string heuristic flagged SIMULATOR vs INTERNAL; the classifier
        // correctly treats internal-as-active for a non-external selection as aligned.
        assertNull(warn(LocationSourceType.SIMULATOR, ProviderChoice.INTERNAL, configured = false))
    }

    @Test fun `null selected or active produces no warning`() {
        assertNull(warn(null, ProviderChoice.INTERNAL, configured = false))
        assertNull(warn(LocationSourceType.EXTERNAL, null, configured = false))
    }
}
