package app.surrealar.util.diagnostics

/**
 * Privacy-safe snapshot of the map page's runtime UI/display state for the diagnostic report.
 *
 * Holds ONLY non-sensitive values — counts, mode names, booleans, zoom, error class/message,
 * approximate distance buckets. Never exact live/target coordinates, NMEA, keys, or notes. The map
 * fragment pushes updates here on low-frequency state transitions; [MapDiagnosticCollector] renders
 * it. Pure data + formatting so it is unit-testable.
 */
data class MapDiagSnapshot(
    val mapReady: Boolean = false,
    val mapLoaded: Boolean = false,
    val mapLoadTimeout: Boolean = false,
    val lastError: String? = null,
    val mapType: String = "Normal",
    val mapToolsOpen: Boolean = false,
    val gridMode: String = "Off",
    val gridSpacing: String? = null,
    val pointLabelMode: String = "Off",
    val currentLocationVisible: Boolean = true,
    val headingAvailable: Boolean = false,
    val accuracyCircleVisible: Boolean = false,
    val markersTotal: Int = 0,
    val markersVisible: Int = 0,
    val selectedActive: Boolean = false,
    val stakeoutActive: Boolean = false,
    val guidanceActive: Boolean = false,
    val cameraRestored: Boolean = false,
    val lastGridSummary: String? = null,
    val lastLabelsSummary: String? = null,
) {
    fun format(): String {
        val markersHidden = (markersTotal - markersVisible).coerceAtLeast(0)
        return buildString {
            appendLine("Map ready               : ${yn(mapReady)}")
            appendLine("Map loaded (tiles)      : ${yn(mapLoaded)}")
            appendLine("Map load timeout        : ${yn(mapLoadTimeout)}")
            appendLine("Last map error          : ${lastError ?: "none"}")
            appendLine("Map type                : $mapType")
            appendLine("Map Tools open          : ${yn(mapToolsOpen)}")
            appendLine("Grid mode               : $gridMode${gridSpacing?.let { " ($it)" } ?: ""}")
            appendLine("Point label mode        : $pointLabelMode")
            appendLine("Current-location overlay: ${yn(currentLocationVisible)}")
            appendLine("Heading available       : ${yn(headingAvailable)}")
            appendLine("Accuracy circle         : ${yn(accuracyCircleVisible)}")
            appendLine("Markers total/vis/hidden: $markersTotal / $markersVisible / $markersHidden")
            appendLine("Selected point active   : ${yn(selectedActive)}")
            appendLine("Stakeout active         : ${yn(stakeoutActive)}")
            appendLine("Stakeout guidance active: ${yn(guidanceActive)}")
            appendLine("Camera restored         : ${yn(cameraRestored)}")
            appendLine("Last grid redraw        : ${lastGridSummary ?: "none"}")
            append("Last labels update      : ${lastLabelsSummary ?: "none"}")
        }
    }

    private fun yn(b: Boolean) = if (b) "yes" else "no"
}

/**
 * Process-wide holder for the latest map-rendering [MapDiagSnapshot], updated as the map runs and read
 * by diagnostics reports. [update] applies a transform to the current snapshot; access is `@Volatile`
 * for cross-thread reads. This is diagnostics state only and does not drive map behavior.
 */
object MapRuntimeDiagnostics {

    @Volatile
    var snapshot: MapDiagSnapshot = MapDiagSnapshot()
        private set

    fun update(transform: (MapDiagSnapshot) -> MapDiagSnapshot) { snapshot = transform(snapshot) }

    /** Reset to defaults (e.g. when the map screen is destroyed). */
    fun clear() { snapshot = MapDiagSnapshot() }

    /** Summary line for a grid redraw — no coordinates. e.g. "mode=Auto spacing=10 m lines=42 zoom=19.2". */
    fun gridSummary(mode: String, spacing: String?, lines: Int, zoom: Float): String =
        "mode=$mode spacing=${spacing ?: "-"} lines=$lines zoom=${"%.1f".format(zoom)}"

    /** Summary line for a labels update — no coordinates. e.g. "mode=Distance labeled=12 skipped=6 reason=noLiveFix". */
    fun labelsSummary(mode: String, labeled: Int, skipped: Int, reason: String?): String =
        "mode=$mode labeled=$labeled skipped=$skipped" + (reason?.let { " reason=$it" } ?: "")
}
