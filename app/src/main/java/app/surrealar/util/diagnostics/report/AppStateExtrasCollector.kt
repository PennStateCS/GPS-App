package app.surrealar.util.diagnostics.report

import java.util.Locale

/** Extra lines for app-state-summary.txt: receiver, corrections, clock, heading and screen. */
object AppStateExtrasCollector {

    data class Extras(
        val receiverDevice: String?, val receiverProfile: String?, val receiverLink: String?, val corrections: String?,
        val clockOffsetMs: Long?, val headingSource: String?, val currentScreen: String?,
    )

    fun render(e: Extras): String = buildString {
        appendLine()
        appendLine("--- Receiver / corrections ---")
        appendLine("Receiver device        : ${e.receiverDevice ?: "unknown"}")
        appendLine("Receiver profile       : ${e.receiverProfile ?: "unknown"}")
        appendLine("Receiver link          : ${e.receiverLink ?: "unknown"}")
        appendLine("Corrections            : ${e.corrections ?: "unknown"}")
        appendLine()
        appendLine("--- Clock / heading / screen ---")
        appendLine("Clock offset dev−GNSS  : ${e.clockOffsetMs?.let { String.format(Locale.US, "%+.1f s", it / 1000.0) } ?: "unknown (no GNSS-timed fix yet)"}")
        appendLine("Heading source         : ${e.headingSource ?: "not used this session"}")
        appendLine("Current screen         : ${e.currentScreen ?: "unknown"}")
    }
}