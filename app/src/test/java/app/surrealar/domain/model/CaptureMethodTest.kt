package app.surrealar.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureMethodTest {

    @Test
    fun fromStorage_parsesCanonicalNames() {
        assertEquals(CaptureMethod.INTERNAL_GPS, CaptureMethod.fromStorage("INTERNAL_GPS"))
        assertEquals(CaptureMethod.EXTERNAL_GNSS, CaptureMethod.fromStorage("EXTERNAL_GNSS"))
        assertEquals(CaptureMethod.MODEL_EMBEDDED, CaptureMethod.fromStorage("MODEL_EMBEDDED"))
        assertEquals(CaptureMethod.SIMULATOR, CaptureMethod.fromStorage("SIMULATOR"))
    }

    @Test
    fun fromStorage_parsesLegacyFreeformStrings() {
        assertEquals(CaptureMethod.INTERNAL_GPS, CaptureMethod.fromStorage("internal_gps"))
        assertEquals(CaptureMethod.INTERNAL_GPS, CaptureMethod.fromStorage("fused"))
        assertEquals(CaptureMethod.EXTERNAL_GNSS, CaptureMethod.fromStorage("external_gnss"))
        assertEquals(CaptureMethod.EXTERNAL_GNSS, CaptureMethod.fromStorage("averaged"))
        assertEquals(CaptureMethod.MODEL_EMBEDDED, CaptureMethod.fromStorage("model_embedded"))
        assertEquals(CaptureMethod.IMPORTED, CaptureMethod.fromStorage("imported"))
    }

    @Test
    fun fromStorage_returnsNullForBlankOrUnknown() {
        assertNull(CaptureMethod.fromStorage(null))
        assertNull(CaptureMethod.fromStorage(""))
        assertNull(CaptureMethod.fromStorage("   "))
        assertNull(CaptureMethod.fromStorage("nonsense"))
    }

    @Test
    fun referenceUnconfirmed_isAppended_andParsed() {
        // Appended after the existing values: ordinals / stored names of older values unchanged.
        assertEquals(CaptureMethod.values().size - 1, CaptureMethod.EXTERNAL_GNSS_REFERENCE_UNCONFIRMED.ordinal)
        assertEquals("EXTERNAL_GNSS_REFERENCE_UNCONFIRMED", CaptureMethod.EXTERNAL_GNSS_REFERENCE_UNCONFIRMED.storageValue)
        assertEquals(CaptureMethod.EXTERNAL_GNSS_REFERENCE_UNCONFIRMED, CaptureMethod.fromStorage("external_gnss_reference_unconfirmed"))
        assertEquals(CaptureMethod.EXTERNAL_GNSS_POLE_TIP, CaptureMethod.fromStorage("EXTERNAL_GNSS_POLE_TIP"))
        assertEquals("External GNSS (reference unconfirmed)", CaptureMethod.EXTERNAL_GNSS_REFERENCE_UNCONFIRMED.displayName)
    }

    @Test
    fun positionReferenceKind_fromCaptureMethod() {
        assertEquals(PositionReferenceKind.ANTENNA, PositionReferenceKind.of("external_gnss", externalProvider = true))
        assertEquals(PositionReferenceKind.ANTENNA, PositionReferenceKind.of("EXTERNAL_GNSS", externalProvider = true))
        assertNull("simulator 'averaged' rows are not labelled", PositionReferenceKind.of("averaged", externalProvider = false))
        assertEquals(PositionReferenceKind.RECEIVER_POLE_TIP, PositionReferenceKind.of("EXTERNAL_GNSS_POLE_TIP", externalProvider = true))
        assertEquals(PositionReferenceKind.UNCONFIRMED, PositionReferenceKind.of("EXTERNAL_GNSS_REFERENCE_UNCONFIRMED", externalProvider = true))
        for (m in listOf("internal_gps", "manual", "imported", "model_embedded", "map_tap", null, "", "nonsense")) {
            assertNull(m, PositionReferenceKind.of(m, externalProvider = false))
        }
        assertNull(PositionReferenceKind.of("internal_gps", externalProvider = true))
        assertEquals(listOf("antenna", "receiver_pole_tip", "unconfirmed"), PositionReferenceKind.values().map { it.token })
    }

    @Test
    fun storageValue_roundTrips() {
        for (m in CaptureMethod.values()) {
            assertEquals(m, CaptureMethod.fromStorage(m.storageValue))
        }
    }
}
