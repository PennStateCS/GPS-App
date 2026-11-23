package app.surrealar.ui.openinar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FloorHysteresisTest {

    private fun h() = FloorHysteresis(deadbandM = 0.03f, stableFrames = 3)

    @Test
    fun firstHeight_isTakenImmediately() {
        val f = h()
        assertNull(f.update(null))
        assertEquals(-1.5f, f.update(-1.5f)!!, 0f)
    }

    @Test
    fun smallChanges_areIgnored() {
        val f = h()
        f.update(-1.50f)
        repeat(10) { assertEquals(-1.50f, f.update(-1.52f)!!, 0f) }
        assertEquals(-1.50f, f.update(-1.48f)!!, 0f)
    }

    @Test
    fun largeChange_adoptedOnlyAfterStableFrames() {
        val f = h()
        f.update(-1.5f)
        assertEquals(-1.5f, f.update(-0.8f)!!, 0f)
        assertEquals(-1.5f, f.update(-0.81f)!!, 0f)
        assertEquals(-0.8f, f.update(-0.8f)!!, 0f)   // third consecutive frame near -0.8: adopted
    }

    @Test
    fun flickeringCandidate_neverCommits() {
        val f = h()
        f.update(-1.5f)
        repeat(20) { i ->
            val raw = if (i % 2 == 0) -0.8f else -1.5f   // a step plane flickers in and out
            assertEquals(-1.5f, f.update(raw)!!, 0f)
        }
    }

    @Test
    fun alternatingCandidates_restartCount() {
        val f = h()
        f.update(-1.5f)
        f.update(-0.8f); f.update(-0.8f)
        f.update(-0.2f)                                // a different candidate restarts the count
        assertEquals(-1.5f, f.update(-0.2f)!!, 0f)
        assertEquals(-0.2f, f.update(-0.2f)!!, 0f)
    }

    @Test
    fun lostFloor_heldUntilStableThenNull_thenNextFloorImmediate() {
        val f = h()
        f.update(-1.5f)
        assertEquals(-1.5f, f.update(null)!!, 0f)
        assertEquals(-1.5f, f.update(null)!!, 0f)
        assertNull(f.update(null))
        assertEquals(-1.2f, f.update(-1.2f)!!, 0f)
    }

    @Test
    fun reset_forgetsHeldHeight() {
        val f = h()
        f.update(-1.5f)
        f.reset()
        assertNull(f.value)
        assertEquals(-0.5f, f.update(-0.5f)!!, 0f)
    }
}
