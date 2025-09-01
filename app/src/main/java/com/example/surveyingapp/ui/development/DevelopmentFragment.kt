package com.example.surveyingapp.ui.development

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.surveyingapp.R
import com.example.surveyingapp.data.local.db.AppDatabase
import com.example.surveyingapp.data.repository.impl.CoordinateRepositoryImpl
import com.example.surveyingapp.domain.model.Coordinate
import com.example.surveyingapp.ui.common.BaseTwoPaneFragment
import com.example.surveyingapp.ui.settings.SettingsCategory
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import android.os.Handler
import android.os.Looper
import androidx.appcompat.widget.AppCompatImageButton
import android.util.TypedValue
import android.view.ViewGroup
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.*

class DevelopmentFragment : BaseTwoPaneFragment() {

    // Developer categories: lightweight side list for specialized debug panes.
    // Add/remove here to extend; IDs must remain stable for state restoration.
    private val devCategories = listOf(
        SettingsCategory(1, "System Info", R.drawable.ic_section_info),
        SettingsCategory(2, "Permissions", R.drawable.ic_section_location),
        SettingsCategory(3, "AR Debug", R.drawable.ic_dev_tools),
        SettingsCategory(4, "Maps Debug", R.drawable.ic_map),
        SettingsCategory(5, "Coordinates", R.drawable.ic_section_location)
    )

    private lateinit var coordinateRepository: CoordinateRepositoryImpl

    // Permission request launcher for core permissions
    private val corePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        val message = if (granted) {
            "All core permissions granted!"
        } else {
            val denied = permissions.filterValues { !it }.keys
            "Permissions denied: ${denied.joinToString(", ") { it.substringAfterLast('.') }}"
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        // Refresh the permissions table to show updated status
        refreshPermissionsTable()
    }

    private var permissionsTableHolder: LinearLayout? = null

    override fun onRootCreated(root: View) {
        // Initialize repository for coordinates operations
        coordinateRepository = CoordinateRepositoryImpl(AppDatabase.getDatabase(requireContext()).coordinateDao())
    }

    override fun provideCategories(): List<SettingsCategory> = devCategories

    override fun buildCategoryContent(category: SettingsCategory, inflater: LayoutInflater): View? = when (category.id) {
        1 -> setupSystemInfoContent()    // App & device/runtime diagnostics
        2 -> setupPermissionsContent()   // Manifest + grant snapshot (static read; no live observer)
        3 -> setupArDebugContent()       // ARCore capability probe (no camera start)
        4 -> setupMapsDebugContent()     // Google Maps / Play Services debug
        5 -> setupCoordinatesDevContent() // Coordinate generation and management
        else -> null
    }

    // Helper to create a right‑aligned refresh icon bar reused across dev panes.
    private fun createRefreshBar(onClick: () -> Unit): View {
        val ctx = requireContext()
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, dpToPx(4f))
        }
        val spacer = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        val refreshBtn = AppCompatImageButton(ctx).apply {
            setImageResource(R.drawable.ic_refresh_24)
            contentDescription = getString(R.string.dev_refresh)
            val out = TypedValue()
            if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)) {
                setBackgroundResource(out.resourceId)
            }
            val pad = dpToPx(8f)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { onClick() }
        }
        bar.addView(spacer)
        bar.addView(refreshBtn)
        return bar
    }

    private fun setupPermissionsContent(): View {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        val tableHolder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun rebuild() {
            tableHolder.removeAllViews()
            tableHolder.addView(createPermissionsTable())
        }
        // Replace text button with right‑aligned icon bar
        container.addView(createRefreshBar { rebuild() })
        container.addView(tableHolder)

        // Add core permissions request button
        val requestButton = androidx.appcompat.widget.AppCompatButton(ctx).apply {
            text = "Request Core Permissions"
            setPadding(dpToPx(16f), dpToPx(12f), dpToPx(16f), dpToPx(12f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(16f)
            }
            setOnClickListener {
                requestCorePermissions()
            }
        }
        container.addView(requestButton)

        rebuild()
        permissionsTableHolder = tableHolder // Track reference for refreshing
        return container
    }

    private fun requestCorePermissions() {
        val corePermissions = arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA
        )
        corePermissionsLauncher.launch(corePermissions)
    }

    private fun refreshPermissionsTable() {
        permissionsTableHolder?.let { holder ->
            holder.removeAllViews()
            holder.addView(createPermissionsTable())
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
        // Header
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 8, 12, 8)
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
        header.addView(headerCell(R.string.dev_perm_status, 0.7f))
        header.addView(headerCell(R.string.dev_perm_name, 1.2f))
        header.addView(headerCell(R.string.dev_perm_full_path, 3.1f))
        table.addView(header)

        permissions.forEachIndexed { idx, perm ->
            val granted = pm.checkPermission(perm, packageName) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(12, 6, 12, 6)
                if (idx % 2 == 1) setBackgroundColor(ContextCompat.getColor(ctx, R.color.dev_info_row_alt))
            }
            fun cell(weight: Float) = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
                textSize = 12f
                setPadding(4, 2, 4, 2)
                setTextIsSelectable(true)
                setTextColor(ContextCompat.getColor(ctx, R.color.dev_info_value))
            }
            val statusView = cell(0.7f).apply {
                text = if (granted) "GRNT" else "DENY"
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor((if (granted) 0xFF2E7D32 else 0xFFC62828).toInt())
                // Align left (start) instead of centered
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                textSize = 11f
            }
            val shortName = perm.substringAfterLast('.')
            val nameView = cell(1.2f).apply {
                text = shortName
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.dev_info_label))
            }
            val fullView = cell(3.1f).apply {
                text = perm
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                maxLines = 2
            }
            row.addView(statusView)
            row.addView(nameView)
            row.addView(fullView)
            table.addView(row)
            // Divider
            if (idx < permissions.lastIndex) table.addView(View(ctx).apply {
                setBackgroundColor(0x14000000)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
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
        // Populate table using gather()
        fun populate() {
            infoTable.removeAllViews()
            val data = gather()
            data.forEachIndexed { idx, pair -> add(pair.first, pair.second, idx) }
        }
        populate()
        // Replace text button with right‑aligned icon bar
        container.addView(createRefreshBar { populate() }, 0)
        container.addView(infoTable)
        return container
    }

    private fun setupMapsDebugContent(): View {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        // Table container (we'll build custom rows for color coding)
        val table = buildInfoTable(ctx)
        var rowIndex = 0
        fun addRow(label: String, value: String, status: MapsStatus? = null) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(12, 8, 12, 8)
                if (rowIndex % 2 == 1) setBackgroundColor(ContextCompat.getColor(ctx, R.color.dev_info_row_alt))
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
                textSize = 13f
                setTextIsSelectable(true)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
                maxLines = 4
                ellipsize = android.text.TextUtils.TruncateAt.END
                val color = when (status) {
                    MapsStatus.OK -> 0xFF2E7D32.toInt() // green
                    MapsStatus.WARN -> 0xFFF9A825.toInt() // amber
                    MapsStatus.ERROR -> 0xFFC62828.toInt() // red
                    null -> ContextCompat.getColor(ctx, R.color.dev_info_value)
                }
                setTextColor(color)
            }
            row.addView(labelView)
            row.addView(valueView)
            table.addView(row)
            table.addView(View(ctx).apply {
                setBackgroundColor(0x14000000)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
            rowIndex++
        }
        // Remove local enum definition, use MapsStatus

        // Gather statuses
        val availabilityCode = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx)
        val availabilityText = when (availabilityCode) {
            ConnectionResult.SUCCESS -> "SUCCESS" to MapsStatus.OK
            ConnectionResult.SERVICE_MISSING -> "MISSING" to MapsStatus.ERROR
            ConnectionResult.SERVICE_UPDATING -> "UPDATING" to MapsStatus.WARN
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED -> "UPDATE_REQUIRED" to MapsStatus.WARN
            ConnectionResult.SERVICE_DISABLED -> "DISABLED" to MapsStatus.ERROR
            ConnectionResult.SERVICE_INVALID -> "INVALID" to MapsStatus.ERROR
            else -> ("CODE_$availabilityCode") to MapsStatus.WARN
        }
        val playPkgInfo = runCatching { ctx.packageManager.getPackageInfo("com.google.android.gms", 0) }.getOrNull()
        val playServicesVersion = playPkgInfo?.versionName ?: "?"
        val playServicesUpdated = playPkgInfo?.lastUpdateTime ?: 0L
        val apiKeyFull = runCatching {
            val ai = ctx.packageManager.getApplicationInfo(ctx.packageName, android.content.pm.PackageManager.GET_META_DATA)
            ai.metaData?.getString("com.google.android.geo.API_KEY") ?: "(not set)"
        }.getOrElse { "(error: ${it.message})" }
        val apiKeyStatus = when {
            apiKeyFull == "(not set)" -> MapsStatus.ERROR
            apiKeyFull.equals("YOUR_API_KEY_HERE", true) -> MapsStatus.WARN
            apiKeyFull.startsWith("AIza") && apiKeyFull.length > 30 -> MapsStatus.OK
            else -> MapsStatus.WARN
        }
        val mapsInitResult = runCatching { MapsInitializer.initialize(ctx); "OK" }.getOrElse { it.message ?: it.javaClass.simpleName }
        val mapsInitStatus = if (mapsInitResult == "OK") MapsStatus.OK else MapsStatus.ERROR
        val locationPerm = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val internetPerm = ctx.checkSelfPermission(android.Manifest.permission.INTERNET) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val cameraPerm = ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val networkStatus = runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val active = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(active)
            when {
                active == null -> "NO_ACTIVE_NETWORK" to MapsStatus.ERROR
                caps == null -> "NO_CAPS" to MapsStatus.WARN
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) -> {
                    val tag = when {
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "INTERNET_WIFI"
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "INTERNET_CELL"
                        else -> "INTERNET"
                    }
                    tag to MapsStatus.OK
                }
                else -> "NO_INTERNET_CAP" to MapsStatus.ERROR
            }
        }.getOrElse { it.javaClass.simpleName to MapsStatus.WARN }
        val mapsLibPresent = runCatching { Class.forName("com.google.android.gms.maps.GoogleMap"); true }.getOrDefault(false)
        val fusedLocPresent = runCatching { Class.forName("com.google.android.gms.location.FusedLocationProviderClient"); true }.getOrDefault(false)

        // Populate rows (color-coded)
        addRow("Play Services Availability", availabilityText.first, availabilityText.second)
        addRow("Play Services Version", playServicesVersion, if (availabilityText.second == MapsStatus.OK) MapsStatus.OK else availabilityText.second)
        addRow("Play Services Last Update", if (playServicesUpdated>0) formatTime(playServicesUpdated) else "--", if (playServicesUpdated>0) MapsStatus.OK else MapsStatus.WARN)
        addRow("Maps Library Present", mapsLibPresent.toString(), if (mapsLibPresent) MapsStatus.OK else MapsStatus.ERROR)
        addRow("Fused Location Present", fusedLocPresent.toString(), if (fusedLocPresent) MapsStatus.OK else MapsStatus.WARN)
        addRow("Maps Initialize", mapsInitResult, mapsInitStatus)
        val apiKeyDisplay = if (apiKeyFull.length>12) apiKeyFull.take(8) + "…" + apiKeyFull.takeLast(4) else apiKeyFull
        addRow("API Key Meta", apiKeyDisplay, apiKeyStatus)
        addRow("Location Permission", locationPerm.toString(), if (locationPerm) MapsStatus.OK else MapsStatus.WARN)
        addRow("Internet Permission", internetPerm.toString(), if (internetPerm) MapsStatus.OK else MapsStatus.ERROR)
        addRow("Camera Permission", cameraPerm.toString(), if (cameraPerm) MapsStatus.OK else MapsStatus.WARN)
        addRow("Network Status", networkStatus.first, networkStatus.second)

        // Placeholder for runtime MapView test (async result inserted below)
        val mapTestLabel = "Runtime MapView Test"
        addRow(mapTestLabel, "PENDING", MapsStatus.WARN)
        val runtimeStatusIndex = rowIndex - 1 // last added row index in table children*2? We'll update the value view directly by tag.
        // We'll tag value TextView for later update
        val valueHolder = (table.getChildAt(runtimeStatusIndex*2 -1) as? LinearLayout)?.getChildAt(1) as? TextView

        // Reveal / Copy controls for API key
        val apiKeyControls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 8, 12, 8)
        }
        val revealBtn = Button(ctx).apply { text = "Reveal Key" }
        val copyBtn = Button(ctx).apply { text = "Copy Key"; isEnabled = false }
        val apiKeyFullView = TextView(ctx).apply {
            visibility = View.GONE
            text = apiKeyFull
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(12,4,12,12)
        }
        revealBtn.setOnClickListener {
            if (apiKeyFullView.visibility == View.GONE) {
                apiKeyFullView.visibility = View.VISIBLE
                copyBtn.isEnabled = apiKeyFull != "(not set)" && !apiKeyFull.startsWith("(error")
                revealBtn.text = "Hide Key"
            } else {
                apiKeyFullView.visibility = View.GONE
                revealBtn.text = "Reveal Key"
            }
        }
        copyBtn.setOnClickListener {
            val clip = ClipData.newPlainText("Maps API Key", apiKeyFull)
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(clip)
            Toast.makeText(ctx, "API key copied", Toast.LENGTH_SHORT).show()
        }
        apiKeyControls.addView(revealBtn)
        apiKeyControls.addView(copyBtn)

        container.addView(table)
        container.addView(apiKeyControls)
        container.addView(apiKeyFullView)

        // Replace text refresh button with right-aligned icon bar at top
        container.addView(createRefreshBar {
            val parent = container.parent as? ViewGroup
            if (parent != null) {
                val idx = parent.indexOfChild(container)
                parent.removeViewAt(idx)
                parent.addView(setupMapsDebugContent(), idx)
            }
        }, 0)

        // Runtime MapView test
        val testFrame = FrameLayout(ctx).apply {
            setPadding(0,16,0,0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(120f)).apply {
                topMargin = dpToPx(8f)
            }
        }
        val miniMapView = MapView(ctx)
        testFrame.addView(miniMapView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        container.addView(testFrame)
        miniMapView.onCreate(null)
        val handler = Handler(Looper.getMainLooper())
        var completed = false
        // For runtime MapView test updateRuntimeStatus signature
        fun updateRuntimeStatus(text: String, status: MapsStatus) {
            valueHolder?.text = text
            valueHolder?.setTextColor(
                when(status){
                    MapsStatus.OK -> 0xFF2E7D32.toInt()
                    MapsStatus.WARN -> 0xFFF9A825.toInt()
                    MapsStatus.ERROR -> 0xFFC62828.toInt()
                }
            )
        }
        try {
            miniMapView.getMapAsync { gMap ->
                completed = true
                updateRuntimeStatus("MAP_READY", MapsStatus.OK)
                // Light camera move (no network heavy call)
                gMap.moveCamera(com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(LatLng(0.0,0.0), 1f))
            }
            // Timeout
            handler.postDelayed({ if(!completed) updateRuntimeStatus("TIMEOUT", MapsStatus.ERROR) }, 5000)
        } catch (e: Exception) {
            updateRuntimeStatus("ERROR: ${e.message}", MapsStatus.ERROR)
        }
        // Manage mini MapView lifecycle via fragment callbacks
        lifecycle.addObserver(object: androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) { miniMapView.onResume() }
            override fun onPause(owner: androidx.lifecycle.LifecycleOwner) { miniMapView.onPause() }
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) { miniMapView.onDestroy() }
            // removed invalid onLowMemory override
        })

        return container
    }

    private fun setupCoordinatesDevContent(): View {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val status = TextView(ctx).apply {
            text = "Ready"
            setTextColor(ContextCompat.getColor(ctx, R.color.dev_info_label))
            textSize = 14f
        }
        fun setStatus(msg: String) { status.text = msg }

        val btnClear = androidx.appcompat.widget.AppCompatButton(ctx).apply {
            text = ctx.getString(R.string.dev_coord_clear_all)
            setOnClickListener {
                setStatus("Clearing…")
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching { coordinateRepository.deleteAll() }
                        .onSuccess { setStatus("All coordinates cleared") }
                        .onFailure { setStatus("Clear failed: ${it.message}") }
                }
            }
        }
        val btnGenerate = androidx.appcompat.widget.AppCompatButton(ctx).apply {
            text = ctx.getString(R.string.dev_coord_generate)
            setOnClickListener {
                setStatus("Generating…")
                viewLifecycleOwner.lifecycleScope.launch { generateCoordinates(::setStatus) }
            }
        }
        container.addView(btnGenerate)
        container.addView(btnClear)
        container.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(8f)) })
        container.addView(status)
        val hint = TextView(ctx).apply {
            text = ctx.getString(R.string.dev_coord_hint)
            textSize = 12f
            setPadding(0, dpToPx(12f), 0, 0)
            setTextColor(ContextCompat.getColor(ctx, R.color.dev_info_value))
        }
        container.addView(hint)
        return container
    }

    private suspend fun generateCoordinates(setStatus: (String) -> Unit) {
        val ctx = requireContext()
        val fineGranted = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) {
            setStatus("Requesting location permission…")
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 991)
            setStatus("Permission required")
            return
        }
        val fused = LocationServices.getFusedLocationProviderClient(ctx)
        val loc = runCatching {
            @Suppress("MissingPermission")
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
        }.getOrNull() ?: runCatching {
            @Suppress("MissingPermission")
            fused.lastLocation.await()
        }.getOrNull()
        if (loc == null) {
            setStatus("Location unavailable")
            return
        }
        val baseLat = loc.latitude
        val baseLon = loc.longitude
        val baseAlt = loc.altitude
        val list = mutableListOf<Coordinate>()
        val now = System.currentTimeMillis()
        // First coordinate is exact location
        list += Coordinate(
            id = UUID.randomUUID().toString(),
            name = "Gen 01",
            latitude = baseLat,
            longitude = baseLon,
            altitude = baseAlt,
            timestamp = now,
            icon = "ic_menu_camera",
            color = 0xFFE57373.toInt()
        )
        val earthRadius = 6378137.0 // meters
        repeat(14) { idx ->
            val feet = (1..20).random()
            val meters = feet * 0.3048
            val angle = Math.random() * 2 * Math.PI
            val dLat = (meters * cos(angle)) / earthRadius
            val dLon = (meters * sin(angle)) / (earthRadius * cos(baseLat * Math.PI / 180))
            val newLat = baseLat + dLat * (180 / Math.PI)
            val newLon = baseLon + dLon * (180 / Math.PI)
            list += Coordinate(
                id = UUID.randomUUID().toString(),
                name = "Gen %02d".format(idx + 2),
                latitude = newLat,
                longitude = newLon,
                altitude = baseAlt,
                timestamp = now + idx + 1,
                icon = "ic_menu_slideshow",
                color = 0xFF64B5F6.toInt()
            )
        }
        val result = runCatching { coordinateRepository.insertAll(list) }
        if (result.isSuccess) {
            val latStr = "%.5f".format(baseLat)
            val lonStr = "%.5f".format(baseLon)
            setStatus("Inserted ${list.size} coords @ $latStr, $lonStr")
        } else {
            setStatus("Insert failed: ${result.exceptionOrNull()?.message}")
        }
    }
}

// Helpers restored (table builders, formatting, dp conversion)
private fun DevelopmentFragment.dpToPx(dp: Float): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

private fun DevelopmentFragment.buildInfoTable(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(ContextCompat.getColor(ctx, R.color.dev_info_section_bg))
    elevation = 1f
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
    addView(View(ctx).apply {
        setBackgroundColor(0x14000000)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    })
}

private fun DevelopmentFragment.formatTime(ts: Long): String {
    if (ts <= 0) return getString(R.string.dev_value_unknown)
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(ts))
}

private fun DevelopmentFragment.formatBytes(bytes: Long): String {
    if (bytes < 0) return getString(R.string.dev_value_unknown)
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024.0
    var idx = 0
    while (v >= 1024 && idx < units.lastIndex) { v /= 1024; idx++ }
    return String.format(java.util.Locale.US, "%.2f %s", v, units[idx])
}

// Opt-in suppression for internal coroutine APIs warning
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) {} }
    addOnFailureListener { if (cont.isActive) cont.resume(null) {} }
    addOnCanceledListener { if (cont.isActive) cont.resume(null) {} }
}

// Add maps status enum outside the fragment for reuse
private enum class MapsStatus { OK, WARN, ERROR }
