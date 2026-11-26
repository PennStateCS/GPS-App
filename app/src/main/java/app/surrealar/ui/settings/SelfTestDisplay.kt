package app.surrealar.ui.settings

import app.surrealar.domain.model.SelfTest
import app.surrealar.domain.model.TestStatus
import app.surrealar.ui.theme.StatusTone

/**
 * Pure display mapping for receiver self-test results shown in the device-info panel of
 * [app.surrealar.ui.settings.categories.ReceiverSettingsController].
 *
 * Extracted so the status icons, summary text, and color choices are unit-testable in one place.
 * Returns a semantic [StatusTone] — no `Context`/views here; the view resolves the theme colour with
 * `tone.foreground(view)`. Glyphs and strings are preserved exactly from the previous in-fragment
 * implementation. (The old framework holo colours were 2.4–3.4:1 on white and ignored dark mode.)
 */
object SelfTestDisplay {

    /** Status glyph shown next to each self-test. */
    fun statusIcon(status: TestStatus): String = when (status) {
        TestStatus.PASSED  -> "✓"
        TestStatus.FAILED  -> "✗"
        TestStatus.WARNING -> "⚠"
        TestStatus.UNKNOWN -> "?"
    }

    /** Semantic tone for a single self-test's status glyph. */
    fun statusTone(status: TestStatus): StatusTone = when (status) {
        TestStatus.PASSED  -> StatusTone.SUCCESS
        TestStatus.FAILED  -> StatusTone.ERROR
        TestStatus.WARNING -> StatusTone.WARNING
        TestStatus.UNKNOWN -> StatusTone.NEUTRAL
    }

    /** Summary line, e.g. "7/9 passed". */
    fun summaryText(tests: List<SelfTest>): String {
        val passed = tests.count { it.status == TestStatus.PASSED }
        return "$passed/${tests.size} passed"
    }

    /** Tone for the summary line: error if any failed, warning if any warning, else success. */
    fun summaryTone(tests: List<SelfTest>): StatusTone {
        val failed = tests.count { it.status == TestStatus.FAILED }
        val warning = tests.count { it.status == TestStatus.WARNING }
        return when {
            failed > 0  -> StatusTone.ERROR
            warning > 0 -> StatusTone.WARNING
            else        -> StatusTone.SUCCESS
        }
    }
}
