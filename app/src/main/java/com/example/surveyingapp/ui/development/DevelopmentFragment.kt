package com.example.surveyingapp.ui.development

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.surveyingapp.R
import com.example.surveyingapp.ui.common.BaseTwoPaneFragment
import com.example.surveyingapp.ui.settings.SettingsCategory
import com.example.surveyingapp.ui.settings.SettingsFragment
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.Config

class DevelopmentFragment : BaseTwoPaneFragment() {

    // Developer categories: lightweight side list for specialized debug panes.
    // Add/remove here to extend; IDs must remain stable for state restoration.
    private val devCategories = listOf(
        SettingsCategory(1, "System Info", R.drawable.ic_home),
        SettingsCategory(2, "Permissions", R.drawable.ic_section_location),
        SettingsCategory(3, "AR Debug", R.drawable.ic_dev_tools)
    )

    override fun provideCategories(): List<SettingsCategory> = devCategories

    override fun buildCategoryContent(category: SettingsCategory, inflater: LayoutInflater): View? = when (category.id) {
        1 -> setupSystemInfoContent()    // App & device/runtime diagnostics
        2 -> setupPermissionsContent()   // Manifest + grant snapshot (static read; no live observer)
        3 -> setupArDebugContent()       // ARCore capability probe (no camera start)
        else -> null
    }

    private fun setupPermissionsContent(): View {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            // Title intentionally omitted per prior request.
            addView(createPermissionsTable())
        }
    }

    private fun createPermissionsTable(): View {
        val ctx = requireContext()
        val pm = ctx.packageManager
        val pkg = ctx.packageName
        // NOTE: getPackageInfo + GET_PERMISSIONS is deprecated in API 33+ in favor of PackageManager.PackageInfoFlags; acceptable here for dev-only screen.
        return try {
            val info = pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_PERMISSIONS)
            val perms = info.requestedPermissions
            if (perms != null && perms.isNotEmpty()) {
                createPermissionsTableLayout(perms, pm, pkg)
            } else TextView(ctx).apply {
                setText(R.string.dev_perm_none)
                textSize = 14f
                setPadding(8, 0, 0, 16)
            }
        } catch (e: Exception) {
            TextView(ctx).apply {
                text = getString(R.string.dev_perm_error, e.message ?: "")
                textSize = 14f
                setPadding(8, 0, 0, 16)
            }
        }
    }

    private fun createPermissionsTableLayout(permissions: Array<String>, pm: android.content.pm.PackageManager, packageName: String): View {
        val ctx = requireContext()
        val table = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        // Summary row
        table.addView(TextView(ctx).apply {
            text = getString(R.string.dev_perm_total, permissions.size)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.dev_info_label))
            setPadding(0, 0, 0, 16)
        })
        // Header definition helper
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 12, 12, 12)
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.dev_perm_header_bg))
        }
        fun headerCell(txt: Int, weight: Float) = TextView(ctx).apply {
            setText(txt)
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.dev_perm_header_text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            setPadding(4, 0, 4, 0)
        }
        header.addView(headerCell(R.string.dev_perm_status, 0.8f))
        header.addView(headerCell(R.string.dev_perm_name, 1.6f))
        header.addView(headerCell(R.string.dev_perm_full_path, 2.6f))
        table.addView(header)
        // Data rows
        permissions.forEachIndexed { idx, perm ->
            val granted = pm.checkPermission(perm, packageName) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(12, 8, 12, 8)
                setBackgroundColor(ContextCompat.getColor(ctx, if (idx % 2 == 0) R.color.dev_perm_row_even else R.color.dev_perm_row_odd))
            }
            fun cell(text: String, weight: Float, size: Float, center: Boolean = false, color: Int? = null, bold: Boolean = false) = TextView(ctx).apply {
                this.text = text
                textSize = size
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
                setPadding(4, 2, 4, 2)
                if (center) gravity = android.view.Gravity.CENTER
                setTextIsSelectable(true) // Allow copy for debugging
                color?.let { setTextColor(ContextCompat.getColor(ctx, it)) } ?: setTextColor(ContextCompat.getColor(ctx, R.color.dev_info_label))
                if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            }
            row.addView(cell(if (granted) "GRNT" else "DENY", 0.8f, 11f, true, if (granted) R.color.dev_perm_status_granted else R.color.dev_perm_status_denied, bold = true))
            row.addView(cell(perm.substringAfterLast('.'), 1.6f, 12f, false, null, true))
            row.addView(cell(perm, 2.6f, 11f, false, null, false).apply { ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; maxLines = 2 })
            table.addView(row)
            if (idx < permissions.lastIndex) { // Divider between rows for readability
                table.addView(View(ctx).apply {
                    setBackgroundColor(ContextCompat.getColor(ctx, R.color.dev_perm_row_divider))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                })
            }
        }
        return table
    }

    private fun setupSystemInfoContent(): View {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            // Optional themed background could be added here.
        }
        fun sectionHeader(titleRes: Int) = TextView(ctx).apply {
            text = getString(titleRes)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.dev_info_label))
            setPadding(0, 24, 0, 12)
        }
        // Package/application metadata
        val pm = ctx.packageManager
        val packageName = ctx.packageName
        val pkgInfo = runCatching { pm.getPackageInfo(packageName, 0) }.getOrNull()
        val appInfo = pkgInfo?.applicationInfo
        val firstInstall = pkgInfo?.firstInstallTime ?: 0L
        val lastUpdate = pkgInfo?.lastUpdateTime ?: 0L
        val versionName = pkgInfo?.versionName ?: getString(R.string.dev_value_unknown)
        @Suppress("DEPRECATION")
        val versionCode = pkgInfo?.versionCode?.toString() ?: getString(R.string.dev_value_unknown)
        val targetSdk = appInfo?.targetSdkVersion?.toString() ?: getString(R.string.dev_value_unknown)
        val minSdk = appInfo?.minSdkVersion?.toString() ?: getString(R.string.dev_value_unknown)
        val debuggable = appInfo?.let { (it.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 } ?: false
        val systemApp = appInfo?.let { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 } ?: false
        val apkFile = appInfo?.sourceDir?.let { java.io.File(it) }
        val apkSize = apkFile?.length() ?: -1L
        // Device fingerprint subset
        val model = android.os.Build.MODEL ?: getString(R.string.dev_value_unknown)
        val manufacturer = android.os.Build.MANUFACTURER ?: getString(R.string.dev_value_unknown)
        val brand = android.os.Build.BRAND ?: getString(R.string.dev_value_unknown)
        val sdkInt = android.os.Build.VERSION.SDK_INT.toString()
        val androidVersion = android.os.Build.VERSION.RELEASE ?: getString(R.string.dev_value_unknown)
        val abis = android.os.Build.SUPPORTED_ABIS?.joinToString(", ") ?: getString(R.string.dev_value_unknown)
        // Runtime snapshot (heap + threads) – coarse, not for profiling accuracy
        val rt = Runtime.getRuntime()
        val heapUsed = rt.totalMemory() - rt.freeMemory()
        val heapFree = rt.freeMemory()
        val heapMax = rt.maxMemory()
        val threadCount = Thread.getAllStackTraces().keys.size
        // Storage basics (internal app storage)
        val filesDir = ctx.filesDir
        val stat = runCatching { android.os.StatFs(filesDir.path) }.getOrNull()
        val blkSize = stat?.blockSizeLong ?: 1L
        val totalBlocks = stat?.blockCountLong ?: 0L
        val availBlocks = stat?.availableBlocksLong ?: 0L
        val internalTotal = blkSize * totalBlocks
        val internalFree = blkSize * availBlocks
        // Process naming (Android P+ helper or fallback)
        val processName = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                val procInfo = android.app.ActivityManager.RunningAppProcessInfo()
                android.app.ActivityManager.getMyMemoryState(procInfo)
                procInfo.processName ?: packageName
            } else packageName
        }.getOrElse { getString(R.string.dev_value_unknown) }
        // App info section
        container.addView(sectionHeader(R.string.dev_section_app_info))
        val appTable = buildInfoTable(ctx).apply {
            addRow(ctx.getString(R.string.dev_label_package_name), packageName, 0)
            addRow(ctx.getString(R.string.dev_label_version_name), versionName, 1)
            addRow(ctx.getString(R.string.dev_label_version_code), versionCode, 2)
            addRow(ctx.getString(R.string.dev_label_first_install), formatTime(firstInstall), 3)
            addRow(ctx.getString(R.string.dev_label_last_update), formatTime(lastUpdate), 4)
            addRow(ctx.getString(R.string.dev_label_target_sdk), targetSdk, 5)
            addRow(ctx.getString(R.string.dev_label_min_sdk), minSdk, 6)
            addRow(ctx.getString(R.string.dev_label_debuggable), debuggable.toString(), 7)
            addRow(ctx.getString(R.string.dev_label_system_app), systemApp.toString(), 8)
            addRow(ctx.getString(R.string.dev_label_app_size), if (apkSize >= 0) formatBytes(apkSize) else getString(R.string.dev_value_unknown), 9)
            addRow(ctx.getString(R.string.dev_label_source_dir), appInfo?.sourceDir ?: getString(R.string.dev_value_unknown), 10)
            addRow(ctx.getString(R.string.dev_label_data_dir), appInfo?.dataDir ?: getString(R.string.dev_value_unknown), 11)
        }
        container.addView(appTable)
        // Device/runtime section
        container.addView(sectionHeader(R.string.dev_section_device_info))
        val deviceTable = buildInfoTable(ctx).apply {
            addRow(ctx.getString(R.string.dev_label_android_version), androidVersion, 0)
            addRow(ctx.getString(R.string.dev_label_sdk_int), sdkInt, 1)
            addRow(ctx.getString(R.string.dev_label_device_model), model, 2)
            addRow(ctx.getString(R.string.dev_label_manufacturer), manufacturer, 3)
            addRow(ctx.getString(R.string.dev_label_brand), brand, 4)
            addRow(ctx.getString(R.string.dev_label_abis), abis, 5)
            addRow(ctx.getString(R.string.dev_label_process_name), processName, 6)
            addRow(ctx.getString(R.string.dev_label_runtime_threads), threadCount.toString(), 7)
            addRow(ctx.getString(R.string.dev_label_heap_used), formatBytes(heapUsed), 8)
            addRow(ctx.getString(R.string.dev_label_heap_free), formatBytes(heapFree), 9)
            addRow(ctx.getString(R.string.dev_label_heap_max), formatBytes(heapMax), 10)
            addRow(ctx.getString(R.string.dev_label_internal_free), formatBytes(internalFree), 11)
            addRow(ctx.getString(R.string.dev_label_internal_total), formatBytes(internalTotal), 12)
        }
        container.addView(deviceTable)
        return container
    }

    private fun setupArDebugContent(): View {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        val infoTable = buildInfoTable(ctx)
        fun add(label: String, value: String, idx: Int) = infoTable.addRow(label, value, idx)
        // Collect ARCore capability snapshot (no camera permission or session resume invoked here).
        fun gather(): List<Pair<String,String>> {
            val list = mutableListOf<Pair<String,String>>()
            val availability = runCatching { ArCoreApk.getInstance().checkAvailability(ctx).toString() }.getOrElse { it.javaClass.simpleName }
            list += "ARCore Availability" to availability
            var session: Session? = null
            val sessionResult = runCatching {
                session = Session(ctx) // Will fail if ARCore services missing/outdated.
                val s = session
                val cfg = Config(s).apply {
                    if (s.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) geospatialMode = Config.GeospatialMode.ENABLED
                }
                s.configure(cfg)
                "OK"
            }.getOrElse { e -> e.message ?: e.javaClass.simpleName }
            list += "Session Create" to sessionResult
            if (session != null) {
                val s = session
                val geospatialSupported = runCatching { s.isGeospatialModeSupported(Config.GeospatialMode.ENABLED) }.getOrDefault(false)
                val depthSupported = runCatching { s.isDepthModeSupported(Config.DepthMode.AUTOMATIC) }.getOrDefault(false)
                val instantPlacement = runCatching {
                    val testCfg = Config(s)
                    testCfg.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                    s.configure(testCfg) // Reconfig just to probe support
                    true
                }.getOrDefault(false)
                list += "Geospatial Supported" to geospatialSupported.toString()
                list += "Depth Supported" to depthSupported.toString()
                list += "Instant Placement Supported" to instantPlacement.toString()
                list += "Earth Tracking" to "(Session not resumed)" // Not resumed: no camera usage for quick diagnostics
            }
            // Permissions snapshot (not requesting here – purely informative)
            val camGranted = ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val locGranted = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            list += "Camera Permission" to camGranted.toString()
            list += "Location Permission" to locGranted.toString()
            list += "Device Model" to android.os.Build.MODEL
            list += "Android SDK" to android.os.Build.VERSION.SDK_INT.toString()
            runCatching { session?.close() } // Cleanup
            return list
        }
        fun populate() {
            infoTable.removeAllViews() // Clear stale rows before repopulating
            val data = gather()
            data.forEachIndexed { idx, pair -> add(pair.first, pair.second, idx) }
        }
        populate() // Initial load
        val refresh = androidx.appcompat.widget.AppCompatButton(ctx).apply {
            setText(R.string.dev_refresh)
            setOnClickListener { populate() } // Manual refresh triggers capability re-probe
        }
        container.addView(refresh, 0)
        container.addView(infoTable)
        return container
    }

    // Helper: root table container builder
    private fun buildInfoTable(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ContextCompat.getColor(ctx, R.color.dev_info_section_bg))
        elevation = 1f // Subtle elevation for separation
    }

    private fun LinearLayout.addRow(label: String, value: String, index: Int) {
        val ctx = context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 8, 12, 8)
            if (index % 2 == 1) setBackgroundColor(ContextCompat.getColor(ctx, R.color.dev_info_row_alt))
        }
        val labelView = TextView(ctx).apply {
            text = label
            setTextColor(ContextCompat.getColor(ctx, R.color.dev_info_label))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }
        val valueView = TextView(ctx).apply {
            text = value
            setTextColor(ContextCompat.getColor(ctx, R.color.dev_info_value))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
            setTextIsSelectable(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxLines = 4
        }
        row.addView(labelView)
        row.addView(valueView)
        addView(row)
        // Divider between rows (visual grouping)
        val divider = View(ctx).apply {
            setBackgroundColor(0x14000000)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
        addView(divider)
    }

    private fun formatTime(ts: Long): String {
        if (ts <= 0) return getString(R.string.dev_value_unknown)
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(ts))
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return getString(R.string.dev_value_unknown)
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble() / 1024.0
        var idx = 0
        while (v >= 1024 && idx < units.lastIndex) { v /= 1024; idx++ }
        return String.format(java.util.Locale.US, "%.2f %s", v, units[idx])
    }
}
