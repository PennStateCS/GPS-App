package app.surrealar.util.diagnostics.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadmeCollectorTest {
    @Test fun `golden readme`() {
        val r = ReadmeCollector.render("7K3M-QX2D", logsTruncatedTo24h = false)
        val lines = r.lines()
        assertEquals("SurReal AR diagnostic report  (Report ID: 7K3M-QX2D)", lines[0])
        assertTrue(lines.any { it.trim().startsWith("timeline.txt") && it.contains("last 2 hours") })
        assertTrue(r.contains("2026-11-21T14:03:07.412-05:00 W RECEIVER [main] TCP: no data for 10000ms"))
        assertEquals("every file has one line", ReadmeCollector.FILES.size, lines.count { l -> ReadmeCollector.FILES.any { l.trim().startsWith(it.first + " ") } })
        assertFalse(r.contains("last 24 hours of each log"))
    }

    @Test fun `truncation is explained`() {
        assertTrue(ReadmeCollector.render("X", logsTruncatedTo24h = true).contains("only the last 24 hours of each log file are included"))
    }
}