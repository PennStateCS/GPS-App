package com.example.surveyingapp.util

import android.content.Context
import android.os.Build
import com.example.surveyingapp.BuildConfig
import com.example.surveyingapp.SurveyingApp
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
 *   diagnostic-summary.txt   build/device/maps info
 *   app-log-current.txt      active DiagnosticsLogger file
 *   app-log-1/2/3.txt        rotated logs (when present)
 *   app-state-summary.txt    current GNSS/network/map state (no keys or coordinates)
 *   settings-summary.txt     non-sensitive debug-relevant settings
 *
 * Privacy: no API keys, passwords, auth tokens, full coordinate databases,
 * model file contents, or raw NMEA streams are included.
 */
object DiagnosticReportExporter {

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

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                zos.addText("diagnostic-summary.txt", buildSummary(context))

                DiagnosticsLogger.logFiles().forEach { file ->
                    zos.addFile(file.name, file)
                }

                zos.addText("app-state-summary.txt", buildStateSummary(context))
                zos.addText(
                    "current-settings-snapshot.txt",
                    com.example.surveyingapp.util.diagnostics.SettingsSnapshotCollector.collect(context)
                )
                zos.addText(
                    "map-troubleshooting.txt",
                    com.example.surveyingapp.util.diagnostics.MapDiagnosticCollector.collect(context)
                )
            }

            zipFile
        } catch (e: Exception) {
            DiagnosticsLogger.e("DiagnosticExport", "Failed to build report", e)
            null
        }
    }

    // ── section builders ───────────────────────────────────────────────────────

    private fun buildSummary(context: Context): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.appendLine("=== Diagnostic Summary ===")
        sb.appendLine("Report generated : $now")
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
        sb.appendLine("Maps renderer    : ${SurveyingApp.activeMapsRenderer}")
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
            val repo = SurveyingApp.settingsRepo
            val locSrc = runCatching { repo.locationSource.first() }.getOrNull()
            sb.appendLine("Selected source        : ${locSrc?.name ?: "unknown"}")

            // Live routing state (active provider + current fix) read via the Hilt entry point.
            runCatching {
                val ep = dagger.hilt.android.EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    com.example.surveyingapp.SurveyingAppEntryPoint::class.java
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
        sb.appendLine("Active renderer        : ${SurveyingApp.activeMapsRenderer}")
        sb.appendLine("Map load status        : ${SurveyingApp.mapLoadStatus}")
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

    // ── ZIP helpers ────────────────────────────────────────────────────────────

    private fun ZipOutputStream.addText(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.addFile(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }
}
