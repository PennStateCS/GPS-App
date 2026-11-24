package app.surrealar.util.diagnostics.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadmeCollectorTest {
    @Test fun `golden readme`() {
        val r = ReadmeCollector.render("7K3M-QX2D", logsReduced = false)
        val lines = r.lines()
        assertEquals("SurReal AR diagnostic report  (Report ID: 7K3M-QX2D)", lines[0])
        assertTrue(lines.any { it.trim().startsWith("timeline.txt") && it.contains("last 2 hours") })
        assertTrue(r.contains("2026-11-21T14:03:07.412-05:00 W RECEIVER [main] TCP: no data for 10000ms"))
        assertEquals("every file has one line", ReadmeCollector.FILES.size, lines.count { l -> ReadmeCollector.FILES.any { l.trim().startsWith(it.first + " ") } })
        assertFalse(r.contains("larger than the size budget"))
        assertTrue(r.contains("Reading order: diagnostic-summary.txt → incidents.txt → timeline.txt → export-health.txt → the rest."))
        // The file list starts in reading order.
        assertEquals(listOf("README.txt", "diagnostic-summary.txt", "incidents.txt", "timeline.txt", "export-health.txt"),
            ReadmeCollector.FILES.take(5).map { it.first })
    }

    @Test fun `log reduction is explained`() {
        val r = ReadmeCollector.render("X", logsReduced = true)
        assertTrue(r.contains("larger than the size budget"))
        assertTrue(r.contains("lines omitted"))
    }
}