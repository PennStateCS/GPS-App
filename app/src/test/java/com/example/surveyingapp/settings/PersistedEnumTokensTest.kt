package com.example.surveyingapp.settings

import com.example.surveyingapp.domain.model.ExternalConnectionType
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.gnss.model.RtkStatus
import com.example.surveyingapp.settings.model.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the persistence contract for every enum-backed setting: each value round-trips through its
 * stable prefKey, the legacy uppercase constant name still parses (backward compatibility), and
 * unknown/null tokens fall back to the documented default.
 */
class PersistedEnumTokensTest {

    @Test
    fun `LocationSourceType prefKey round-trip + legacy name + fallback`() {
        LocationSourceType.entries.forEach {
            assertEquals(it, LocationSourceType.fromPrefKey(it.prefKey))
            assertEquals("legacy name must still parse", it, LocationSourceType.fromPrefKey(it.name))
        }
        assertEquals(LocationSourceType.INTERNAL, LocationSourceType.fromPrefKey("nope"))
        assertEquals(LocationSourceType.INTERNAL, LocationSourceType.fromPrefKey(null))
        assertEquals("internal", LocationSourceType.INTERNAL.prefKey)
    }

    @Test
    fun `ExternalConnectionType prefKey round-trip + legacy name + fallback`() {
        ExternalConnectionType.entries.forEach {
            assertEquals(it, ExternalConnectionType.fromPrefKey(it.prefKey))
            assertEquals(it, ExternalConnectionType.fromPrefKey(it.name))
        }
        // A legacy saved Bluetooth value must still resolve to BT (token + legacy uppercase name).
        assertEquals(ExternalConnectionType.BT, ExternalConnectionType.fromPrefKey("bt"))
        assertEquals(ExternalConnectionType.BT, ExternalConnectionType.fromPrefKey("BT"))
        // Unknown/missing now falls back to TCP — the only implemented external transport.
        assertEquals(ExternalConnectionType.TCP, ExternalConnectionType.fromPrefKey("nope"))
        assertEquals(ExternalConnectionType.TCP, ExternalConnectionType.fromPrefKey(null))
        assertEquals("tcp", ExternalConnectionType.TCP.prefKey)
    }

    @Test
    fun `AppThemeMode prefKey round-trip + legacy name + fallback`() {
        AppThemeMode.entries.forEach {
            assertEquals(it, AppThemeMode.fromPrefKey(it.prefKey))
            assertEquals(it, AppThemeMode.fromPrefKey(it.name))
        }
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromPrefKey("nope"))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.fromPrefKey(null))
    }

    @Test
    fun `RtkStatus prefKey round-trip + legacy name + context default`() {
        RtkStatus.entries.forEach {
            assertEquals(it, RtkStatus.fromPrefKey(it.prefKey))
            assertEquals(it, RtkStatus.fromPrefKey(it.name))
        }
        // Capture context default is FIX; general default is NONE.
        assertEquals(RtkStatus.FIX, RtkStatus.fromPrefKey("nope", default = RtkStatus.FIX))
        assertEquals(RtkStatus.NONE, RtkStatus.fromPrefKey(null))
    }
}
