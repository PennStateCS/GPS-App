package app.surrealar.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory, process-lifetime holder for the MOST RECENT AR/model session's diagnostics.
 *
 * Updated by [app.surrealar.ui.openinar.OpenInARFragment] (lifecycle/anchor/tracking/skip events)
 * and [app.surrealar.ui.openinar.ArFilamentRenderer] (model load/parse/asset outcomes). Read by
 * [DiagnosticReportExporter] to build `ar-session-summary.txt` (also persisted via
 * [DiagnosticsLogger.writeSessionSummary]) and the per-model status column of `model-integrity.txt`.
 *
 * This is NOT an event log — it only holds compact last-session state (counts + a per-coordinate
 * model status map). Thread-safe: written from the GL thread, the main thread, and renderer
 * coroutines. No coordinates, credentials, or raw file contents are stored.
 */
object ArSessionDiagnostics {

    /** Per-coordinate model lifecycle status during the last AR session. */
    enum class ModelStatus {
        NOT_USED, QUEUED, LOADING, READY,
        /** Model was preloaded but the anchor never reached TRACKING before the session ended. */
        ANCHOR_PENDING,
        /** Model was preloaded and anchor tracked, but the model was never selected/visible this session. */
        NOT_RENDERED,
        IN_SCENE, FAILED, SKIPPED
    }

    class ModelEntry(
        val coordinateId: String,
        val coordinateName: String,
        val modelId: String?,
        val pathBasename: String?,
        val fileExists: Boolean,
        val fileSizeBytes: Long,
        @Volatile var status: ModelStatus,
        @Volatile var reason: String? = null,
    )

    @Volatile var openedAtMs = 0L;  private set
    @Volatile var closedAtMs = 0L;  private set
    @Volatile var coordinateCount = 0
    @Volatile var linkedModelCount = 0
    @Volatile var coordinatesVisible = 0
    @Volatile var labelsShown = 0
    @Volatile var anchorsAttempted = 0
    @Volatile var anchorsCreated = 0
    @Volatile var anchorFailures = 0
    @Volatile var activeAnchorCount = 0
    @Volatile var reanchorAttempts = 0
    @Volatile var lastEarthTrackingState: String? = null
    @Volatile var lastEarthState: String? = null
    @Volatile var lastCameraTrackingState: String? = null

    // ── AR / mock-location / source context captured at anchor creation ───────────────────────────
    // Answers, from a future export: was mock location enabled + actively injecting during AR, what
    // source/fix was active, and what was ARCore Earth's pose accuracy when the anchors were created.
    // Accuracy fields are null when genuinely unknown — never 0.0-as-missing.
    @Volatile var arSelectedSource: String? = null
    @Volatile var arCurrentFixProvider: String? = null
    @Volatile var arMockEnabledDuringAr: Boolean? = null
    @Volatile var arMockInjectionActiveDuringAr: Boolean? = null
    @Volatile var arMockAppApproved: Boolean? = null
    @Volatile var arLastMockFixAgeAtAnchorMs: Long? = null
    @Volatile var arEarthTrackingAtAnchor: String? = null
    @Volatile var arHAccAtAnchorM: Double? = null
    @Volatile var arVAccAtAnchorM: Double? = null
    @Volatile var arYawAccAtAnchorDeg: Double? = null
    @Volatile var anchorsViaTimeout = 0
    @Volatile var anchorsRebuiltAfterInitial = 0
    /** "anchor-driven" once real anchors are committed; distinguishes stable anchoring from user-relative. */
    @Volatile var modelTransformSource: String? = null

    // key = coordinateId
    private val models = ConcurrentHashMap<String, ModelEntry>()

    @Synchronized
    fun startSession() {
        models.clear()
        openedAtMs = System.currentTimeMillis(); closedAtMs = 0L
        coordinateCount = 0; linkedModelCount = 0; coordinatesVisible = 0; labelsShown = 0
        anchorsAttempted = 0; anchorsCreated = 0; anchorFailures = 0
        activeAnchorCount = 0; reanchorAttempts = 0
        lastEarthTrackingState = null; lastEarthState = null; lastCameraTrackingState = null
        arSelectedSource = null; arCurrentFixProvider = null
        arMockEnabledDuringAr = null; arMockInjectionActiveDuringAr = null; arMockAppApproved = null
        arLastMockFixAgeAtAnchorMs = null; arEarthTrackingAtAnchor = null
        arHAccAtAnchorM = null; arVAccAtAnchorM = null; arYawAccAtAnchorDeg = null
        anchorsViaTimeout = 0; anchorsRebuiltAfterInitial = 0; modelTransformSource = null
    }

    /** Finalizes terminal states for models that loaded but never reached the scene. */
    @Synchronized
    fun endSession() {
        closedAtMs = System.currentTimeMillis()
        val earthWasTracking = lastEarthTrackingState == "TRACKING"
        models.values.forEach { e ->
            when (e.status) {
                // Still loading/queued at session end — anchor tracking is the likely blocker.
                ModelStatus.QUEUED, ModelStatus.LOADING -> {
                    e.status = ModelStatus.ANCHOR_PENDING
                    if (e.reason == null)
                        e.reason = if (earthWasTracking) "anchor_pending" else "earth_not_tracking"
                }
                // Preloaded successfully but never rendered — typically not selected as visible.
                // Distinct from ANCHOR_PENDING so the summary is not misleading.
                ModelStatus.READY -> {
                    e.status = if (earthWasTracking) ModelStatus.NOT_RENDERED else ModelStatus.ANCHOR_PENDING
                    if (e.reason == null)
                        e.reason = if (earthWasTracking) "not_selected_visible" else "earth_not_tracking"
                }
                else -> { /* IN_SCENE, FAILED, SKIPPED, NOT_USED — already terminal, leave as-is */ }
            }
        }
    }

    fun hasSession(): Boolean = openedAtMs != 0L

    /** Records (or replaces) a model-linked coordinate's initial status for this session. */
    fun recordModel(
        coordinateId: String, coordinateName: String, modelId: String?, pathBasename: String?,
        fileExists: Boolean, fileSizeBytes: Long, status: ModelStatus, reason: String? = null,
    ) {
        models[coordinateId] = ModelEntry(
            coordinateId, coordinateName, modelId, pathBasename, fileExists, fileSizeBytes, status, reason
        )
    }

    /**
     * Updates a model's status by coordinate key (e.g. from the renderer). Will not downgrade a
     * terminal IN_SCENE/FAILED state to an intermediate one.
     */
    fun setStatus(coordinateId: String, status: ModelStatus, reason: String? = null) {
        val e = models[coordinateId] ?: return
        if (e.status == ModelStatus.IN_SCENE && status != ModelStatus.IN_SCENE) return
        if (e.status == ModelStatus.FAILED && status != ModelStatus.FAILED) return
        e.status = status
        if (reason != null) e.reason = reason
    }

    fun models(): List<ModelEntry> = models.values.sortedBy { it.coordinateName }

    /** Best (most successful) status seen for a model id across all coordinates that link it. */
    fun statusForModelId(modelId: String): ModelEntry? =
        models.values.filter { it.modelId == modelId }.maxByOrNull { successRank(it.status) }

    private fun successRank(s: ModelStatus): Int = when (s) {
        ModelStatus.IN_SCENE -> 7; ModelStatus.READY -> 6
        ModelStatus.NOT_RENDERED -> 5       // preloaded + anchor tracked, just not visible
        ModelStatus.ANCHOR_PENDING -> 4     // preloaded but anchor never tracked
        ModelStatus.LOADING -> 3; ModelStatus.QUEUED -> 2; ModelStatus.SKIPPED -> 1
        ModelStatus.FAILED -> 0; ModelStatus.NOT_USED -> -1
    }

    private fun countByStatus(s: ModelStatus) = models.values.count { it.status == s }

    /** yes/no/unknown for nullable booleans in the summary (null = genuinely undetermined). */
    private fun yn(b: Boolean?): String = when (b) { true -> "yes"; false -> "no"; null -> "unknown" }

    /** Counts of SKIPPED/FAILED reasons (short structured strings) for the summary. */
    fun reasonCounts(): Map<String, Int> =
        models.values
            .filter { it.status == ModelStatus.SKIPPED || it.status == ModelStatus.FAILED }
            .groupingBy { it.reason ?: "unknown" }.eachCount()

    /** Compact human-readable summary (written to ar-last-session.txt / ar-session-summary.txt). */
    fun buildSummaryText(): String = buildString {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val durMs = (if (closedAtMs > 0) closedAtMs else System.currentTimeMillis()) - openedAtMs
        appendLine("=== AR last session ===")
        appendLine("opened    : ${if (openedAtMs > 0) fmt.format(Date(openedAtMs)) else "n/a"}")
        appendLine("closed    : ${if (closedAtMs > 0) fmt.format(Date(closedAtMs)) else "n/a (still open or crashed)"}")
        appendLine("durationS : ${if (openedAtMs > 0) durMs / 1000 else 0}")
        appendLine()
        appendLine("coordinatesReceived  : $coordinateCount")
        appendLine("coordinatesWithModel : $linkedModelCount")
        appendLine("coordinatesVisible   : $coordinatesVisible")
        appendLine("labelsShown(max)     : $labelsShown")
        appendLine()
        appendLine("models considered    : ${models.size}")
        appendLine("  queued   : ${countByStatus(ModelStatus.QUEUED)}")
        appendLine("  loading  : ${countByStatus(ModelStatus.LOADING)}")
        appendLine("  loaded   : ${countByStatus(ModelStatus.READY) + countByStatus(ModelStatus.ANCHOR_PENDING) + countByStatus(ModelStatus.NOT_RENDERED)}")
        appendLine("  inScene  : ${countByStatus(ModelStatus.IN_SCENE)}")
        appendLine("  notVis   : ${countByStatus(ModelStatus.NOT_RENDERED)}")
        appendLine("  failed   : ${countByStatus(ModelStatus.FAILED)}")
        appendLine("  skipped  : ${countByStatus(ModelStatus.SKIPPED)}")
        val reasons = reasonCounts()
        if (reasons.isNotEmpty()) appendLine("  reasons  : " + reasons.entries.joinToString { "${it.key}=${it.value}" })
        appendLine()
        appendLine("anchorsAttempted : $anchorsAttempted")
        appendLine("anchorsCreated   : $anchorsCreated")
        appendLine("anchorFailures   : $anchorFailures")
        appendLine("activeAnchors    : $activeAnchorCount")
        appendLine("reanchorAttempts : $reanchorAttempts")
        appendLine("lastEarthTracking: ${lastEarthTrackingState ?: "never"}")
        appendLine("lastEarthState   : ${lastEarthState ?: "n/a"}")
        appendLine("lastCameraTrack  : ${lastCameraTrackingState ?: "n/a"}")
        appendLine()
        appendLine("--- warnings ---")
        val warnings = buildList {
            if (labelsShown > 0 && countByStatus(ModelStatus.IN_SCENE) == 0 && linkedModelCount > 0)
                add("labels rendered but NO models reached the scene")
            if (linkedModelCount > 0 && lastEarthTrackingState != "TRACKING")
                add("Earth tracking never reached TRACKING — geospatial anchors could not form")
            val failed = countByStatus(ModelStatus.FAILED)
            if (failed > 0) add("$failed model(s) FAILED (${reasons.filterValues { it > 0 }})")
            val notRendered = countByStatus(ModelStatus.NOT_RENDERED)
            if (notRendered > 0) add("$notRendered model(s) were loaded and ready but never rendered (not selected as visible this session)")
        }
        if (warnings.isEmpty()) appendLine("(none)") else warnings.forEach { appendLine("⚠ $it") }
        appendLine()
        appendLine("--- AR / Mock Location Summary ---")
        appendLine("selectedSource(AR)         : ${arSelectedSource ?: "unknown"}")
        appendLine("currentFixProvider(AR)     : ${arCurrentFixProvider ?: "unknown"}")
        appendLine("mockLocationEnabled(AR)    : ${yn(arMockEnabledDuringAr)}")
        appendLine("mockInjectionActive(AR)    : ${yn(arMockInjectionActiveDuringAr)}")
        appendLine("mockAppApproved(AppOps)    : ${yn(arMockAppApproved)}")
        appendLine("lastMockFixAge@anchor(ms)  : ${arLastMockFixAgeAtAnchorMs?.toString() ?: "n/a"}")
        appendLine("earthTracking@anchor       : ${arEarthTrackingAtAnchor ?: "n/a"}")
        appendLine("earth hAcc@anchor(m)       : ${arHAccAtAnchorM?.let { "%.1f".format(it) } ?: "unknown"}")
        appendLine("earth vAcc@anchor(m)       : ${arVAccAtAnchorM?.let { "%.1f".format(it) } ?: "unknown"}")
        appendLine("earth yawAcc@anchor(deg)   : ${arYawAccAtAnchorDeg?.let { "%.1f".format(it) } ?: "unknown"}")
        appendLine("anchorsCreatedViaTimeout   : $anchorsViaTimeout")
        appendLine("anchorsRebuiltAfterInitial : $anchorsRebuiltAfterInitial")
        appendLine("modelTransformSource       : ${modelTransformSource ?: "unknown"}")
        appendLine()
        appendLine("--- per-model (last AR session) ---")
        if (models.isEmpty()) {
            appendLine("(no model-linked coordinates considered)")
        } else {
            models().forEach { m ->
                appendLine("coordId=${m.coordinateId} name=\"${m.coordinateName}\" modelId=${m.modelId} " +
                    "exists=${m.fileExists} sizeBytes=${m.fileSizeBytes} status=${m.status}" +
                    (m.reason?.let { " reason=$it" } ?: ""))
            }
        }
        appendLine()
        appendLine("(detailed per-event timeline is in app-log-*.txt under tag AR_MDL)")
    }
}
