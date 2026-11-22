package app.surrealar.util.diagnostics

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.surrealar.util.DiagnosticsLogger
import java.security.MessageDigest
import java.util.TimeZone

/**
 * SYSTEM events: network type and Wi-Fi (SSID hashed), battery every 10 %, charging, power save,
 * thermal status, location permission and providers, and time / time-zone changes. Everything goes
 * through [DiagnosticsLogger.logOnChange], so repeats cost nothing.
 */
object SystemEventsLogger {

    private const val TAG = DiagTags.SYSTEM

    /** 0..100 → the 10 % step it falls in (57 → 50). */
    fun batteryBucket(percent: Int): Int = (percent.coerceIn(0, 100) / 10) * 10

    /** First 8 hex chars of SHA-256 of the SSID (quotes stripped); "unknown" when Android hides it. */
    fun hashSsid(ssid: String?): String {
        val s = ssid?.trim()?.removePrefix("\"")?.removeSuffix("\"")
        if (s.isNullOrEmpty() || s == "<unknown ssid>") return "unknown"
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return d.take(4).joinToString("") { "%02x".format(it) }
    }

    fun transportName(wifi: Boolean, cellular: Boolean, ethernet: Boolean, vpn: Boolean): String = when {
        vpn -> "VPN"; wifi -> "Wi-Fi"; cellular -> "cellular"; ethernet -> "ethernet"; else -> "other"
    }

    fun thermalName(status: Int): String = when (status) {
        0 -> "NONE"; 1 -> "LIGHT"; 2 -> "MODERATE"; 3 -> "SEVERE"; 4 -> "CRITICAL"; 5 -> "EMERGENCY"; 6 -> "SHUTDOWN"
        else -> "STATUS_$status"
    }

    fun install(context: Context) {
        val app = context.applicationContext
        runCatching { watchNetwork(app) }.onFailure { DiagnosticsLogger.w(TAG, "Network watch unavailable", it) }
        runCatching { watchBroadcasts(app) }.onFailure { DiagnosticsLogger.w(TAG, "System broadcasts unavailable", it) }
        runCatching { watchThermal(app) }.onFailure { DiagnosticsLogger.w(TAG, "Thermal status watch unavailable", it) }
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                // Permissions can only change while the app is away, so re-check on every return.
                override fun onStart(owner: LifecycleOwner) = logPermissions(app)
            })
        }
    }

    private fun watchNetwork(app: Context) {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val type = transportName(wifi, caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET), caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                DiagnosticsLogger.logOnChange(TAG, "sys.net", "$type/$validated") {
                    "Network $type validated=$validated" + if (!validated) " (no internet: receiver Wi-Fi?)" else ""
                }
                val ssid = if (wifi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    hashSsid((caps.transportInfo as? WifiInfo)?.ssid) else null
                DiagnosticsLogger.logOnChange(TAG, "sys.wifi", if (wifi) "on:$ssid" else "off") {
                    if (wifi) "Wi-Fi connected ssid#${ssid ?: "unknown"}" else "Wi-Fi disconnected"
                }
            }
            override fun onLost(network: Network) {
                DiagnosticsLogger.logOnChange(TAG, "sys.net", "none") { "Network lost (no default network)" }
                DiagnosticsLogger.logOnChange(TAG, "sys.wifi", "off") { "Wi-Fi disconnected" }
            }
        })
    }

    private fun watchBroadcasts(app: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                runCatching {
                    when (intent.action) {
                        Intent.ACTION_BATTERY_CHANGED -> onBattery(intent)
                        PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> logPowerSave(app)
                        LocationManager.PROVIDERS_CHANGED_ACTION -> logProviders(app)
                        Intent.ACTION_TIME_CHANGED -> DiagnosticsLogger.w(TAG, "Device time was changed (wall clock jump)")
                        Intent.ACTION_TIMEZONE_CHANGED -> DiagnosticsLogger.w(TAG,
                            "Time zone changed to ${intent.getStringExtra("time-zone") ?: TimeZone.getDefault().id}")
                    }
                }.onFailure {
                    // Battery broadcasts arrive often; one line a minute is enough to see a repeat failure.
                    DiagnosticsLogger.every(TAG, "sys.broadcast.fail", 60_000L, 'W', it) { "System broadcast handling failed action=${intent.action}" }
                }
            }
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        logPowerSave(app)
        logProviders(app)
    }

    private fun onBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).takeIf { it > 0 } ?: 100
        if (level >= 0) {
            val pct = level * 100 / scale
            DiagnosticsLogger.logOnChange(TAG, "sys.battery", batteryBucket(pct)) { "Battery $pct%" }
        }
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        DiagnosticsLogger.logOnChange(TAG, "sys.charging", plugged != 0) {
            if (plugged != 0) "Charging (${when (plugged) { 1 -> "AC"; 2 -> "USB"; 4 -> "wireless"; else -> "plugged" }})" else "Not charging"
        }
    }

    private fun logPowerSave(app: Context) {
        val on = app.getSystemService(PowerManager::class.java)?.isPowerSaveMode ?: return
        DiagnosticsLogger.logOnChange(TAG, "sys.powersave", on) { "Power save ${if (on) "on" else "off"}" }
    }

    private fun watchThermal(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = app.getSystemService(PowerManager::class.java) ?: return
        pm.addThermalStatusListener(ContextCompat.getMainExecutor(app)) { status ->
            DiagnosticsLogger.logOnChange(TAG, "sys.thermal", status, if (status >= 3) 'W' else 'I') { "Thermal status ${thermalName(status)}" }
        }
    }

    private fun logProviders(app: Context) {
        val lm = app.getSystemService(LocationManager::class.java) ?: return
        val gps = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val net = runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        DiagnosticsLogger.logOnChange(TAG, "sys.providers", "$gps/$net", if (!gps) 'W' else 'I') {
            "Location providers gps=${if (gps) "on" else "off"} network=${if (net) "on" else "off"}"
        }
    }

    private fun logPermissions(app: Context) {
        fun granted(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        DiagnosticsLogger.logOnChange(TAG, "sys.locperm", "$fine/$coarse", if (!fine) 'W' else 'I') {
            "Location permission fine=${if (fine) "granted" else "denied"} coarse=${if (coarse) "granted" else "denied"}"
        }
        logProviders(app)
    }
}