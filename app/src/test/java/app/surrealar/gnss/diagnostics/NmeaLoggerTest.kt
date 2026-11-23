package app.surrealar.gnss.diagnostics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [NmeaLogger]'s ring buffer (capacity 2000, oldest dropped, newest-first reads), the error
 * entries and the once-per-second stats. Stats are wall-clock based; instead of sleeping, the
 * private `lastStatsUpdate` is moved 2 s into the past so the next line publishes a snapshot.
 */
class NmeaLoggerTest {

    private fun ageStatsWindow(logger: NmeaLogger, byMs: Long = 2_000L) {
        val f = NmeaLogger::class.java.getDeclaredField("lastStatsUpdate")
        f.isAccessible = true
        f.setLong(logger, System.currentTimeMillis() - byMs)
    }

    @Test fun empty_logger_hasNoLines_andZeroStats() = runBlocking {
        val l = NmeaLogger()
        assertTrue(l.getRecentLines().isEmpty())
        assertTrue(l.getErrorLines().isEmpty())
        assertEquals(NmeaLogStats(), l.stats.value)
    }

    @Test fun recentLines_areNewestFirst_andLimited() = runBlocking {
        val l = NmeaLogger()
        l.logNmeaLine("a"); l.logNmeaLine("b"); l.logNmeaLine("c")
        assertEquals(listOf("c", "b", "a"), l.getRecentLines().map { it.nmeaLine })
        assertEquals(listOf("c", "b"), l.getRecentLines(2).map { it.nmeaLine })
        assertFalse(l.getRecentLines().any { it.isError })
    }

    @Test fun parseErrors_areTaggedInline_andFiltered() = runBlocking {
        val l = NmeaLogger()
        l.logNmeaLine("ok")
        l.logParseError("\$GPGGA,bad", "checksum")
        l.logParseError("\$GPRMC,bad")
        val errors = l.getErrorLines()
        assertEquals(listOf("\$GPGGA,bad [ERROR: checksum]", "\$GPRMC,bad [PARSE ERROR]"), errors.map { it.nmeaLine })
        assertTrue(errors.all { it.isError })
        assertEquals(3, l.getRecentLines().size)
    }

    @Test fun buffer_dropsOldest_pastCapacity() = runBlocking {
        val l = NmeaLogger()
        repeat(2_005) { l.logNmeaLine("L$it") }
        val all = l.getRecentLines()
        assertEquals(2_000, all.size)
        assertEquals("L2004", all.first().nmeaLine)
        assertEquals("L5", all.last().nmeaLine)
    }

    @Test fun stats_arePublishedOnceTheWindowElapsed() = runBlocking {
        val l = NmeaLogger()
        l.logNmeaLine("a")
        assertEquals("inside the first second nothing is published", NmeaLogStats(), l.stats.value)
        l.logParseError("x", null)
        ageStatsWindow(l)
        l.logNmeaLine("b")
        val s = l.stats.value
        assertEquals(2L, s.totalLines)
        assertEquals(1L, s.parseErrors)
        assertEquals(3, s.bufferSize)
        // 3 lines over ~2 s (a little more, as wall time moved on).
        assertTrue("rate was ${s.linesPerSecond}", s.linesPerSecond > 0.0 && s.linesPerSecond <= 1.5)
    }

    @Test fun stats_errorPathAlsoPublishes() = runBlocking {
        val l = NmeaLogger()
        ageStatsWindow(l)
        l.logParseError("x", "e")
        assertEquals(1L, l.stats.value.parseErrors)
        assertEquals(0L, l.stats.value.totalLines)
        assertEquals(1, l.stats.value.bufferSize)
    }

    @Test fun clear_resetsBufferAndStats() = runBlocking {
        val l = NmeaLogger()
        repeat(3) { l.logNmeaLine("L$it") }
        ageStatsWindow(l)
        l.logParseError("x")
        assertTrue(l.stats.value.bufferSize > 0)
        l.clear()
        assertTrue(l.getRecentLines().isEmpty())
        assertEquals(NmeaLogStats(), l.stats.value)
        l.logNmeaLine("after")
        assertEquals(listOf("after"), l.getRecentLines().map { it.nmeaLine })
    }

    @Test fun clear_afterWrapAround_restartsCleanly() = runBlocking {
        val l = NmeaLogger()
        repeat(2_001) { l.logNmeaLine("L$it") }
        l.clear()
        l.logNmeaLine("x"); l.logNmeaLine("y")
        assertEquals(listOf("y", "x"), l.getRecentLines().map { it.nmeaLine })
    }

    @Test fun entries_areValueObjects() {
        val e = NmeaLogEntry(1L, "l")
        assertFalse(e.isError)
        assertEquals(e.copy(isError = true), NmeaLogEntry(1L, "l", true))
    }
}
