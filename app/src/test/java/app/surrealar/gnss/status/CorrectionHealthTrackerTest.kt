package app.surrealar.gnss.status

import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** Correction-health hysteresis for the compact toolbar (aging = capture limit 10 s, stale = 15 s, margin 2 s, hold 3 s). */
class CorrectionHealthTrackerTest {

    private val t = CorrectionHealthTracker(agingS = 10.0)
    private fun at(ms: Long, age: Double?, rtk: RtkStatus = RtkStatus.FIX) = t.update(true, age, rtk, ms)

    @Test fun `quiet below the aging limit`() {
        assertEquals(CorrectionHealth.OK, at(0, 1.2))
        assertEquals(CorrectionHealth.OK, at(250, 10.0))
    }

    @Test fun `warnings are entered at the threshold immediately`() {
        assertEquals(CorrectionHealth.AGING, at(0, 10.1))
        assertEquals(CorrectionHealth.STALE, at(250, 15.1))
        // straight from OK to STALE too
        t.reset()
        assertEquals(CorrectionHealth.STALE, at(500, 30.0))
    }

    @Test fun `no flicker around the threshold`() {
        assertEquals(CorrectionHealth.AGING, at(0, 10.2))
        // hovers 9.8 / 10.2 / 9.9 …: stays AGING (not below 10 − 2 s)
        var ms = 0L
        for (age in listOf(9.8, 10.2, 9.9, 10.1, 9.5, 8.5)) { ms += 250; assertEquals("age $age", CorrectionHealth.AGING, at(ms, age)) }
    }

    @Test fun `a warning clears only below threshold minus margin, held for 3 s`() {
        at(0, 16.0)
        assertEquals(CorrectionHealth.STALE, at(1_000, 13.5))  // below 15 but not below 13
        assertEquals(CorrectionHealth.STALE, at(2_000, 12.0))  // clear of the margin: hold starts
        assertEquals(CorrectionHealth.STALE, at(4_000, 12.0))  // 2 s held
        assertEquals("steps down to AGING (12 s > 10 − 2 s)", CorrectionHealth.AGING, at(5_000, 12.0))
        assertEquals(CorrectionHealth.AGING, at(6_000, 1.0))
        assertEquals(CorrectionHealth.AGING, at(8_000, 1.0))
        assertEquals(CorrectionHealth.OK, at(9_000, 1.0))
    }

    @Test fun `a relapse during the hold restarts it`() {
        at(0, 11.0)
        at(1_000, 1.0)                                     // hold starts
        assertEquals(CorrectionHealth.AGING, at(2_000, 9.0)) // back within the margin: hold cancelled
        at(3_000, 1.0)                                     // hold restarts
        assertEquals(CorrectionHealth.AGING, at(5_000, 1.0))
        assertEquals(CorrectionHealth.OK, at(6_000, 1.0))
    }

    @Test fun `unavailable only after corrections were seen and the solution has none`() {
        assertEquals("a receiver never used with corrections is not nagged", CorrectionHealth.OK, at(0, null, RtkStatus.SINGLE))
        assertEquals(CorrectionHealth.OK, at(250, 1.0, RtkStatus.FIX))
        assertEquals("RTK without an age field is fine", CorrectionHealth.OK, at(500, null, RtkStatus.FIX))
        assertEquals(CorrectionHealth.UNAVAILABLE, at(750, null, RtkStatus.SINGLE))
        assertEquals(CorrectionHealth.UNAVAILABLE, at(1_000, 1.0, RtkStatus.FIX))
        assertEquals(CorrectionHealth.OK, at(4_000, 1.0, RtkStatus.FIX))
    }

    @Test fun `not applicable clears the warning but keeps seen-corrections (review M3), reset forgets`() {
        at(0, 20.0)
        assertEquals(CorrectionHealth.OK, t.update(false, 20.0, RtkStatus.FIX, 250))
        // After a fix dropout / reconnect blip, losing corrections still warns.
        assertEquals(CorrectionHealth.UNAVAILABLE, at(500, null, RtkStatus.SINGLE))
        // A source / receiver change (reset) forgets: a receiver never seen with corrections is not nagged.
        t.reset()
        assertEquals(CorrectionHealth.OK, at(750, null, RtkStatus.SINGLE))
    }

    @Test fun `a short custom limit (1-2 s) can still clear aging (review M1)`() {
        for (limit in listOf(1.0, 2.0)) {
            val short = CorrectionHealthTracker(agingS = limit)
            assertEquals(CorrectionHealth.AGING, short.update(true, limit + 0.5, RtkStatus.FIX, 0))
            short.update(true, 0.2, RtkStatus.FIX, 1_000)
            assertEquals("limit $limit s clears after the hold", CorrectionHealth.OK, short.update(true, 0.2, RtkStatus.FIX, 5_000))
        }
    }

    @Test fun `aging threshold is capped at stale and invalid ages are not reported`() {
        val capped = CorrectionHealthTracker(agingS = 30.0)
        assertEquals(15.0, capped.agingS, 0.0)
        assertEquals(CorrectionHealth.STALE, capped.update(true, 16.0, RtkStatus.FIX, 0))
        assertEquals(CorrectionHealth.OK, at(0, Double.NaN))
        assertEquals(CorrectionHealth.OK, at(0, -1.0))
    }

    @Test fun `a stricter capture preset (5 s) ages earlier`() {
        val strict = CorrectionHealthTracker(agingS = 5.0)
        assertEquals(CorrectionHealth.AGING, strict.update(true, 5.5, RtkStatus.FIX, 0))
    }

    @Test fun `recovering is true only while a warning is held by hysteresis after the raw age improved`() {
        val tr = CorrectionHealthTracker(agingS = 10.0)
        assertEquals(CorrectionHealth.STALE, tr.update(true, 20.0, RtkStatus.FIX, 0))
        assertFalse("raw stale: not recovering", tr.recovering)
        assertEquals(CorrectionHealth.STALE, tr.update(true, 1.0, RtkStatus.FIX, 250))
        assertTrue("held stale with a good raw age: recovering", tr.recovering)
        assertEquals(CorrectionHealth.OK, tr.update(true, 1.0, RtkStatus.FIX, 4_000))
        assertFalse("released", tr.recovering)
    }

    @Test fun `rapid correction changes - worse shows at once, better waits, recovering follows`() {
        val tr = CorrectionHealthTracker(agingS = 10.0)
        tr.update(true, 1.0, RtkStatus.FIX, 0)
        assertEquals(CorrectionHealth.AGING, tr.update(true, 11.0, RtkStatus.FIX, 100))
        assertEquals(CorrectionHealth.STALE, tr.update(true, 16.0, RtkStatus.FIX, 200))
        assertEquals(CorrectionHealth.STALE, tr.update(true, 2.0, RtkStatus.FIX, 300)); assertTrue(tr.recovering)
        assertEquals("a relapse resets the hold", CorrectionHealth.STALE, tr.update(true, 17.0, RtkStatus.FIX, 400)); assertFalse(tr.recovering)
        assertEquals(CorrectionHealth.STALE, tr.update(true, 2.0, RtkStatus.FIX, 3_000))
        assertEquals(CorrectionHealth.OK, tr.update(true, 2.0, RtkStatus.FIX, 6_100)); assertFalse(tr.recovering)
    }

    @Test fun `receiver switch (reset) and disconnect (not applicable) clear recovering`() {
        val tr = CorrectionHealthTracker(agingS = 10.0)
        tr.update(true, 20.0, RtkStatus.FIX, 0); tr.update(true, 1.0, RtkStatus.FIX, 100)
        assertTrue(tr.recovering)
        tr.update(false, null, RtkStatus.NONE, 200)
        assertFalse("disconnect", tr.recovering)
        tr.update(true, 20.0, RtkStatus.FIX, 300); tr.update(true, 1.0, RtkStatus.FIX, 400)
        tr.reset()
        assertFalse("switch", tr.recovering)
        assertEquals(CorrectionHealth.OK, tr.current)
    }
}
