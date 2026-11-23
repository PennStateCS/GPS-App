package app.surrealar.util.diagnostics.incident

/**
 * One "Mark Problem" press: when it happened, where in the app, which session and the app state
 * ([context]). [description] is the tester's optional note: free text that may hold anything, so it
 * is kept only in the incident file and the share text the tester sees — never in the event log.
 */
data class IncidentMarker(
    val id: String,
    val epochMs: Long,
    val utcTime: String,
    val localTime: String,
    val zoneId: String,
    val sessionId: String?,
    val appVersion: String?,
    val screen: String?,
    val description: String?,
    val context: IncidentContext,
) {
    /**
     * Stable plain-text block for reports. The note is left out unless [includeDescription] (and
     * coordinate-like numbers in it are replaced even then); everything else is already PII-free.
     */
    fun render(includeDescription: Boolean = false): String = buildString {
        appendLine("Incident $id")
        appendLine("  utcTime: $utcTime")
        appendLine("  localTime: $localTime ${IncidentContext.safeText(zoneId) ?: IncidentContext.UNKNOWN}")
        appendLine("  session: ${IncidentContext.safeText(sessionId) ?: IncidentContext.UNKNOWN}")
        appendLine("  appVersion: ${IncidentContext.safeText(appVersion) ?: IncidentContext.UNKNOWN}")
        appendLine("  screen: ${IncidentContext.safeText(screen) ?: IncidentContext.UNKNOWN}")
        val note = description
        when {
            note == null -> appendLine("  description: none")
            includeDescription -> appendLine("  description: \"${IncidentContext.redactCoordinates(note).replace("\n", " / ")}\"")
            else -> appendLine("  description: provided (${note.length} chars, not included)")
        }
        context.renderLines().forEach { appendLine("  $it") }
    }

    companion object {
        /** Notes longer than this are cut. */
        const val MAX_DESCRIPTION = 500

        /** Trims, caps at [MAX_DESCRIPTION] and turns blank into null. */
        fun cleanDescription(raw: String?): String? =
            raw?.trim()?.take(MAX_DESCRIPTION)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
