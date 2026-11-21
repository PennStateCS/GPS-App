package app.surrealar.util.diagnostics.report

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import androidx.core.content.ContextCompat
import app.surrealar.util.diagnostics.SystemEventsLogger
import java.io.File

/** Reads device-health and data-health inputs from the running app (read-only). */
object AppReportReaders {

    fun deviceHealth(context: Context): DeviceHealth {
        val app = context.applicationContext
        val battery = runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.takeIf { it > 0 } ?: 100
        val pm = app.getSystemService(PowerManager::class.java)
        val stat = runCatching { StatFs(app.filesDir.path) }.getOrNull()
        val mem = runCatching {
            ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }
        }.getOrNull()
        val lm = app.getSystemService(LocationManager::class.java)
        fun granted(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED
        val perms = buildMap {
            put("Fine location", granted(Manifest.permission.ACCESS_FINE_LOCATION))
            put("Coarse location", granted(Manifest.permission.ACCESS_COARSE_LOCATION))
            put("Camera", granted(Manifest.permission.CAMERA))
            if (Build.VERSION.SDK_INT >= 31) put("Bluetooth connect", granted(Manifest.permission.BLUETOOTH_CONNECT))
            if (Build.VERSION.SDK_INT >= 33) put("Notifications", granted(Manifest.permission.POST_NOTIFICATIONS))
        }
        return DeviceHealth(
            batteryPercent = if (level >= 0) level * 100 / scale else null,
            charging = battery?.let { it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 },
            powerSave = pm?.isPowerSaveMode,
            thermal = if (Build.VERSION.SDK_INT >= 29) pm?.currentThermalStatus?.let(SystemEventsLogger::thermalName) else null,
            storageFreeBytes = stat?.availableBytes, storageTotalBytes = stat?.totalBytes,
            appDataBytes = runCatching { dirSize(app.filesDir.parentFile ?: app.filesDir) + dirSize(app.cacheDir) }.getOrNull(),
            ramAvailBytes = mem?.availMem, ramTotalBytes = mem?.totalMem, lowMemory = mem?.lowMemory,
            network = runCatching { network(app) }.getOrNull(),
            gpsProvider = runCatching { lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull(),
            networkProvider = runCatching { lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrNull(),
            permissions = perms,
            batteryOptimizationIgnored = runCatching { pm?.isIgnoringBatteryOptimizations(app.packageName) }.getOrNull(),
            mockLocationApp = runCatching { mockLocationApp(app) }.getOrNull(),
        )
    }

    private fun network(app: Context): String {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "none"
        val type = SystemEventsLogger.transportName(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR), caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        return "$type validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
    }

    /** Whether this app is the developer-options mock location app. */
    private fun mockLocationApp(app: Context): String {
        val ops = app.getSystemService(AppOpsManager::class.java) ?: return "unknown"
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), app.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), app.packageName)
        return if (mode == AppOpsManager.MODE_ALLOWED) "this app (SurReal AR)" else "not this app"
    }

    private fun dirSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun dataHealth(context: Context): DataHealth {
        val db = app.surrealar.data.local.db.AppDatabase.getDatabase(context).openHelper.readableDatabase
        val tables = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c -> while (c.moveToNext()) tables += c.getString(0) }
        val counts = tables.filterNot { it.startsWith("sqlite_") || it == "android_metadata" || it == "room_master_table" }
            .associateWith { t -> db.query("SELECT COUNT(*) FROM `$t`").use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } }
        val integrity = runCatching {
            db.query("PRAGMA quick_check").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) }.joinToString("; ") }
        }.getOrElse { "failed: ${it.message}" }
        return DataHealth(db.version, counts, integrity)
    }
}