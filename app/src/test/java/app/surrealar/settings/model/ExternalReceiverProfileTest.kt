package app.surrealar.settings.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalReceiverProfileTest {

    @Test
    fun defaultIsRs2Plus_preservingExistingBehavior() {
        assertEquals(ExternalReceiverProfile.REACH_RS2_PLUS, ExternalReceiverProfile.DEFAULT)
        assertEquals("RS2+", ExternalReceiverProfile.DEFAULT.shortLabel)
    }

    @Test
    fun fromPrefKey_roundTripsEveryProfile() {
        ExternalReceiverProfile.entries.forEach { p ->
            assertEquals(p, ExternalReceiverProfile.fromPrefKey(p.prefKey))
        }
    }

    @Test
    fun fromPrefKey_unknownOrNull_fallsBackToDefault() {
        assertEquals(ExternalReceiverProfile.DEFAULT, ExternalReceiverProfile.fromPrefKey(null))
        assertEquals(ExternalReceiverProfile.DEFAULT, ExternalReceiverProfile.fromPrefKey(""))
        assertEquals(ExternalReceiverProfile.DEFAULT, ExternalReceiverProfile.fromPrefKey("nope"))
    }

    @Test
    fun rs4Profiles_defaultToPort9001_withSetupHint() {
        assertEquals(9001, ExternalReceiverProfile.REACH_RS4.defaultPort)
        assertEquals(9001, ExternalReceiverProfile.REACH_RS4_PRO.defaultPort)
        assert(ExternalReceiverProfile.REACH_RS4.hint!!.contains("Position Streaming", ignoreCase = true))
        assert(ExternalReceiverProfile.REACH_RS4_PRO.hint!!.contains("NMEA", ignoreCase = true))
    }

    @Test
    fun rs2AndGeneric_haveNoHint_andGenericUses9000() {
        assertNull(ExternalReceiverProfile.REACH_RS2_PLUS.hint)
        assertNull(ExternalReceiverProfile.GENERIC_NMEA_TCP.hint)
        assertEquals(9000, ExternalReceiverProfile.GENERIC_NMEA_TCP.defaultPort)
        assertEquals(9001, ExternalReceiverProfile.REACH_RS2_PLUS.defaultPort)
    }

    @Test
    fun portForProfileChange_keepsCustomPort() {
        // 2947 is not any profile's default → custom → preserved.
        assertEquals(2947, ExternalReceiverProfile.portForProfileChange(2947, ExternalReceiverProfile.REACH_RS4))
    }

    @Test
    fun portForProfileChange_appliesNewDefaultWhenAtAProfileDefault() {
        assertEquals(9001, ExternalReceiverProfile.portForProfileChange(9000, ExternalReceiverProfile.REACH_RS4))
        assertEquals(9001, ExternalReceiverProfile.portForProfileChange(9001, ExternalReceiverProfile.REACH_RS4_PRO))
        assertEquals(9000, ExternalReceiverProfile.portForProfileChange(9001, ExternalReceiverProfile.GENERIC_NMEA_TCP))
    }

    @Test
    fun portForProfileChange_appliesNewDefaultForNullOrInvalid() {
        assertEquals(9001, ExternalReceiverProfile.portForProfileChange(null, ExternalReceiverProfile.REACH_RS4))
        assertEquals(9001, ExternalReceiverProfile.portForProfileChange(0, ExternalReceiverProfile.REACH_RS4))
        assertEquals(9000, ExternalReceiverProfile.portForProfileChange(70000, ExternalReceiverProfile.GENERIC_NMEA_TCP))
    }

    @Test
    fun labels_areDistinctAndHumanReadable() {
        assertEquals("Emlid Reach RS4", ExternalReceiverProfile.REACH_RS4.label)
        assertEquals("Emlid Reach RS4 Pro", ExternalReceiverProfile.REACH_RS4_PRO.label)
        assertEquals("RS4", ExternalReceiverProfile.REACH_RS4.shortLabel)
        assertEquals("RS4 Pro", ExternalReceiverProfile.REACH_RS4_PRO.shortLabel)
    }
}
