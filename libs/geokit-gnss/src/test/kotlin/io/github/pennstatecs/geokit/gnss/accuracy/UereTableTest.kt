package io.github.pennstatecs.geokit.gnss.accuracy

import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fallback UERE lookup used when the receiver reports no accuracy (accuracy = UERE × DOP). */
class UereTableTest {

    private val table = UereTable()

    @Test fun rs2Transports_shareTheSameTable() {
        RtkStatus.values().forEach { mode ->
            val ext = table.uere(Provider.EXTERNAL_TCP, mode)
            assertEquals(ext, table.uere(Provider.EXTERNAL_TCP, mode))
            assertEquals(ext, table.uere(Provider.EXTERNAL_BT, mode))
        }
    }

    @Test fun otherAndModelProviders_useInternalTable() {
        RtkStatus.values().forEach { mode ->
            val internal = table.uere(Provider.INTERNAL, mode)
            assertEquals(internal, table.uere(Provider.OTHER, mode))
            assertEquals(internal, table.uere(Provider.MODEL, mode))
        }
    }

    @Test fun rs2Values_improveWithRtkQuality() {
        val single = table.uere(Provider.EXTERNAL_TCP, RtkStatus.SINGLE)!!
        val dgps = table.uere(Provider.EXTERNAL_TCP, RtkStatus.DGPS)!!
        val float = table.uere(Provider.EXTERNAL_TCP, RtkStatus.FLOAT)!!
        val fix = table.uere(Provider.EXTERNAL_TCP, RtkStatus.FIX)!!
        assertTrue(single > dgps && dgps > float && float > fix)
        assertEquals(0.02, fix, 0.0)
    }

    @Test fun singleAndDeadReckoning_haveExplicitConservativeRows() {
        // RS2+ standalone used to fall back to the NONE value (1.0 m) — optimistic for a
        // first Emlid fix before any GST arrives.
        assertEquals(2.5, table.uere(Provider.EXTERNAL_TCP, RtkStatus.SINGLE)!!, 0.0)
        assertEquals(4.0, table.uere(Provider.INTERNAL, RtkStatus.SINGLE)!!, 0.0)
        assertTrue(table.uere(Provider.EXTERNAL_TCP, RtkStatus.DEAD_RECKONING)!! > 2.5)
        assertTrue(table.uere(Provider.INTERNAL, RtkStatus.DEAD_RECKONING)!! > 4.0)
    }

    @Test fun noFixOrInvalid_hasNoAccuracy() {
        Provider.values().forEach { p ->
            assertNull("$p/NONE", table.uere(p, RtkStatus.NONE))
            assertNull("$p/INVALID", table.uere(p, RtkStatus.INVALID))
        }
    }

    @Test fun everyRealFixMode_returnsPositiveValue() {
        val real = RtkStatus.values().filter { it != RtkStatus.NONE && it != RtkStatus.INVALID }
        Provider.values().forEach { p ->
            real.forEach { m -> assertTrue("$p/$m", table.uere(p, m)!! > 0.0) }
        }
    }
}
