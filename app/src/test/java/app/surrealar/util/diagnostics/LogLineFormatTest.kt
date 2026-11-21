package app.surrealar.util.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class LogLineFormatTest {

    private val ny = ZoneId.of("America/New_York")

    @Test fun `formats the documented example`() {
        val ms = java.time.OffsetDateTime.parse("2026-11-21T14:03:07.412-05:00").toInstant().toEpochMilli()
        assertEquals("2026-11-21T14:03:07.412-05:00 W RECEIVER [main] TCP: no data for 10000ms",
            LogLineFormat.format(ms, 'W', "RECEIVER", "main", "TCP: no data for 10000ms", ny))
    }

    @Test fun `parses back what it writes, including threads with spaces`() {
        val line = LogLineFormat.format(1_700_000_000_123L, 'E', "ERROR", "DefaultDispatcher-worker 3", "boom [x]", ny)
        val p = LogLineFormat.parse(line)!!
        assertEquals(1_700_000_000_123L, p.epochMs)
        assertEquals('E', p.level); assertEquals("ERROR", p.tag)
        assertEquals("DefaultDispatcher-worker 3", p.thread); assertEquals("boom [x]", p.message)
    }

    @Test fun `stack trace lines and the old format are not log lines`() {
        assertNull(LogLineFormat.parse("\tat app.surrealar.Foo.bar(Foo.kt:12)"))
        assertNull(LogLineFormat.parse("2026-11-21 14:03:07.412 INFO  App - Started"))
    }
}