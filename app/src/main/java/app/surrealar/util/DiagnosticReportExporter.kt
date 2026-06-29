package app.surrealar.util

import android.content.Context
import android.os.Build
import app.surrealar.BuildConfig
import app.surrealar.SurRealApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a shareable diagnostic ZIP in [Context.getCacheDir]/diagnostic_exports/.
 *
 * Report contents:
 *   diagnostic-summary.txt   build/device/maps info + IMPORTANT WARNINGS
 *   app-log-current.txt      active DiagnosticsLogger event log (AR/model/GNSS events)
 *   app-log-1..4.txt         rotated event logs (when present)
 *   app-errors-*.txt         separate error/crash log (latest crash, survives across launches)
 *   app-state-summary.txt    current GNSS/network/map state (no keys or coordinates)
 *   coordinates.txt          coordinate counts + coordinate→model association summary
 *   models.txt               model inventory: path (basename), file existence, size, link count
 *   current-settings-snapshot.txt  non-sensitive debug-relevant settings
 *   map-troubleshooting.txt / nmea-stream-diagnostics.txt
 *
 * Privacy: no API keys, passwords, auth tokens, full coordinate databases (raw lat/lon are NOT
 * dumped — only counts and model-association rows), model file contents, or raw NMEA streams.
 */
object DiagnosticReportExporter {

    /** Snapshot of app data gathered once and reused across the summary/coordinates/models sections. */
    private data class DiagData(
        val selectedSource: String?,
        val activeProvider: String?,
        val receiverConfigured: Boolean,
        val coordinates: List<app.surrealar.domain.model.Coordinate>,
        val models: List<app.surrealar.domain.model.Model>,
    )

    suspend fun buildReport(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val exportDir = File(context.cacheDir, "diagnostic_exports").also { it.mkdirs() }

            // Keep at most 3 previous exports to avoid filling cache
            exportDir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(3)
                ?.forEach { it.delete() }

            val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
            val zipFile = File(exportDir, "surreal-ar-diagnostic-$stamp.zip")

            // Gather app data once; never let a single failing section abort the whole export.
            val data = runCatching { gatherDiagData(context) }.getOrElse {
                DiagnosticsLogger.w("DiagnosticExport", "gatherDiagData failed: ${it.message}", it)
                DiagData(null, null, false, emptyList(), emptyList())
            }
            val warnings = runCatching { computeWarnings(data) }.getOrDefault(emptyList())

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                zos.addText("diagnostic-summary.txt", buildSummary(context, warnings))

                DiagnosticsLogger.logFiles().forEach { file -> zos.addFile(file.name, file) }
                // Separate error/crash log — bundled even if the rolling event log has moved on.
                DiagnosticsLogger.errorFiles().forEach { file -> zos.addFile(file.name, file) }

                zos.addText("app-state-summary.txt", buildStateSummary(context))
                zos.addTextSafe("coordinates.txt") { buildCoordinatesReport(data) }
                zos.addTextSafe("models.txt") { buildModelsReport(data) }
                zos.addText(
                    "current-settings-snapshot.txt",
                    app.surrealar.util.diagnostics.SettingsSnapshotCollector.collect(context)
                )
                zos.addText(
                    "map-troubleshooting.txt",
                    app.surrealar.util.diagnostics.MapDiagnosticCollector.collect(context)
                )
                zos.addText(
                    "nmea-stream-diagnostics.txt",
                    app.surrealar.util.diagnostics.NmeaStreamDiagnosticsCollector.collect(context)
                )
            }

            zipFile
        } catch (e: Exception) {
            DiagnosticsLogger.e("DiagnosticExport", "Failed to build report", e)
            null
        }
    }

    // ── data gathering ─────────────────────────────────────────────────────────

    private suspend fun gatherDiagData(context: Context): DiagData {
        val ep = dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext, app.surrealar.SurRealApplicationEntryPoint::class.java
        )
        val repo = SurRealApplication.settingsRepo
        val selectedSource = runCatching { repo.locationSource.first().name }.getOrNull()
        val activeProvider = runCatching { ep.sourceSettings().activeProvider.value.toString() }.getOrNull()
        val host = runCatching { repo.externalTcpHost.first() }.getOrNull()
        val coordinates = runCatching { ep.coordinateRepository().getAllCoordinatesList() }.getOrDefault(emptyList())
        val models = runCatching { ep.modelRepository().getAllModels().first() }.getOrDefault(emptyList())
        return DiagData(
            selectedSource = selectedSource,
            activeProvider = activeProvider,
            receiverConfigured = !host.isNullOrBlank(),
            coordinates = coordinates,
            models = models,
        )
    }

    /** Builds the "Important warnings" lines surfaced at the top of the summary. Empty when all clear. */
    private fun computeWarnings(d: DiagData): List<String> = buildList {
        val sel = d.selectedSource?.uppercase(Locale.US)
        val act = d.activeProvider?.uppercase(Locale.US)
        // Selected source vs active provider mismatch (e.g. EXTERNAL selected but INTERNAL routing).
        if (sel != null && act != null && !act.contains(sel) && !sel.contains(act)) {
            val reason = if (sel.contains("EXTERNAL") && !d.receiverConfigured) "external_not_configured" else "provider_differs"
            add("SOURCE mismatch: selected=$sel active=$act reason=\"$reason\"")
        }
        if (sel != null && sel.contains("EXTERNAL") && !d.receiverConfigured) {
            add("GNSS: external receiver selected but no host/port configured")
        }
        // Coordinates link a model whose file is missing on disk.
        val modelById = d.models.associateBy { it.id }
        val missing = d.coordinates.count { c ->
            val mid = c.modelId
            mid != null && (modelById[mid]?.filePath?.let { !File(it).exists() } ?: true)
        }
        if (missing > 0) add("MODEL: $missing coordinate(s) link a model whose file is missing or unresolved — models will not render")
        // Pointers for AR/model issues that can only be reconstructed from the event/error logs.
        add("Note: AR session, Earth-tracking, anchor and model-load outcomes are in app-log-*.txt (tags AR / AR_MDL); crashes are in app-errors-*.txt.")
    }

    // ── section builders ───────────────────────────────────────────────────────

    private fun buildSummary(context: Context, warnings: List<String>): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.appendLine("=== Diagnostic Summary ===")
        sb.appendLine("Report generated : $now")
        sb.appendLine()
        sb.appendLine("--- Important warnings ---")
        if (warnings.isEmpty()) {
            sb.appendLine("(none detected)")
        } else {
            warnings.forEach { sb.appendLine("⚠ $it") }
        }
        sb.appendLine()
        sb.appendLine("--- App ---")
        sb.appendLine("App name         : SurReal AR")
        sb.appendLine("Version name     : ${BuildConfig.VERSION_NAME}")
        sb.appendLine("Version code     : ${BuildConfig.VERSION_CODE}")
        sb.appendLine("Build number     : ${BuildConfig.BUILD_NUMBER}")
        sb.appendLine("Build type       : ${if (BuildConfig.DEBUG) "DEBUG" else "RELEASE"}")
        sb.appendLine("Package          : ${context.packageName}")
        val dirtyTag = if (BuildConfig.BUILD_GIT_DIRTY) "-dirty" else ""
        sb.appendLine("Git commit       : ${BuildConfig.BUILD_GIT_HASH}$dirtyTag")
        sb.appendLine("Git branch       : ${BuildConfig.BUILD_GIT_BRANCH}")
        sb.appendLine("Uncommitted      : ${BuildConfig.BUILD_GIT_DIRTY}")
        sb.appendLine("Build time       : ${BuildConfig.BUILD_TIME}")
        sb.appendLine()
        sb.appendLine("--- Device ---")
        sb.appendLine("Manufacturer     : ${Build.MANUFACTURER}")
        sb.appendLine("Model            : ${Build.MODEL}")
        sb.appendLine("Android version  : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Board            : ${Build.BOARD}")
        sb.appendLine("Hardware         : ${Build.HARDWARE}")
        val screenInfo = try {
            val wm = context.getSystemService(android.view.WindowManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm?.currentWindowMetrics?.bounds
                if (bounds != null) "${bounds.width()}x${bounds.height()}" else "unknown"
            } else {
                @Suppress("DEPRECATION")
                val dm = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                wm?.defaultDisplay?.getMetrics(dm)
                "${dm.widthPixels}x${dm.heightPixels}"
            }
        } catch (_: Exception) { "unknown" }
        sb.appendLine("Screen           : $screenInfo")
        sb.appendLine("Locale           : ${java.util.Locale.getDefault()}")
        sb.appendLine("Timezone         : ${java.util.TimeZone.getDefault().id}")
        sb.appendLine()
        sb.appendLine("--- Maps ---")
        sb.appendLine("Maps renderer    : ${SurRealApplication.activeMapsRenderer}")
        return sb.toString()
    }

    private suspend fun buildStateSummary(context: Context): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.appendLine("=== App State Summary ===")
        sb.appendLine("Generated: $now")
        sb.appendLine()
        sb.appendLine("--- GNSS / Receiver ---")
        try {
            val repo = SurRealApplication.settingsRepo
            val locSrc = runCatching { repo.locationSource.first() }.getOrNull()
            sb.appendLine("Selected source        : ${locSrc?.name ?: "unknown"}")

            // Live routing state (active provider + current fix) read via the Hilt entry point.
            runCatching {
                val ep = dagger.hilt.android.EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    app.surrealar.SurRealApplicationEntryPoint::class.java
                )
                val activeProvider = ep.sourceSettings().activeProvider.value
                sb.appendLine("Active provider        : $activeProvider")

                val fix = ep.fixSwitchboard().currentFix.value
                if (fix != null) {
                    val ageS = java.time.Duration.between(fix.timeUtc, java.time.Instant.now()).seconds
                    sb.appendLine("Current fix            : provider=${fix.provider} status=${fix.rtkStatus}" +
                        " sats=${fix.satsUsed} hAcc=${fix.hAccM?.let { "%.3fm".format(it) } ?: "?"} age=${ageS}s")
                } else {
                    sb.appendLine("Current fix            : none (no live fix from active provider)")
                }

                val switchAt = ep.fixSwitchboard().lastProviderSwitchAtMs
                sb.appendLine("Last provider switch   : " +
                    if (switchAt == 0L) "none this session"
                    else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(switchAt)))
            }.onFailure { sb.appendLine("Active provider        : (unavailable: ${it.message})") }

            val host = runCatching { repo.externalTcpHost.first() }.getOrNull()
            val port = runCatching { repo.externalTcpPort.first() }.getOrNull()
            sb.appendLine("Receiver host/port     : ${if (host.isNullOrBlank()) "not configured" else "$host:$port"}")

            val name = runCatching { repo.externalTcpName.first() }.getOrNull()
            sb.appendLine("Receiver name          : ${name?.takeIf { it.isNotBlank() } ?: "not set"}")
        } catch (e: Exception) {
            sb.appendLine("(settings read error: ${e.message})")
        }
        sb.appendLine()
        sb.appendLine("--- Maps ---")
        sb.appendLine("Active renderer        : ${SurRealApplication.activeMapsRenderer}")
        sb.appendLine("Map load status        : ${SurRealApplication.mapLoadStatus}")
        sb.appendLine()
        sb.appendLine("--- Network ---")
        try {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val net = cm?.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            val hasInternet  = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val hasValidated = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val isVpn        = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
            val isWifi       = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            val isCellular   = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
            val transport = when {
                net == null -> "none"; isVpn -> "VPN"; isWifi -> "WiFi"
                isCellular  -> "Cellular"; else -> "other"
            }
            sb.appendLine("Transport              : $transport")
            sb.appendLine("Internet capability    : $hasInternet")
            sb.appendLine("Validated              : $hasValidated")
            if (hasInternet && !hasValidated) {
                sb.appendLine("WARNING: internet=true but validated=false — device may be on receiver WiFi; map tiles may not load")
            }
        } catch (e: Exception) {
            sb.appendLine("(network state unavailable: ${e.message})")
        }
        return sb.toString()
    }

    // Settings now live in `current-settings-snapshot.txt` (SettingsSnapshotCollector) — a more
    // complete, sanitized snapshot that supersedes the old settings-summary section.

    /**
     * Coordinate counts + the coordinate→model association rows needed to diagnose "models linked
     * but not rendering". Raw lat/lon are deliberately NOT dumped (privacy); only aggregate counts
     * and, for model-linked coordinates, the link/file state.
     */
    private fun buildCoordinatesReport(d: DiagData): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val coords = d.coordinates
        val modelById = d.models.associateBy { it.id }
        val linked = coords.filter { it.modelId != null }
        val sb = StringBuilder()
        sb.appendLine("=== Coordinates ===")
        sb.appendLine("Generated: $now")
        sb.appendLine()
        sb.appendLine("Total coordinates        : ${coords.size}")
        sb.appendLine("With linked model        : ${linked.size}")
        sb.appendLine("Render-in-AR enabled     : ${coords.count { it.renderEnabled }}")
        sb.appendLine("With finite altitude     : ${coords.count { it.altitude.isFinite() }}")
        sb.appendLine("With UTM (E/N/zone)      : ${coords.count { it.easting != null && it.northing != null && !it.utmZone.isNullOrBlank() }}")
        sb.appendLine()
        sb.appendLine("--- Coordinate → model associations ---")
        if (linked.isEmpty()) {
            sb.appendLine("(no coordinates link a model)")
        } else {
            linked.forEach { c ->
                val model = c.modelId?.let { modelById[it] }
                val fileState = when {
                    model == null -> "model_record_missing"
                    !File(model.filePath).exists() -> "file_missing"
                    else -> "ok(${File(model.filePath).length()}B)"
                }
                sb.appendLine("id=${c.id} name=\"${c.name}\" modelId=${c.modelId} " +
                    "renderEnabled=${c.renderEnabled} altitude=${if (c.altitude.isFinite()) "yes" else "missing"} file=$fileState")
            }
        }
        return sb.toString()
    }

    /** Model inventory: basename (sanitized), file existence, stored vs actual size, and link count. */
    private fun buildModelsReport(d: DiagData): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val models = d.models
        val linkCounts = d.coordinates.mapNotNull { it.modelId }.groupingBy { it }.eachCount()
        val sb = StringBuilder()
        sb.appendLine("=== Models ===")
        sb.appendLine("Generated: $now")
        sb.appendLine()
        sb.appendLine("Total models             : ${models.size}")
        sb.appendLine("Missing files            : ${models.count { !File(it.filePath).exists() }}")
        sb.appendLine()
        if (models.isEmpty()) {
            sb.appendLine("(no models imported)")
        } else {
            models.forEach { m ->
                val f = File(m.filePath)
                val exists = f.exists()
                // Show only the file name, not the full internal path.
                sb.appendLine("id=${m.id} name=\"${m.name}\" file=\"${f.name}\" type=${m.fileType} " +
                    "exists=$exists sizeBytes=${if (exists) f.length() else -1L} storedSize=${m.fileSize} " +
                    "linkedCoordinates=${linkCounts[m.id] ?: 0}")
            }
        }
        return sb.toString()
    }

    // ── ZIP helpers ────────────────────────────────────────────────────────────

    private fun ZipOutputStream.addText(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    /** Like [addText] but never lets one failing section abort the export — writes the error instead. */
    private fun ZipOutputStream.addTextSafe(name: String, build: () -> String) {
        val content = runCatching { build() }.getOrElse {
            DiagnosticsLogger.w("DiagnosticExport", "section $name failed: ${it.message}", it)
            "Section failed to generate: ${it.message}"
        }
        addText(name, content)
    }

    private fun ZipOutputStream.addFile(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }
}
