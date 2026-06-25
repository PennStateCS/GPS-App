package com.example.surveyingapp.ui.settings

import com.example.surveyingapp.domain.model.SelfTest
import com.example.surveyingapp.domain.model.TestStatus

/**
 * Pure display mapping for receiver self-test results shown in the device-info panel of
 * [SettingsFragment].
 *
 * Extracted so the status icons, summary text, and color choices are unit-testable in one place.
 * Returns framework color **resource ids** (`android.R.color.*`) — no `Context`/views here; the
 * Fragment resolves them via `ContextCompat.getColor`. All glyphs/strings/colors are preserved
 * exactly from the previous in-fragment implementation; changing them is a UI/behavior change.
 */
object SelfTestDisplay {

    /** Status glyph shown next to each self-test. */
    fun statusIcon(status: TestStatus): String = when (status) {
        TestStatus.PASSED  -> "✓"
        TestStatus.FAILED  -> "✗"
        TestStatus.WARNING -> "⚠"
        TestStatus.UNKNOWN -> "?"
    }

    /** Color resource id for a single self-test's status glyph. */
    fun statusColorRes(status: TestStatus): Int = when (status) {
        TestStatus.PASSED  -> android.R.color.holo_green_dark
        TestStatus.FAILED  -> android.R.color.holo_red_dark
        TestStatus.WARNING -> android.R.color.holo_orange_dark
        TestStatus.UNKNOWN -> android.R.color.darker_gray
    }

    /** Summary line, e.g. "7/9 passed". */
    fun summaryText(tests: List<SelfTest>): String {
        val passed = tests.count { it.status == TestStatus.PASSED }
        return "$passed/${tests.size} passed"
    }

    /** Color resource id for the summary line: red if any failed, amber if any warning, else green. */
    fun summaryColorRes(tests: List<SelfTest>): Int {
        val failed = tests.count { it.status == TestStatus.FAILED }
        val warning = tests.count { it.status == TestStatus.WARNING }
        return when {
            failed > 0  -> android.R.color.holo_red_dark
            warning > 0 -> android.R.color.holo_orange_dark
            else        -> android.R.color.holo_green_dark
        }
    }
}
