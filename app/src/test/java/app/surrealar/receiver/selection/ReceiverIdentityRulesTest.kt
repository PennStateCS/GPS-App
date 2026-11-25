package app.surrealar.receiver.selection

import app.surrealar.receiver.selection.ReceiverIdentityRules.Decision
import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.settings.model.ExternalReceiverProfile.REACH_RS2_PLUS
import app.surrealar.settings.model.ExternalReceiverProfile.REACH_RS4
import app.surrealar.settings.model.ExternalReceiverProfile.REACH_RS4_PRO
import app.surrealar.settings.model.ProfileOrigin
import io.github.pennstatecs.geokit.gnss.receiver.ReceiverDeviceInfo
import io.github.pennstatecs.geokit.receiver.emlid.EmlidReachModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 5 identification (device info only) and the automatic-profile / mismatch rules, incl. pre-Phase-5 origins. */
class ReceiverIdentityRulesTest {

    private val host = "192.168.42.1"
    private fun info(model: String?, h: String? = host) =
        ReceiverDeviceInfo(host = h, name = "reach", model = model, firmware = "34.10", serial = "SN1")

    // ── identify ──────────────────────────────────────────────────────────────

    @Test fun `a reported model for the saved host of the active receiver is verified`() {
        val id = ReceiverIdentityRules.identify(info("Reach RS4 Pro"), " 192.168.42.1 ", externalActive = true)!!
        assertTrue(id.modelVerified)
        assertEquals(EmlidReachModel.RS4_PRO, id.model)
        assertEquals(REACH_RS4_PRO, id.recognizedProfile)
        assertEquals("SN1", id.serial)
        assertEquals("34.10", id.firmware)
    }

    @Test fun `only the active external receiver at the saved host is identified`() {
        assertNull("device GNSS active", ReceiverIdentityRules.identify(info("Reach RS4"), host, externalActive = false))
        assertNull("another host (stale poll)", ReceiverIdentityRules.identify(info("Reach RS4", "192.168.42.7"), host, true))
        assertNull("no host in the info", ReceiverIdentityRules.identify(info("Reach RS4", null), host, true))
        assertNull("no saved host", ReceiverIdentityRules.identify(info("Reach RS4"), null, true))
        assertNull("no info", ReceiverIdentityRules.identify(null, host, true))
        assertNull("a failed poll reports nothing",
            ReceiverIdentityRules.identify(ReceiverDeviceInfo(host = host), host, true))
    }

    @Test fun `an ambiguous or unknown model is reported but not verified`() {
        for (m in listOf("Reach RS2", "Reach RS3", null)) {
            val id = ReceiverIdentityRules.identify(info(m), host, true)!!
            assertFalse(m.toString(), id.modelVerified)
            assertNull(id.recognizedProfile)
        }
    }

    @Test fun `profiles for identified models`() {
        assertEquals(REACH_RS2_PLUS, ReceiverIdentityRules.profileFor(EmlidReachModel.RS2_PLUS))
        assertEquals(REACH_RS4, ReceiverIdentityRules.profileFor(EmlidReachModel.RS4))
        assertEquals(REACH_RS4_PRO, ReceiverIdentityRules.profileFor(EmlidReachModel.RS4_PRO))
        assertNull(ReceiverIdentityRules.profileFor(EmlidReachModel.RS2))
        assertNull(ReceiverIdentityRules.profileFor(null))
    }

    // ── decide ────────────────────────────────────────────────────────────────

    private val rs4 = ReceiverIdentityRules.identify(info("Reach RS4"), host, true)

    @Test fun `default and receiver-set profiles are replaced automatically when no capture runs`() {
        assertEquals(Decision.AutoApply(REACH_RS4), ReceiverIdentityRules.decide(rs4, REACH_RS2_PLUS, ProfileOrigin.DEFAULT, false))
        assertEquals(Decision.AutoApply(REACH_RS4), ReceiverIdentityRules.decide(rs4, REACH_RS4_PRO, ProfileOrigin.RECEIVER, false))
    }

    @Test fun `a user profile gives a mismatch, until the user keeps it`() {
        val d = ReceiverIdentityRules.decide(rs4, REACH_RS2_PLUS, ProfileOrigin.USER, false) as Decision.Mismatch
        assertEquals(ProfileMismatch(host, "Reach RS4", REACH_RS4, REACH_RS2_PLUS, MismatchReason.PROFILE_SET_BY_USER), d.mismatch)
        val kept = setOf(ReceiverIdentityRules.dismissKey(host, "Reach RS4", REACH_RS2_PLUS))
        assertEquals(Decision.None, ReceiverIdentityRules.decide(rs4, REACH_RS2_PLUS, ProfileOrigin.USER, false, kept))
        // "Keep" for RS2+ says nothing about another current profile.
        assertTrue(ReceiverIdentityRules.decide(rs4, ExternalReceiverProfile.REACH_RS4_PRO, ProfileOrigin.USER, false, kept) is Decision.Mismatch)
    }

    @Test fun `never automatic during a capture, whatever the origin`() {
        for (o in ProfileOrigin.entries) {
            val d = ReceiverIdentityRules.decide(rs4, REACH_RS2_PLUS, o, captureActive = true) as Decision.Mismatch
            assertEquals(MismatchReason.CAPTURE_ACTIVE, d.mismatch.reason)
        }
    }

    @Test fun `nothing to do when it matches, is unknown, or there is no identity`() {
        assertEquals(Decision.None, ReceiverIdentityRules.decide(rs4, REACH_RS4, ProfileOrigin.DEFAULT, false))
        assertEquals(Decision.None, ReceiverIdentityRules.decide(null, REACH_RS4, ProfileOrigin.DEFAULT, false))
        val rs2 = ReceiverIdentityRules.identify(info("Reach RS2"), host, true)
        assertEquals("plain RS2 keeps the manual profile", Decision.None,
            ReceiverIdentityRules.decide(rs2, REACH_RS4, ProfileOrigin.DEFAULT, false))
    }

    // ── existing users (no origin key) ─────────────────────────────────────────

    @Test fun `origin of installs from before Phase 5`() {
        assertEquals("a profile picked before Phase 5 is the user's", ProfileOrigin.USER, ProfileOrigin.resolve(null, profileStored = true))
        assertEquals("never picked", ProfileOrigin.DEFAULT, ProfileOrigin.resolve(null, profileStored = false))
        assertEquals(ProfileOrigin.RECEIVER, ProfileOrigin.resolve("receiver", true))
        assertEquals(ProfileOrigin.DEFAULT, ProfileOrigin.resolve("default", true))
        assertEquals(ProfileOrigin.USER, ProfileOrigin.resolve("user", false))
        assertEquals("unknown token: conservative", ProfileOrigin.USER, ProfileOrigin.resolve("bogus", false))
    }

    @Test fun `an existing user's explicit profile is never auto-replaced`() {
        val origin = ProfileOrigin.resolve(null, profileStored = true)
        assertTrue(ReceiverIdentityRules.decide(rs4, REACH_RS2_PLUS, origin, false) is Decision.Mismatch)
        val fresh = ProfileOrigin.resolve(null, profileStored = false)
        assertEquals(Decision.AutoApply(REACH_RS4), ReceiverIdentityRules.decide(rs4, REACH_RS2_PLUS, fresh, false))
    }
}
