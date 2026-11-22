package app.surrealar.ui.models.readiness

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import app.surrealar.R
import app.surrealar.ui.models.ModelDiagnostics

/** What the readiness checklist dialog shows; published by the model viewer. */
data class ReadinessUiState(
    val loading: Boolean = true,
    val report: ReadinessReport? = null,
    /** Dimensions / file size from the loaded asset, for the "Details" section. */
    val details: ModelDiagnostics? = null,
    /** False when the viewer was opened without a model id (no linked points can be checked). */
    val hasModel: Boolean = true,
    /** A fix is being written; fix buttons are disabled meanwhile. */
    val applying: Boolean = false,
)

/** Implemented by the screen that hosts the readiness checklist dialog. */
interface ReadinessHost {
    val readinessState: kotlinx.coroutines.flow.StateFlow<ReadinessUiState>
    fun onReadinessFix(check: ReadinessCheck)
}

/** Labels, colours and icons for verdicts and check statuses (pure, shared by the dialog and the cards). */
object ReadinessStyle {

    /** Long label for the dialog header chip. */
    fun verdictTitle(v: Verdict): String = when (v) {
        Verdict.READY -> "Ready for AR"
        Verdict.READY_WITH_WARNINGS -> "Ready with warnings"
        Verdict.NOT_READY -> "Not ready"
    }

    /** Short label for the model card chip. */
    fun verdictChip(v: Verdict): String = when (v) {
        Verdict.READY -> "Ready"
        Verdict.READY_WITH_WARNINGS -> "Warnings"
        Verdict.NOT_READY -> "Not ready"
    }

    @ColorRes
    fun verdictColor(v: Verdict): Int = when (v) {
        Verdict.READY -> R.color.app_success
        Verdict.READY_WITH_WARNINGS -> R.color.app_warning
        Verdict.NOT_READY -> R.color.app_error
    }

    @ColorRes
    fun statusColor(s: CheckStatus): Int = when (s) {
        CheckStatus.PASS -> R.color.app_success
        CheckStatus.INFO -> R.color.app_info
        CheckStatus.WARN -> R.color.app_warning
        CheckStatus.FAIL -> R.color.app_error
    }

    @DrawableRes
    fun statusIcon(s: CheckStatus): Int = when (s) {
        CheckStatus.PASS -> R.drawable.ic_readiness_pass
        CheckStatus.INFO -> R.drawable.ic_viewer_info
        CheckStatus.WARN -> R.drawable.ic_readiness_warn
        CheckStatus.FAIL -> R.drawable.ic_readiness_fail
    }

    /** Button text for a one-tap fix, or null when the check offers none. */
    fun fixButton(a: FixAction): String? = when (a) {
        FixAction.NONE -> null
        FixAction.USE_BOTTOM_CENTER -> "Use Bottom Center"
        FixAction.SET_VERTICAL_OFFSET -> "Set offset"
        FixAction.ROTATE_UP_AXIS -> "Rotate upright"
        FixAction.LINK_POINT -> "Link a point"
    }

    /** "Placement updated for ‘Point 5’" / "Placement updated for 3 points". */
    fun fixAppliedMessage(names: List<String>): String = when (names.size) {
        0 -> "Nothing to update"
        1 -> "Placement updated for ‘${names[0]}’"
        else -> "Placement updated for ${names.size} points"
    }

    /** One log line for the diagnostic report. Never contains coordinates. */
    fun logLine(fileName: String, report: ReadinessReport): String {
        val fails = report.checks.filter { it.status == CheckStatus.FAIL }.joinToString(",") { it.id }
        val warns = report.checks.filter { it.status == CheckStatus.WARN }.joinToString(",") { it.id }
        return "MODEL_READINESS model=$fileName verdict=${report.verdict} " +
            "fails=[$fails] warns=[$warns]"
    }
}
