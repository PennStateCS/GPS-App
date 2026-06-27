package app.surrealar.util.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import app.surrealar.BuildConfig
import app.surrealar.SurveyingApp
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import java.security.MessageDigest

/**
 * Read-only, side-effect-free collector that assembles a human-readable "Map Troubleshooting"
 * block for the diagnostic report. It surfaces everything needed to diagnose a blank Google Map
 * from a tester's report alone — most importantly the signing SHA-1 + package name that must match
 * the Maps API key's Android restriction.
 *
 * Never prints the raw API key, private keys, keystore paths, or live coordinates.
 */
object MapDiagnosticCollector {

    fun collect(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("=== Map Troubleshooting ===")
        sb.appendLine("If the map is blank, the most common cause is the Maps API key's Android")
        sb.appendLine("restriction not allowing this app's package + signing SHA-1. Compare the values")
        sb.appendLine("below against APIs & Services → Credentials → (Maps key) → Android apps.")
        sb.appendLine()

        // --- Build / identity ---
        sb.appendLine("--- App identity ---")
        sb.appendLine("Package / applicationId : ${context.packageName}")
        sb.appendLine("Version                 : ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}, build ${BuildConfig.BUILD_NUMBER})")
        sb.appendLine("Build type              : ${if (BuildConfig.DEBUG) "DEBUG" else "RELEASE"}")
        sb.appendLine("Git / built             : ${BuildConfig.BUILD_GIT_HASH}${if (BuildConfig.BUILD_GIT_DIRTY) "-dirty" else ""} @ ${BuildConfig.BUILD_TIME}")
        sb.appendLine("Installer source        : ${installerSource(context)}")
        sb.appendLine()

        // --- Signing (the values to allow in Google Cloud Console) ---
        sb.appendLine("--- Signing certificate (register these in the Maps key Android restriction) ---")
        val fp = AppSigningInfo.fingerprints(context)
        if (fp != null) {
            sb.appendLine("SHA-1                   : ${fp.sha1}")
            sb.appendLine("SHA-256                 : ${fp.sha256}")
            sb.appendLine("Cloud Console entry     : package=${context.packageName}, SHA-1=${fp.sha1}")
        } else {
            sb.appendLine("SHA-1 / SHA-256         : (could not read signing certificate)")
        }
        sb.appendLine()

        // --- Maps API key (presence only, never the value) ---
        sb.appendLine("--- Maps API key ---")
        sb.appendLine("Key metadata            : ${redactApiKey(readMapsApiKey(context))}")
        sb.appendLine()

        // --- Google Play Services ---
        sb.appendLine("--- Google Play Services ---")
        sb.appendLine("Availability            : ${playServicesStatus(context)}")
        sb.appendLine("Installed GMS version   : ${gmsVersion(context)}")
        sb.appendLine("Maps renderer (active)  : ${SurveyingApp.activeMapsRenderer}")
        sb.appendLine()

        // --- Network ---
        sb.appendLine("--- Network ---")
        sb.appendLine(networkSummary(context))
        sb.appendLine()

        // --- Map lifecycle / runtime ---
        sb.appendLine("--- Map runtime ---")
        sb.appendLine("Last map load status    : ${SurveyingApp.mapLoadStatus}")
        sb.appendLine("  (MAP_READY = map object ready; MAP_LOADED = tiles drawn;")
        sb.appendLine("   'MAP_READY but MAP_LOADED did not fire' = tiles never rendered → usually auth/key/network)")
        sb.appendLine()

        // --- Map display state (non-sensitive: counts/modes/booleans only, never coordinates) ---
        sb.appendLine("--- Map display state ---")
        sb.appendLine(MapRuntimeDiagnostics.snapshot.format())
        sb.appendLine()

        // --- Permissions ---
        sb.appendLine("--- Permissions ---")
        sb.appendLine("Fine location           : ${permissionGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)}")
        sb.appendLine("Coarse location         : ${permissionGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)}")
        return sb.toString()
    }

    // ── pure helpers (unit-tested) ───────────────────────────────────────────────

    /** Reports API-key presence WITHOUT exposing it: blank/placeholder, or length + short sha256. */
    fun redactApiKey(key: String?): String = when {
        key.isNullOrBlank() -> "MISSING / blank"
        key == "YOUR_API_KEY_HERE" || key == "YOUR_GOOGLE_MAPS_API_KEY_HERE" -> "PLACEHOLDER (not a real key)"
        else -> "present (len=${key.length}, sha256=${shortHash(key)})"
    }

    /** First 8 hex chars of the value's SHA-256 — lets us confirm WHICH key without revealing it. */
    fun shortHash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(8)

    /** Formats network capabilities; flags internet-without-validation (likely receiver Wi-Fi). */
    fun formatNetwork(present: Boolean, internet: Boolean, validated: Boolean, transport: String): String {
        val sb = StringBuilder()
        sb.appendLine("Active network          : ${if (present) "yes" else "none"}")
        sb.appendLine("Transport               : $transport")
        sb.appendLine("NET_CAPABILITY_INTERNET : $internet")
        sb.append("NET_CAPABILITY_VALIDATED: $validated")
        if (internet && !validated) {
            sb.append("\nWARNING: connected but not validated — likely a receiver Wi-Fi with no real internet; map tiles will not load.")
        }
        return sb.toString()
    }

    // ── Android-backed gatherers ─────────────────────────────────────────────────

    private fun networkSummary(context: Context): String = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = cm?.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val transport = when {
            net == null -> "none"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> "other/unknown"
        }
        formatNetwork(present = net != null, internet = internet, validated = validated, transport = transport)
    } catch (e: Exception) {
        "(network state unavailable: ${e.message})"
    }

    private fun readMapsApiKey(context: Context): String? = try {
        val ai = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        ai.metaData?.getString("com.google.android.geo.API_KEY")
    } catch (_: Exception) { null }

    private fun playServicesStatus(context: Context): String = try {
        when (val code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)) {
            ConnectionResult.SUCCESS -> "SUCCESS"
            ConnectionResult.SERVICE_MISSING -> "SERVICE_MISSING"
            ConnectionResult.SERVICE_UPDATING -> "SERVICE_UPDATING"
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED -> "UPDATE_REQUIRED"
            ConnectionResult.SERVICE_DISABLED -> "SERVICE_DISABLED"
            ConnectionResult.SERVICE_INVALID -> "SERVICE_INVALID"
            else -> "code=$code"
        }
    } catch (e: Exception) { "(unavailable: ${e.message})" }

    private fun gmsVersion(context: Context): String = try {
        context.packageManager.getPackageInfo("com.google.android.gms", 0).versionName ?: "unknown"
    } catch (_: Exception) { "not installed" }

    private fun installerSource(context: Context): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName ?: "unknown"
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName) ?: "unknown"
        }
    } catch (_: Exception) { "unknown" }

    private fun permissionGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
