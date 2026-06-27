package com.example.surveyingapp.domain.model

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
    fun storageValue_roundTrips() {
        for (m in CaptureMethod.values()) {
            assertEquals(m, CaptureMethod.fromStorage(m.storageValue))
        }
    }
}
