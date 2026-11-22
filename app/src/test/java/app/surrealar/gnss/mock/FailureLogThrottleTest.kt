package app.surrealar.gnss.mock

import app.surrealar.gnss.mock.FailureLogThrottle.Mode.BRIEF
import app.surrealar.gnss.mock.FailureLogThrottle.Mode.FULL
import app.surrealar.gnss.mock.FailureLogThrottle.Mode.NONE
import org.junit.Assert.assertEquals
import org.junit.Test

class FailureLogThrottleTest {

    private var now = 0L
    private val throttle = FailureLogThrottle(60_000L) { now }

    @Test
    fun `first failure is full, then brief once per minute`() {
        val modes = (0L..130_000L step 100L).map { t -> now = t; throttle.decide("not_permitted") }
        assertEquals(1, modes.count { it == FULL })
        assertEquals(FULL, modes.first())
        assertEquals(2, modes.count { it == BRIEF })   // at 60 s and 120 s
        assertEquals(modes.size - 3, modes.count { it == NONE })
    }

    @Test
    fun `a new kind or a success in between logs in full again`() {
        assertEquals(FULL, throttle.decide("not_permitted"))
        now = 1_000L
        assertEquals(NONE, throttle.decide("not_permitted"))
        assertEquals(FULL, throttle.decide("provider_error"))
        throttle.reset()
        now = 2_000L
        assertEquals(FULL, throttle.decide("provider_error"))
    }
}
