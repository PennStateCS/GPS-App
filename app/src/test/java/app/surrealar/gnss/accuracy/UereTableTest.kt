package app.surrealar.gnss.accuracy

import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.model.RtkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fallback UERE lookup used when the receiver reports no accuracy (accuracy = UERE × DOP). */
class UereTableTest {

    private val table = UereTable()

    @Test fun rs2Transports_shareTheSameTable() {
        RtkStatus.values().forEach { mode ->
            val ext = table.uere(Provider.RS2_EXTERNAL, mode)
            assertEquals(ext, table.uere(Provider.RS2_TCP, mode), 0.0)
            assertEquals(ext, table.uere(Provider.RS2_BT, mode), 0.0)
        }
    }

    @Test fun otherAndModelProviders_useInternalTable() {
        RtkStatus.values().forEach { mode ->
            val internal = table.uere(Provider.INTERNAL, mode)
            assertEquals(internal, table.uere(Provider.OTHER, mode), 0.0)
            assertEquals(internal, table.uere(Provider.MODEL, mode), 0.0)
        }
    }

    @Test fun rs2Values_improveWithRtkQuality() {
        val none = table.uere(Provider.RS2_EXTERNAL, RtkStatus.NONE)
        val dgps = table.uere(Provider.RS2_EXTERNAL, RtkStatus.DGPS)
        val float = table.uere(Provider.RS2_EXTERNAL, RtkStatus.FLOAT)
        val fix = table.uere(Provider.RS2_EXTERNAL, RtkStatus.FIX)
        assertTrue(none > dgps && dgps > float && float > fix)
        assertEquals(0.02, fix, 0.0)
    }

    @Test fun unmappedModes_fallBackConservatively() {
        // SINGLE / INVALID / DEAD_RECKONING are not in the default maps.
        assertEquals(1.0, table.uere(Provider.RS2_EXTERNAL, RtkStatus.SINGLE), 0.0)
        assertEquals(4.0, table.uere(Provider.INTERNAL, RtkStatus.DEAD_RECKONING), 0.0)
    }

    @Test fun everyProviderAndMode_returnsPositiveValue() {
        Provider.values().forEach { p ->
            RtkStatus.values().forEach { m -> assertTrue("$p/$m", table.uere(p, m) > 0.0) }
        }
    }
}
