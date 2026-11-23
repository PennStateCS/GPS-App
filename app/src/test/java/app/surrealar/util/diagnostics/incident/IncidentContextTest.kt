package app.surrealar.util.diagnostics.incident

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncidentContextTest {

    private object AllUnknown : IncidentContextInputs {
        override fun gnssSource(): String? = null
        override suspend fun receiverProfile(): String? = null
        override fun receiverLink(): String? = null
        override fun corrections(): String? = null
        override fun fixQuality(): FixQuality? = null
        override fun arState(): ArState? = null
        override fun freeStorageBytes(): Long? = null
        override fun permissions(): PermissionState? = null
    }

    private object AllThrow : IncidentContextInputs {
        override fun gnssSource(): String? = error("boom")
        override suspend fun receiverProfile(): String? = error("boom")
        override fun receiverLink(): String? = error("boom")
        override fun corrections(): String? = error("boom")
        override fun fixQuality(): FixQuality? = error("boom")
        override fun arState(): ArState? = error("boom")
        override fun freeStorageBytes(): Long? = error("boom")
        override fun permissions(): PermissionState? = error("boom")
    }

    @Test fun `all-unknown inputs render every field as unknown`() = runBlocking {
        for (inputs in listOf(AllUnknown, AllThrow)) {
            val c = IncidentContextCollector.collect(inputs)
            assertEquals(IncidentContext(), c)
            val lines = c.renderLines()
            assertEquals(18, lines.size)
            lines.forEach { assertTrue(it, it.endsWith(": unknown")) }
        }
    }

    @Test fun `non-finite and negative values become unknown`() = runBlocking {
        val c = IncidentContextCollector.collect(object : IncidentContextInputs by AllUnknown {
            override fun fixQuality() = FixQuality("  ", -5, Double.NaN, Double.POSITIVE_INFINITY, Double.NaN)
            override fun freeStorageBytes() = -1L
            override fun arState() = ArState(null, "", null, -1, null)
        })
        assertEquals(IncidentContext(), c)
    }

    @Test fun `safeText strips odd characters and caps length`() {
        assertEquals("CONNECTED", IncidentContext.safeText(" CONNECTED\n"))
        assertEquals("a b", IncidentContext.safeText("a\t\"b"))
        assertEquals("CONNECTED", IncidentContext.safeText("CONNECTED \$GNGGA,1,2"))
        assertEquals("first", IncidentContext.safeText("first\nsecond"))
        assertEquals(64, IncidentContext.safeText("A".repeat(200))!!.length)
        assertNull(IncidentContext.safeText("   "))
        assertEquals("receiving station=12 age=1.2s", IncidentContext.safeText("receiving station=12 age=1.2s"))
        assertEquals("[redacted]", IncidentContext.safeText("40.712812"))
    }
}
