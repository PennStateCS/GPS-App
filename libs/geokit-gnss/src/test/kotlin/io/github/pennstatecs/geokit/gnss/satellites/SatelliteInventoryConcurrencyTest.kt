package io.github.pennstatecs.geokit.gnss.satellites

import io.github.pennstatecs.geokit.gnss.bus.adapters.GsvEntry
import io.github.pennstatecs.geokit.gnss.bus.adapters.GsvMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * A reconnect can reset the inventory while the previous GSV collector is still consuming: that
 * must not throw (ConcurrentModificationException on the app scope) or corrupt the counts.
 */
class SatelliteInventoryConcurrencyTest {

    private fun gsv(talker: String, from: Int) = GsvMessage(
        talker, (from until from + 24).map { GsvEntry(it, 30 + it % 50, (it * 7) % 360, 35.0 + it % 10, it % 3 == 0) }
    )

    @Test fun `concurrent consume and reset from two threads does not throw`() {
        // Old satellites must age out on every consume, so eviction (removeIf) races with reset.
        var t = 0.0
        val inv = SatelliteInventory(evictionSeconds = 0.5, nowSeconds = { synchronized(this) { t += 0.1; t } })
        val errors = CopyOnWriteArrayList<Throwable>()
        val start = CountDownLatch(1)
        val consumers = (0 until 2).map { n ->
            thread {
                start.await()
                try { repeat(20_000) { i -> inv.consume(gsv(if (n == 0) "GP" else "GL", (i % 40) * 3)) } }
                catch (e: Throwable) { errors += e }
            }
        }
        val resetter = thread {
            start.await()
            try { repeat(20_000) { inv.reset() } } catch (e: Throwable) { errors += e }
        }
        start.countDown()
        (consumers + resetter).forEach { it.join(60_000) }
        assertTrue("thrown: ${errors.firstOrNull()}", errors.isEmpty())

        // Still consistent afterwards.
        inv.reset()
        val snap = inv.consume(gsv("GP", 1))
        assertEquals(24, snap.totalVisible)
    }
}
