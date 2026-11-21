package app.surrealar.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.surrealar.BuildConfig
import app.surrealar.SurRealApplication
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.source.SourceRoutingDecisions
import app.surrealar.gnss.source.SourceSettings.ProviderChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipOutputStream

/**
 * Builds a shareable diagnostic ZIP in [Context.getCacheDir]/diagnostic_exports/.
 *
 * Gathers the running app's state as [app.surrealar.util.diagnostics.report.ReportSources] and hands it
 * to [app.surrealar.util.diagnostics.report.ReportAssembler], which flushes the logs and writes every
 * file (the list and what each file is: README.txt in the ZIP / ReadmeCollector). Everything written
 * passes through ReportRedactor: no API keys, passwords, tokens, raw NMEA or coordinates. Receiver
 * host/IP + port ARE included (connectivity).
 */
object DiagnosticReportExporter {

    /** Snapshot of app data gathered once and reused across the summary/coordinates/models sections. */
    private data class DiagData(
        val selectedSource: LocationSourceType?,
        val activeProvider: ProviderChoice?,
        val receiverConfigured: Boolean,
        val coordinates: List<app.surrealar.domain.model.Coordinate>,
        val models: List<app.surrealar.domain.model.Model>,
    )

    /** A built report: the ZIP plus the short ID that is also in its summary and the email body. */
    data class Report(val file: File, val reportId: String)

    suspend fun buildReport(context: Context): Report? = withContext(Dispatchers.IO) {
        try {
            val exportDir = File(context.cacheDir, "diagnostic_exports").also { it.mkdirs() }

            // Keep at most 3 previous exports to avoid filling cache
            exportDir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(3)
                ?.forEach { it.delete() }

            val reportId = app.surrealar.util.diagnostics.ReportNaming.newReportId()
            val zipFile = File(exportDir, app.surrealar.util.diagnostics.ReportNaming.fileName(
                Build.MODEL ?: "device", BuildConfig.VERSION_NAME, System.currentTimeMillis()))

            // Gather app data once; never let a single failing section abort the whole export.
            val data = runCatching { gatherDiagData(context) }.getOrElse {
                DiagnosticsLogger.w("EXPORT", "gatherDiagData failed: ${it.message}", it)
                DiagData(null, null, false, emptyList(), emptyList())
            }
            val warnings = runCatching { computeWarnings(data) }.getOrDefault(emptyList())
            val permWarnings = runCatching { computePermissionWarnings(context) }.getOrDefault(emptyList())

            val sources = ContextSources(context, reportId, System.currentTimeMillis(), data, warnings + permWarnings)
            app.surrealar.util.diagnostics.report.ReportAssembler.write(zipFile, sources)

            Report(zipFile, reportId)
        } catch (e: Exception) {
            DiagnosticsLogger.e("EXPORT", "Failed to build report", e)
            null
        }
    }

    /** The running app as [app.surrealar.util.diagnostics.report.ReportSources]. */
    private class ContextSources(
        private val context: Context,
        override val reportId: String,
        override val nowMs: Long,
        private val data: DiagData,
        private val warnings: List<String>,
    ) : app.surrealar.util.diagnostics.report.ReportSources {
        override suspend fun summary() = buildSummary(context, warnings, reportId)
        override suspend fun settingsSnapshot() = app.surrealar.util.diagnostics.SettingsSnapshotCollector.collect(context)
        override suspend fun permissions() = buildPermissionsStatus(context)
        override suspend fun appState(): String {
            val g = app.surrealar.util.diagnostics.GnssFeedLogger.instance
            val ep = runCatching {
                dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext,
                    app.surrealar.SurRealApplicationEntryPoint::class.java)
            }.getOrNull()
            val extras = app.surrealar.util.diagnostics.report.AppStateExtrasCollector.Extras(
                receiverDevice = runCatching { ep?.receiverDeviceManager()?.activeDevice?.value?.javaClass?.simpleName }.getOrNull(),
                receiverProfile = runCatching { ep?.settingsRepository()?.externalReceiverProfile?.first()?.label }.getOrNull(),
                receiverLink = g?.link?.lastLinkState,
                corrections = g?.link?.correctionsSummary,
                clockOffsetMs = app.surrealar.util.diagnostics.ClockOffset.lastOffsetMs,
                headingSource = app.surrealar.util.diagnostics.HeadingDiagnostics.lastSource,
                currentScreen = DiagnosticsLogger.currentScreen,
            )
            return buildStateSummary(context) + app.surrealar.util.diagnostics.report.AppStateExtrasCollector.render(extras)
        }
        override suspend fun coordinates() = buildCoordinatesReport(data)
        override suspend fun models() = buildModelsReport(data)
        // Most recent AR/model session (persisted file survives event-log rotation).
        override fun arSession() = DiagnosticsLogger.sessionSummaryFile("ar-last-session.txt")?.readText()
            ?: "No AR session has been recorded yet.\n"
        override suspend fun mapDiagnostics() = app.surrealar.util.diagnostics.MapDiagnosticCollector.collect(context)
        override suspend fun nmeaStream() = app.surrealar.util.diagnostics.NmeaStreamDiagnosticsCollector.collect(context)
        override fun liveState(): app.surrealar.util.diagnostics.report.LiveState {
            val g = app.surrealar.util.diagnostics.GnssFeedLogger.instance
            return app.surrealar.util.diagnostics.report.LiveState(
                source = g?.feed?.currentSource(),
                rtk = g?.feed?.currentRtk()?.name,
                receiverLink = g?.link?.lastLinkState,
                correctionAgeS = g?.feed?.currentCorrectionAgeS(),
                corrections = g?.link?.correctionsSummary,
                freeStorageBytes = runCatching { android.os.StatFs(context.filesDir.path).availableBytes }.getOrNull(),
            )
        }
        override fun exitTraceFiles(): List<File> =
            app.surrealar.util.diagnostics.ExitReasonRecorder.exitsDir(context).listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        override fun deviceHealth() = app.surrealar.util.diagnostics.report.AppReportReaders.deviceHealth(context)
        override fun capturesLog() = app.surrealar.util.diagnostics.CaptureLog.file()
        override suspend fun gnssStats(): app.surrealar.util.diagnostics.report.GnssStatsInput {
            val g = app.surrealar.util.diagnostics.GnssFeedLogger.instance
            val nmea = runCatching {
                dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext,
                    app.surrealar.SurRealApplicationEntryPoint::class.java).diagnosticsService().diagnosticData.value
            }.getOrNull()
            return app.surrealar.util.diagnostics.report.GnssStatsInput(
                stats = g?.feed?.stats(app.surrealar.util.diagnostics.GnssFeedLogger.now()),
                reconnects = g?.link?.reconnects,
                nmeaLines = nmea?.totalLinesProcessed, nmeaParseErrors = nmea?.totalParseErrors,
                sentenceCounts = app.surrealar.util.diagnostics.report.GnssStatsCollector.sentenceCounts(nmeaStream()),
            )
        }
        override fun dataHealth() = app.surrealar.util.diagnostics.report.AppReportReaders.dataHealth(
            dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext,
                app.surrealar.SurRealApplicationEntryPoint::class.java).appDatabase())
        override fun logFiles() = DiagnosticsLogger.logFiles()
        override fun errorFiles() = DiagnosticsLogger.errorFiles()
    }

    // ── data gathering ─────────────────────────────────────────────────────────

    private suspend fun gatherDiagData(context: Context): DiagData {
        val ep = dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext, app.surrealar.SurRealApplicationEntryPoint::class.java
        )
        val repo = SurRealApplication.settingsRepo
        val selectedSource = runCatching { repo.locationSource.first() }.getOrNull()
        val activeProvider = runCatching { ep.sourceSettings().activeProvider.value }.getOrNull()
        val host = runCatching { repo.externalTcpHost.first() }.getOrNull()
        val port = runCatching { repo.externalTcpPort.first() }.getOrNull()
        val coordinates = runCatching { ep.coordinateRepository().getAllCoordinatesList() }.getOrDefault(emptyList())
        val models = runCatching { ep.modelRepository().getAllModels().first() }.getOrDefault(emptyList())
        return DiagData(
            selectedSource = selectedSource,
            activeProvider = activeProvider,
            // Same "usable" notion as the coordinator (host AND port), so the exporter's
            // external_not_configured matches GnssSourceCoordinator's decision.
            receiverConfigured = SourceRoutingDecisions.externalConfigUsable(host, port),
            coordinates = coordinates,
            models = models,
        )
    }

    /** Runtime-permission grants + location-services state (Q9). No sensitive data. */
    private fun buildPermissionsStatus(context: Context): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        fun grant(perm: String): String =
            if (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED)
                "GRANTED" else "DENIED"
        val sb = StringBuilder()
        sb.appendLine("=== Permissions Status ===")
        sb.appendLine("Generated: $now")
        sb.appendLine()
        sb.appendLine("--- Runtime permissions ---")
        sb.appendLine("Camera (AR)              : ${grant(Manifest.permission.CAMERA)}")
        sb.appendLine("Fine location (GNSS)     : ${grant(Manifest.permission.ACCESS_FINE_LOCATION)}")
        sb.appendLine("Coarse location          : ${grant(Manifest.permission.ACCESS_COARSE_LOCATION)}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            sb.appendLine("Bluetooth connect        : ${grant(Manifest.permission.BLUETOOTH_CONNECT)}")
            sb.appendLine("Bluetooth scan           : ${grant(Manifest.permission.BLUETOOTH_SCAN)}")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sb.appendLine("Post notifications       : ${grant(Manifest.permission.POST_NOTIFICATIONS)}")
        }
        sb.appendLine()
        sb.appendLine("--- Location services ---")
        runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            sb.appendLine("GPS provider enabled     : ${lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)}")
            sb.appendLine("Network provider enabled : ${lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)}")
        }.onFailure { sb.appendLine("Location services        : (unavailable: ${it.message})") }
        return sb.toString()
    }

    /** Permission-based warnings for the summary — denied camera/location explain AR/GNSS failures. */
    private fun computePermissionWarnings(context: Context): List<String> = buildList {
        fun denied(p: String) = ContextCompat.checkSelfPermission(context, p) != PackageManager.PERMISSION_GRANTED
        if (denied(Manifest.permission.CAMERA))
            add("PERMISSION: Camera denied — AR cannot start")
        if (denied(Manifest.permission.ACCESS_FINE_LOCATION))
            add("PERMISSION: Fine location denied — GNSS/geospatial will not work")
    }

    /**
     * Formats the selected-vs-active source mismatch warning using the SAME classifier the
     * coordinator uses ([SourceRoutingDecisions.classifySelectedActiveMismatch]), so the export can
     * never describe the mismatch differently from how routing decided it. Returns null when the
     * sources are aligned (e.g. SIMULATOR selected → INTERNAL active is NOT a mismatch). `internal`
     * for unit tests.
     */
    internal fun sourceMismatchWarning(
        selected: LocationSourceType?, active: ProviderChoice?, externalConfigured: Boolean
    ): String? {
        if (selected == null || active == null) return null
        val mismatch = SourceRoutingDecisions.classifySelectedActiveMismatch(selected, active, externalConfigured)
        return if (mismatch.isMismatch)
            "SOURCE mismatch: selected=${selected.name} active=$active reason=\"${mismatch.reason}\""
        else null
    }

    /** Builds the "Important warnings" lines surfaced at the top of the summary. Empty when all clear. */
    private fun computeWarnings(d: DiagData): List<String> = buildList {
        // Selected source vs active provider mismatch — unified with the coordinator's classifier.
        sourceMismatchWarning(d.selectedSource, d.activeProvider, d.receiverConfigured)?.let { add(it) }
        if (d.selectedSource == LocationSourceType.EXTERNAL && !d.receiverConfigured) {
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
        add("Note: AR session, Earth-tracking, anchor and model-load outcomes are in app-log-*.txt (tags AR / MODEL); crashes are in app-errors-*.txt.")
    }

    // ── section builders ───────────────────────────────────────────────────────

    private fun buildSummary(context: Context, warnings: List<String>, reportId: String): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.appendLine("=== Diagnostic Summary ===")
        sb.appendLine("Report ID        : $reportId")
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
        val hasArSession = ArSessionDiagnostics.hasSession()
        val sb = StringBuilder()
        sb.appendLine("=== Model Integrity ===")
        sb.appendLine("Generated: $now")
        sb.appendLine()
        sb.appendLine("Total models             : ${models.size}")
        sb.appendLine("Missing files            : ${models.count { !File(it.filePath).exists() }}")
        sb.appendLine("Last AR session present  : $hasArSession")
        sb.appendLine("(lastArStatus reflects the most recent AR session in this app run, when available)")
        sb.appendLine()
        if (models.isEmpty()) {
            sb.appendLine("(no models imported)")
        } else {
            models.forEach { m ->
                val f = File(m.filePath)
                val exists = f.exists()
                // Join the stored model file to its last AR loading status (by model id).
                val arEntry = if (hasArSession) ArSessionDiagnostics.statusForModelId(m.id) else null
                val arStatus = when {
                    !hasArSession -> "not_used_in_last_ar_session"
                    arEntry == null -> "not_used_in_last_ar_session"
                    else -> arEntry.status.name.lowercase(Locale.US)
                }
                val arReason = arEntry?.reason?.let { " reason=$it" } ?: ""
                // Show only the file name, not the full internal path.
                sb.appendLine("id=${m.id} name=\"${m.name}\" file=\"${f.name}\" type=${m.fileType} " +
                    "exists=$exists sizeBytes=${if (exists) f.length() else -1L} storedSize=${m.fileSize} " +
                    "linkedCoordinates=${linkCounts[m.id] ?: 0} lastArStatus=$arStatus$arReason")
            }
        }
        return sb.toString()
    }

    // ── ZIP helpers ────────────────────────────────────────────────────────────

    /**
     * Flushes queued log lines to disk, then writes the ZIP — so the events right before the export
     * (including the export's own) are in it. `internal` for tests.
     */
    internal suspend fun writeZip(
        zipFile: File,
        flush: () -> Unit = { DiagnosticsLogger.flush() },
        body: suspend (ZipOutputStream) -> Unit,
    ) {
        runCatching(flush)
        ZipOutputStream(FileOutputStream(zipFile)).use { body(it) }
    }
}
