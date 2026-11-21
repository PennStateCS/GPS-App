package app.surrealar.data.local.db

import io.github.pennstatecs.geokit.gnss.model.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider text written by every earlier version still loads with the same meaning after the
 * receiver-neutral rename; new values round-trip.
 */
class ProviderLegacyValuesTest {

    /** Every spelling the Room column reader accepted before the rename → its new value. */
    private val legacyColumn = mapOf(
        "RS2_EXTERNAL" to Provider.EXTERNAL_TCP, "rs2-external" to Provider.EXTERNAL_TCP,
        "rs2_external" to Provider.EXTERNAL_TCP, "external" to Provider.EXTERNAL_TCP,
        "external_gnss" to Provider.EXTERNAL_TCP,
        "RS2_TCP" to Provider.EXTERNAL_TCP, "rs2-tcp" to Provider.EXTERNAL_TCP, "rs2_tcp" to Provider.EXTERNAL_TCP,
        "RS2_BT" to Provider.EXTERNAL_BT, "rs2-bt" to Provider.EXTERNAL_BT, "rs2_bt" to Provider.EXTERNAL_BT,
        "INTERNAL" to Provider.INTERNAL, "internal" to Provider.INTERNAL, "fused" to Provider.INTERNAL,
        "internal_gps" to Provider.INTERNAL,
        "MODEL" to Provider.MODEL, "model" to Provider.MODEL, "model_embedded" to Provider.MODEL,
        "OTHER" to Provider.OTHER, "other" to Provider.OTHER, "something-unknown" to Provider.OTHER,
    )

    /** Every spelling the domain mapper (Coordinate.provider, backups) accepted before → new value. */
    private val legacyDomain = mapOf(
        "rs2-external" to Provider.EXTERNAL_TCP, "rs2_external" to Provider.EXTERNAL_TCP,
        "rs2-tcp" to Provider.EXTERNAL_TCP, "rs2_tcp" to Provider.EXTERNAL_TCP,
        "rs2-bt" to Provider.EXTERNAL_BT, "rs2_bt" to Provider.EXTERNAL_BT,
        "fused" to Provider.INTERNAL, "internal" to Provider.INTERNAL, "internal_gps" to Provider.INTERNAL,
        "model" to Provider.MODEL, "other" to Provider.OTHER,
        // Unchanged quirk of this path: these were never external here, so they stay OTHER.
        "external" to Provider.OTHER, "external_gnss" to Provider.OTHER,
    )

    @Test fun `every legacy column spelling maps to the matching new value`() {
        legacyColumn.forEach { (s, p) -> assertEquals(s, p, Converters.toProvider(s)) }
    }

    @Test fun `every legacy domain token maps to the matching new value`() {
        legacyDomain.forEach { (s, p) -> assertEquals(s, p, ProviderTokens.fromDomain(s)) }
    }

    @Test fun `new enum to stored name and back is the same enum`() {
        Provider.entries.forEach { p ->
            assertEquals(p.name, Converters.fromProvider(p))
            assertEquals(p, Converters.toProvider(Converters.fromProvider(p)))
        }
    }

    @Test fun `new enum to domain token and back is the same enum`() {
        Provider.entries.forEach { p -> assertEquals(p, ProviderTokens.fromDomain(ProviderTokens.toDomain(p))) }
        assertEquals("external-tcp", ProviderTokens.toDomain(Provider.EXTERNAL_TCP))
        assertEquals("fused", ProviderTokens.toDomain(Provider.INTERNAL))   // unchanged token
    }

    @Test fun `isExternal recognises current and legacy external tokens only`() {
        listOf("external-tcp", "external-bt", "rs2-external", "rs2-tcp", "rs2-bt").forEach { assertTrue(it, ProviderTokens.isExternal(it)) }
        listOf("fused", "model", "replay", "other", null).forEach { assertFalse("$it", ProviderTokens.isExternal(it)) }
    }
}
