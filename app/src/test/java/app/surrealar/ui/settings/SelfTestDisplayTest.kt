package app.surrealar.ui.settings

import app.surrealar.domain.model.SelfTest
import app.surrealar.domain.model.TestStatus
import org.junit.Assert.assertEquals
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
    fun statusColorRes_mapsEachStatus() {
        assertEquals(android.R.color.holo_green_dark, SelfTestDisplay.statusColorRes(TestStatus.PASSED))
        assertEquals(android.R.color.holo_red_dark, SelfTestDisplay.statusColorRes(TestStatus.FAILED))
        assertEquals(android.R.color.holo_orange_dark, SelfTestDisplay.statusColorRes(TestStatus.WARNING))
        assertEquals(android.R.color.darker_gray, SelfTestDisplay.statusColorRes(TestStatus.UNKNOWN))
    }

    @Test
    fun summaryText_countsPassedOverTotal() {
        val tests = listOf(t(TestStatus.PASSED), t(TestStatus.PASSED), t(TestStatus.FAILED))
        assertEquals("2/3 passed", SelfTestDisplay.summaryText(tests))
    }

    @Test
    fun summaryColorRes_redWhenAnyFailed() {
        val tests = listOf(t(TestStatus.PASSED), t(TestStatus.WARNING), t(TestStatus.FAILED))
        assertEquals(android.R.color.holo_red_dark, SelfTestDisplay.summaryColorRes(tests))
    }

    @Test
    fun summaryColorRes_amberWhenWarningNoFail() {
        val tests = listOf(t(TestStatus.PASSED), t(TestStatus.WARNING))
        assertEquals(android.R.color.holo_orange_dark, SelfTestDisplay.summaryColorRes(tests))
    }

    @Test
    fun summaryColorRes_greenWhenAllPassed() {
        val tests = listOf(t(TestStatus.PASSED), t(TestStatus.PASSED))
        assertEquals(android.R.color.holo_green_dark, SelfTestDisplay.summaryColorRes(tests))
    }
}
