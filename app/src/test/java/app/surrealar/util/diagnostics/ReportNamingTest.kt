package app.surrealar.util.diagnostics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Random

class ReportNamingTest {
    private val ny = ZoneId.of("America/New_York")
    private val ms = java.time.OffsetDateTime.parse("2026-11-21T14:03:07-05:00").toInstant().toEpochMilli()

    @Test fun `subject has device, version and local time`() {
        assertEquals("SurReal AR report – SM-X940 – v1.4.2 (142) – 2026-11-21 14:03",
            ReportNaming.subject("SM-X940", "1.4.2", 142, ms, ny))
    }

    @Test fun `body asks the three questions and ends with the report id`() {
        val b = ReportNaming.body("7K3M-QX2D")
        listOf("What were you doing?", "What did you expect?", "What happened instead?").forEach { assertTrue(b.contains(it)) }
        assertTrue(b.trimEnd().endsWith("Report ID: 7K3M-QX2D"))
    }

    @Test fun `blank email leaves To empty`() {
        assertArrayEquals(emptyArray<String>(), ReportNaming.recipients(""))
        assertArrayEquals(emptyArray<String>(), ReportNaming.recipients("  "))
        assertArrayEquals(arrayOf("qa@example.org"), ReportNaming.recipients("qa@example.org"))
    }

    @Test fun `file name is safe and dated`() {
        assertEquals("surreal-ar-sm-x940-v1.4.2-debug-20261121-1403.zip", ReportNaming.fileName("SM X940", "1.4.2 debug", ms, ny))
    }

    @Test fun `report ids are short and differ`() {
        val a = ReportNaming.newReportId(Random(1)); val b = ReportNaming.newReportId(Random(2))
        assertTrue(a, Regex("[0-9A-Z]{4}-[0-9A-Z]{4}").matches(a)); assertNotEquals(a, b)
    }
}