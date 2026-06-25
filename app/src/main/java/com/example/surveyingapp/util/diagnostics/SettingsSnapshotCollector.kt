package com.example.surveyingapp.util.diagnostics

import android.content.Context
import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.SurveyingAppEntryPoint
import com.example.surveyingapp.util.DiagnosticsLogger
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first

/**
 * Read-only, sanitized snapshot of the app's current persisted settings for the diagnostic report,
 * so map/GNSS/receiver/AR/capture issues can be diagnosed without asking the tester for Settings
 * screenshots.
 *
 * Privacy: no API keys, passwords, tokens, raw NMEA, or live coordinates. Receiver host/IP + port
 * ARE included (needed for connectivity troubleshooting). Only reads settings — never writes.
 */
object SettingsSnapshotCollector {

    suspend fun collect(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== Current Settings Snapshot ===")
        sb.appendLine("Sanitized — no keys/passwords/tokens/raw NMEA/live coordinates.")
        sb.appendLine()

        val repo = SurveyingApp.settingsRepo

        // ── General app / display ────────────────────────────────────────────────
        sb.appendLine("--- App / display ---")
        runCatching { repo.appearanceSettings.first() }.getOrNull()?.let { a ->
            sb.appendLine("Theme mode              : ${a.themeMode}")
            sb.appendLine("Live GNSS status bar    : ${a.showLiveGnssStatusBar}")
            sb.appendLine("Keep screen awake       : ${a.keepScreenAwake}")
            sb.appendLine("Max brightness          : ${a.maxBrightnessWhileOpen}")
        }
        runCatching { repo.developerSettings.first() }.getOrNull()?.let {
            sb.appendLine("Developer tools enabled : ${it.developerToolsEnabled}")
        }
        sb.appendLine()

        // ── Map ───────────────────────────────────────────────────────────────────
        sb.appendLine("--- Map ---")
        sb.appendLine("Maps renderer (active)  : ${SurveyingApp.activeMapsRenderer}")
        sb.appendLine("Last map load status    : ${SurveyingApp.mapLoadStatus}")
        sb.appendLine("Maps API key            : ${MapDiagnosticCollector.redactApiKey(readMapsApiKey(context))}")
        sb.appendLine("(Map type & dark-map style are runtime UI state, not persisted settings — see map-troubleshooting.txt.)")
        sb.appendLine()

        // ── Location / GNSS source ─────────────────────────────────────────────────
        sb.appendLine("--- Location / GNSS source ---")
        val selectedSource = runCatching { repo.locationSource.first() }.getOrNull()
        sb.appendLine("Selected source         : ${selectedSource?.name ?: "unknown"}")
        runCatching {
            val ep = EntryPointAccessors.fromApplication(context.applicationContext, SurveyingAppEntryPoint::class.java)
            sb.appendLine("Active provider         : ${ep.sourceSettings().activeProvider.value}")
            val switchAt = ep.fixSwitchboard().lastProviderSwitchAtMs
            sb.appendLine("Last provider switch    : " +
                if (switchAt == 0L) "none this session"
                else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(switchAt)))
        }.onFailure { sb.appendLine("Active provider         : (unavailable: ${it.message})") }
        sb.appendLine("Mock location enabled   : ${runCatching { repo.mockLocationEnabled.first() }.getOrNull()}")
        runCatching { repo.gnssReceiverSettings.first() }.getOrNull()?.let {
            sb.appendLine("Internal high-accuracy  : ${it.highAccuracy}")
        }
        sb.appendLine()

        // ── External receiver (host/port OK to include) ────────────────────────────
        sb.appendLine("--- External receiver ---")
        val host = runCatching { repo.externalTcpHost.first() }.getOrNull()
        val port = runCatching { repo.externalTcpPort.first() }.getOrNull()
        val name = runCatching { repo.externalTcpName.first() }.getOrNull()
        val connType = runCatching { repo.externalConnType.first() }.getOrNull()
        val profile = runCatching { repo.externalReceiverProfile.first() }.getOrNull()
        sb.appendLine("Receiver profile        : ${profile?.label ?: "unknown"}")
        sb.appendLine("Receiver label/name     : ${name?.takeIf { it.isNotBlank() } ?: "not set"}")
        sb.appendLine("Connection type         : ${connType?.name ?: "unknown"}")
        sb.appendLine("Host / IP               : ${host?.takeIf { it.isNotBlank() } ?: "not configured"}")
        sb.appendLine("Port                    : ${port ?: "not configured"}")
        sb.appendLine()

        // ── GNSS capture / averaging ───────────────────────────────────────────────
        sb.appendLine("--- GNSS capture / averaging policy ---")
        runCatching { repo.gnssCaptureSettings.first() }.getOrNull()?.let { c ->
            sb.appendLine("Required RTK status     : ${c.requiredMinStatus}")
            sb.appendLine("Min sampling time (s)   : ${c.minDurationSec}")
            sb.appendLine("Max sampling time (s)   : ${c.maxDurationSec}")
            sb.appendLine("Min accepted fixes      : ${c.minSamples}")
            sb.appendLine("Max fix age (s)         : ${c.maxFixAgeSec}")
            sb.appendLine("Max correction age (s)  : ${c.maxDiffAgeSec}")
        } ?: sb.appendLine("(capture settings unavailable)")
        sb.appendLine()

        // ── Coordinate display ─────────────────────────────────────────────────────
        sb.appendLine("--- Coordinate display ---")
        runCatching { repo.coordinateDisplaySettings.first() }.getOrNull()?.let { c ->
            sb.appendLine("Accuracy indicators     : ${c.showAccuracyIndicators}")
            sb.appendLine("Default name prefix     : ${c.defaultNamePrefix}")
            sb.appendLine("Auto-increment names    : ${c.autoIncrementNames}")
        }
        sb.appendLine("(Format/CRS/UTM toggles are not persisted in this build.)")
        sb.appendLine()

        // ── AR display ─────────────────────────────────────────────────────────────
        sb.appendLine("--- AR display ---")
        runCatching { repo.arDisplaySettings.first() }.getOrNull()?.let { ar ->
            sb.appendLine("Labels shown            : ${ar.showLabels}")
            sb.appendLine("Off-screen arrows       : ${ar.showOffscreenArrows}")
            sb.appendLine("AR debug overlay        : ${ar.showDebugOverlay}")
            sb.appendLine("AR debug tools          : ${ar.showArDebugTools}")
            sb.appendLine("Altitude mode           : ${ar.altitudeMode}")
            sb.appendLine("Model scale             : ${ar.modelScale}")
            sb.appendLine("Distance filter index   : ${ar.distanceFilterIndex}")
        }
        sb.appendLine()

        // ── Diagnostics ────────────────────────────────────────────────────────────
        sb.appendLine("--- Diagnostics ---")
        val logFiles = DiagnosticsLogger.logFiles()
        val totalKb = logFiles.sumOf { it.length() } / 1024
        sb.appendLine("Diagnostic log files    : ${logFiles.size} (${totalKb} KB total)")
        sb.appendLine("Raw NMEA logging        : not enabled (raw NMEA is never written to diagnostics)")
        return sb.toString()
    }

    private fun readMapsApiKey(context: Context): String? = try {
        val ai = context.packageManager.getApplicationInfo(
            context.packageName, android.content.pm.PackageManager.GET_META_DATA
        )
        ai.metaData?.getString("com.google.android.geo.API_KEY")
    } catch (_: Exception) { null }
}
