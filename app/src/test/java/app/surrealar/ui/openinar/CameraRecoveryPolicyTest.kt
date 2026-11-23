package app.surrealar.ui.openinar

import app.surrealar.ui.openinar.CameraRecoveryPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraRecoveryPolicyTest {

    @Test
    fun backsOffThenGivesUp() {
        val p = CameraRecoveryPolicy(listOf(1_000L, 2_000L, 4_000L), quietResetMs = 60_000L)
        assertEquals(Decision.Retry(1, 3, 1_000L), p.onCameraUnavailable(0L))
        assertEquals(Decision.Retry(2, 3, 2_000L), p.onCameraUnavailable(1_100L))
        assertEquals(Decision.Retry(3, 3, 4_000L), p.onCameraUnavailable(3_200L))
        assertEquals(Decision.GiveUp, p.onCameraUnavailable(7_300L))
        assertEquals(Decision.GiveUp, p.onCameraUnavailable(8_000L))
    }

    @Test
    fun resetGivesFreshBudget() {
        val p = CameraRecoveryPolicy(listOf(1_000L), quietResetMs = 60_000L)
        p.onCameraUnavailable(0L)
        assertEquals(Decision.GiveUp, p.onCameraUnavailable(1_000L))
        p.reset()
        assertEquals(Decision.Retry(1, 1, 1_000L), p.onCameraUnavailable(2_000L))
    }

    @Test
    fun longQuietPeriod_startsNewEpisode() {
        val p = CameraRecoveryPolicy(listOf(1_000L, 2_000L), quietResetMs = 60_000L)
        p.onCameraUnavailable(0L)
        p.onCameraUnavailable(1_000L)
        assertEquals(Decision.GiveUp, p.onCameraUnavailable(3_000L))
        // Recovered (or the user waited); a loss a minute later starts from the shortest delay.
        assertEquals(Decision.Retry(1, 2, 1_000L), p.onCameraUnavailable(63_000L))
    }

    @Test
    fun defaultsAreBounded() {
        val p = CameraRecoveryPolicy()
        var t = 0L
        var retries = 0
        while (true) {
            val d = p.onCameraUnavailable(t)
            if (d !is Decision.Retry) break
            retries++
            t += d.delayMs
        }
        assertEquals(CameraRecoveryPolicy.DEFAULT_BACKOFF_MS.size, retries)
        assertEquals(31_000L, t)   // 1+2+4+8+16 s before giving up
    }
}
