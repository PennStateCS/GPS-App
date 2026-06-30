package app.surrealar.gnss.source

import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.source.SourceRoutingDecisions.MismatchKind
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [SourceRoutingDecisions] — the selected-vs-active source routing decisions
 * behind [GnssSourceCoordinator]. These guard against the app silently treating an unconfigured /
 * unavailable external receiver as the active source (a real user-reported `selected=EXTERNAL
 * active=INTERNAL` mismatch).
 */
class SourceRoutingDecisionsTest {

    // ── externalConfigUsable ────────────────────────────────────────────────────────────────────

    @Test fun `external config usable only with non-blank host and non-null port`() {
        assertTrue(SourceRoutingDecisions.externalConfigUsable("192.168.2.174", 9001))
        assertFalse("null host", SourceRoutingDecisions.externalConfigUsable(null, 9001))
        assertFalse("blank host", SourceRoutingDecisions.externalConfigUsable("   ", 9001))
        assertFalse("null port", SourceRoutingDecisions.externalConfigUsable("192.168.2.174", null))
        assertFalse("both missing", SourceRoutingDecisions.externalConfigUsable(null, null))
    }

    // ── resolveStartupProvider ──────────────────────────────────────────────────────────────────

    @Test fun `internal selected resolves to internal`() {
        val r = SourceRoutingDecisions.resolveStartupProvider(LocationSourceType.INTERNAL, null, null)
        assertEquals(ProviderChoice.INTERNAL, r.provider)
        assertEquals("startup-restore-internal", r.reason)
    }

    @Test fun `simulator selected resolves to internal (not external)`() {
        val r = SourceRoutingDecisions.resolveStartupProvider(LocationSourceType.SIMULATOR, "h", 9001)
        assertEquals(ProviderChoice.INTERNAL, r.provider)
        assertEquals("startup-restore-internal", r.reason)
    }

    @Test fun `external selected with valid config resolves to external`() {
        val r = SourceRoutingDecisions.resolveStartupProvider(LocationSourceType.EXTERNAL, "10.0.0.5", 9000)
        assertEquals(ProviderChoice.EXTERNAL_TCP, r.provider)
        assertEquals("startup-restore", r.reason)
    }

    @Test fun `external selected but unconfigured resolves to internal with no-host reason`() {
        listOf(
            SourceRoutingDecisions.resolveStartupProvider(LocationSourceType.EXTERNAL, null, 9001),
            SourceRoutingDecisions.resolveStartupProvider(LocationSourceType.EXTERNAL, "  ", 9001),
            SourceRoutingDecisions.resolveStartupProvider(LocationSourceType.EXTERNAL, "10.0.0.5", null),
        ).forEach {
            assertEquals(ProviderChoice.INTERNAL, it.provider)   // never pretends external is active
            assertEquals("startup-restore-no-host", it.reason)
        }
    }

    // ── classifySelectedActiveMismatch ──────────────────────────────────────────────────────────

    @Test fun `aligned states are not a mismatch`() {
        val internalOk = SourceRoutingDecisions.classifySelectedActiveMismatch(
            LocationSourceType.INTERNAL, ProviderChoice.INTERNAL, externalConfigured = false)
        assertEquals(MismatchKind.NONE, internalOk.kind)
        assertNull(internalOk.reason)
        assertFalse(internalOk.isMismatch)

        val externalOk = SourceRoutingDecisions.classifySelectedActiveMismatch(
            LocationSourceType.EXTERNAL, ProviderChoice.EXTERNAL_TCP, externalConfigured = true)
        assertFalse(externalOk.isMismatch)
    }

    @Test fun `external selected but internal active with no config reports external_not_configured`() {
        val m = SourceRoutingDecisions.classifySelectedActiveMismatch(
            LocationSourceType.EXTERNAL, ProviderChoice.INTERNAL, externalConfigured = false)
        assertEquals(MismatchKind.EXTERNAL_SELECTED_INTERNAL_ACTIVE, m.kind)
        assertEquals("external_not_configured", m.reason)
        assertTrue(m.isMismatch)
    }

    @Test fun `external selected but internal active with config reports unavailable_or_connecting`() {
        val m = SourceRoutingDecisions.classifySelectedActiveMismatch(
            LocationSourceType.EXTERNAL, ProviderChoice.INTERNAL, externalConfigured = true)
        assertEquals(MismatchKind.EXTERNAL_SELECTED_INTERNAL_ACTIVE, m.kind)
        assertEquals("external_unavailable_or_connecting", m.reason)
    }

    @Test fun `internal selected but external active is flagged`() {
        val m = SourceRoutingDecisions.classifySelectedActiveMismatch(
            LocationSourceType.INTERNAL, ProviderChoice.EXTERNAL_TCP, externalConfigured = true)
        assertEquals(MismatchKind.INTERNAL_SELECTED_EXTERNAL_ACTIVE, m.kind)
        assertEquals("active_external_while_internal_selected", m.reason)
    }

    @Test fun `simulator selected but external active is flagged`() {
        val m = SourceRoutingDecisions.classifySelectedActiveMismatch(
            LocationSourceType.SIMULATOR, ProviderChoice.EXTERNAL_TCP, externalConfigured = true)
        assertEquals(MismatchKind.INTERNAL_SELECTED_EXTERNAL_ACTIVE, m.kind)
        assertEquals("active_external_while_simulator_selected", m.reason)
    }
}
