package app.surrealar.ui.openinar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LevelHysteresisTest {

    private enum class L { A, B, C }

    @Test
    fun newLevel_commitsOnlyAfterHold() {
        val h = LevelHysteresis<L>(1_500)
        assertNull(h.update(L.A, 0))
        assertNull(h.update(L.A, 1_499))
        assertEquals(LevelHysteresis.Transition(null, L.A), h.update(L.A, 1_500))
        assertNull(h.update(L.A, 5_000))
    }

    @Test
    fun hoveringAtThreshold_neverLogs() {
        val h = LevelHysteresis<L>(1_500)
        h.update(L.A, 0); h.update(L.A, 2_000)          // A committed
        var t = 2_000L
        repeat(100) {
            t += 100
            assertNull(h.update(if (it % 2 == 0) L.B else L.A, t))
        }
        assertEquals(L.A, h.committed)
    }

    @Test
    fun candidateRestartsWhenItChanges() {
        val h = LevelHysteresis<L>(1_500)
        h.update(L.A, 0); h.update(L.A, 1_500)
        assertNull(h.update(L.B, 2_000))
        assertNull(h.update(L.C, 3_000))                // B never held 1.5 s
        assertNull(h.update(L.C, 4_499))
        assertEquals(LevelHysteresis.Transition(L.A, L.C), h.update(L.C, 4_500))
    }

    @Test
    fun reset_forgetsCommittedLevel() {
        val h = LevelHysteresis<L>(1_500)
        h.update(L.A, 0); h.update(L.A, 1_500)
        h.reset()
        assertNull(h.committed)
        assertNull(h.update(L.A, 2_000))
        assertEquals(LevelHysteresis.Transition(null, L.A), h.update(L.A, 3_500))
    }
}
