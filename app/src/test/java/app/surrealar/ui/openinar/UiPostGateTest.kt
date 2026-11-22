package app.surrealar.ui.openinar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiPostGateTest {

    @Test
    fun firstCall_isAllowed() {
        assertTrue(UiPostGate(200).tryAcquire(0L))
    }

    @Test
    fun blocksWhilePreviousPostIsPending_evenAfterInterval() {
        val gate = UiPostGate(200)
        assertTrue(gate.tryAcquire(1_000))
        assertFalse(gate.tryAcquire(1_500))
        gate.release()
        assertTrue(gate.tryAcquire(1_501))
    }

    @Test
    fun blocksWithinInterval_evenAfterRelease() {
        val gate = UiPostGate(200)
        assertTrue(gate.tryAcquire(1_000))
        gate.release()
        assertFalse(gate.tryAcquire(1_016))
        assertFalse(gate.tryAcquire(1_199))
        assertTrue(gate.tryAcquire(1_200))
    }

    @Test
    fun sixtyFpsForOneSecond_allowsAboutFivePosts() {
        val gate = UiPostGate(200)
        var posts = 0
        var t = 0.0
        while (t < 1_000.0) {
            if (gate.tryAcquire(t.toLong())) { posts++; gate.release() }
            t += 1000.0 / 60.0
        }
        assertTrue("posts=$posts", posts in 5..6)
    }

    @Test
    fun reset_clearsPendingAndInterval() {
        val gate = UiPostGate(200)
        assertTrue(gate.tryAcquire(1_000))
        gate.reset()
        assertTrue(gate.tryAcquire(1_001))
    }
}
