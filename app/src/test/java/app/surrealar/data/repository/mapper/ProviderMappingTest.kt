package app.surrealar.data.repository.mapper

import app.surrealar.domain.model.Coordinate
import app.surrealar.gnss.model.Provider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the provider round-trip so external-GNSS captures don't degrade on save/load.
 * Regression: RS2_EXTERNAL previously collapsed to RS2_TCP.
 */
class ProviderMappingTest {

    private fun coord(provider: String) = Coordinate(
        id = "1", name = "P", latitude = 41.0, longitude = -75.0, altitude = 0.0,
        timestamp = 0L, icon = "", color = 0, provider = provider
    )

    private fun roundTripProvider(domainProvider: String): Provider =
        coord(domainProvider).toEntity().provider

    @Test
    fun rs2External_survivesRoundTrip() {
        assertEquals(Provider.RS2_EXTERNAL, roundTripProvider("rs2-external"))
    }

    @Test
    fun rs2Tcp_andBt_mapDistinctly() {
        assertEquals(Provider.RS2_TCP, roundTripProvider("rs2-tcp"))
        assertEquals(Provider.RS2_BT, roundTripProvider("rs2-bt"))
    }

    @Test
    fun internal_mapsToInternal() {
        assertEquals(Provider.INTERNAL, roundTripProvider("fused"))
    }

    @Test
    fun fullRoundTrip_entityToDomainToEntity_isStable() {
        for (p in listOf("rs2-external", "rs2-tcp", "rs2-bt", "fused")) {
            val once = coord(p).toEntity()
            val twice = once.toDomain().toEntity()
            assertEquals("provider should be stable for $p", once.provider, twice.provider)
        }
    }

    @Test
    fun newFields_roundTripThroughMapper() {
        val c = coord("fused").copy(
            modelId = "m1", iconKey = null, renderEnabled = false,
            createdAt = 111L, updatedAt = 222L, modelScale = 2.5,
            modelYawDeg = 90.0, modelVerticalOffsetM = 1.5
        )
        val back = c.toEntity().toDomain()
        assertEquals("m1", back.modelId)
        assertEquals(false, back.renderEnabled)
        assertEquals(111L, back.createdAt)
        assertEquals(222L, back.updatedAt)
        assertEquals(2.5, back.modelScale!!, 1e-9)
        assertEquals(90.0, back.modelYawDeg!!, 1e-9)
        assertEquals(1.5, back.modelVerticalOffsetM!!, 1e-9)
    }
}
