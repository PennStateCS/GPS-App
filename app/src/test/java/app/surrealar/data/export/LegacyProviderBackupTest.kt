package app.surrealar.data.export

import app.surrealar.data.repository.mapper.toEntity
import app.surrealar.domain.model.Coordinate
import app.surrealar.gnss.model.Provider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A backup written before the receiver-neutral rename imports with the same provider meaning. */
// org.json is stubbed in plain JVM unit tests; Robolectric provides a real implementation.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyProviderBackupTest {

    private fun coord(id: String, provider: String) = Coordinate(
        id = id, name = "P $id", latitude = 41.3478, longitude = -76.0226, altitude = 399.0,
        timestamp = 1_000L, icon = "pin", color = -1, provider = provider, sourceDevice = "RS2+",
    )

    @Test fun `legacy provider tokens in a backup load as the matching new values`() {
        val legacy = mapOf(
            "rs2-external" to Provider.EXTERNAL_TCP, "rs2-tcp" to Provider.EXTERNAL_TCP,
            "rs2-bt" to Provider.EXTERNAL_BT, "fused" to Provider.INTERNAL, "model" to Provider.MODEL,
        )
        val json = CoordinateBackup.export(legacy.keys.mapIndexed { i, p -> coord("c$i", p) }, emptyList(), "old")
        val imported = CoordinateBackup.parse(json).coordinates
        assertEquals(legacy.size, imported.size)
        imported.forEachIndexed { i, c ->
            val token = legacy.keys.elementAt(i)
            assertEquals(token, c.provider)                                  // kept verbatim on import
            assertEquals(token, legacy.getValue(token), c.toEntity().provider) // same meaning when stored
            assertEquals("RS2+", c.sourceDevice)                             // existing values untouched
        }
    }
}
