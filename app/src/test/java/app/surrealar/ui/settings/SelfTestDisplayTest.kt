package app.surrealar.ui.settings

import app.surrealar.domain.model.SelfTest
import app.surrealar.domain.model.TestStatus
import org.junit.Assert.assertEquals
import app.surrealar.ui.theme.StatusTone
import org.junit.Test

class SelfTestDisplayTest {

    private fun t(status: TestStatus) = SelfTest(name = "t", status = status)

    @Test
    fun statusIcon_mapsEachStatus() {
        assertEquals("✓", SelfTestDisplay.statusIcon(TestStatus.PASSED))
        assertEquals("✗", SelfTestDisplay.statusIcon(TestStatus.FAILED))
        assertEquals("⚠", SelfTestDisplay.statusIcon(TestStatus.WARNING))
        assertEquals("?", SelfTestDisplay.statusIcon(TestStatus.UNKNOWN))
    }

    @Test
    fun statusTone_mapsEachStatus() {
        assertEquals(StatusTone.SUCCESS, SelfTestDisplay.statusTone(TestStatus.PASSED))
        assertEquals(StatusTone.ERROR, SelfTestDisplay.statusTone(TestStatus.FAILED))
        assertEquals(StatusTone.WARNING, SelfTestDisplay.statusTone(TestStatus.WARNING))
        assertEquals(StatusTone.NEUTRAL, SelfTestDisplay.statusTone(TestStatus.UNKNOWN))
    }

    @Test
    fun summaryText_countsPassedOverTotal() {
        val tests = listOf(t(TestStatus.PASSED), t(TestStatus.PASSED), t(TestStatus.FAILED))
        assertEquals("2/3 passed", SelfTestDisplay.summaryText(tests))
    }

    @Test
    fun summaryTone_errorWhenAnyFailed() {
        val tests = listOf(t(TestStatus.PASSED), t(TestStatus.WARNING), t(TestStatus.FAILED))
        assertEquals(StatusTone.ERROR, SelfTestDisplay.summaryTone(tests))
    }

    @Test
    fun summaryTone_warningWhenWarningNoFail() {
        val tests = listOf(t(TestStatus.PASSED), t(TestStatus.WARNING))
        assertEquals(StatusTone.WARNING, SelfTestDisplay.summaryTone(tests))
    }

    @Test
    fun summaryTone_successWhenAllPassed() {
        val tests = listOf(t(TestStatus.PASSED), t(TestStatus.PASSED))
        assertEquals(StatusTone.SUCCESS, SelfTestDisplay.summaryTone(tests))
    }
}
