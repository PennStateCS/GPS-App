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
import com.example.surveyingapp.gnss.bus.FixSwitchboard
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
import androidx.fragment.app.viewModels
import com.example.surveyingapp.ui.common.SatelliteSignalChartView
import com.example.surveyingapp.ui.common.SkyplotView
import com.example.surveyingapp.gnss.model.Constellation
import com.example.surveyingapp.gnss.model.SkyGeometry
import com.example.surveyingapp.gnss.diagnostics.DiagnosticData
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.RtkStatus
import com.example.surveyingapp.gnss.model.SkySnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class DevelopmentFragment : BaseTwoPaneFragment() {

    @Inject
    lateinit var fixSwitchboard: FixSwitchboard

    private val viewModel: DevelopmentViewModel by viewModels()

    // Developer categories: lightweight side list for specialized debug panes.
    // Add/remove here to extend; IDs must remain stable for state restoration.
    private val devCategories = listOf(
        SettingsCategory(1, "System Info", R.drawable.ic_section_info),
        SettingsCategory(2, "Permissions", R.drawable.ic_lock_24),
        SettingsCategory(3, "AR Debug", R.drawable.ic_dev_tools),
        SettingsCategory(4, "Maps Debug", R.drawable.ic_map),
        SettingsCategory(5, "Coordinates", R.drawable.ic_section_location),
        SettingsCategory(7, "GNSS", R.drawable.ic_satellite_24)
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

    override fun buildCategoryContent(category: SettingsCategory, inflater: LayoutInflater): View? =
        try {
            when (category.id) {
                1 -> setupSystemInfoContent(inflater)     // App & device/runtime diagnostics
                2 -> setupPermissionsContent(inflater)    // Manifest + grant snapshot
                3 -> setupArDebugContent(inflater)        // ARCore capability probe (no camera start)
                4 -> setupMapsDebugContent(inflater)      // Google Maps / Play Services debug
                5 -> setupCoordinatesDevContent(inflater) // Coordinate generation and management
                7 -> setupGnssLiveContent()               // Live GNSS fix + NMEA sentence history
                else -> null
            }
        } catch (e: Exception) {
            android.util.Log.e("DevelopmentFragment", "buildCategoryContent failed for id=${category.id}", e)
            null
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

    private fun setupPermissionsContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.dev_page_permissions, null)

        // Get references to the layout components
        val refreshBarContainer = view.findViewById<LinearLayout>(R.id.refreshBarContainer)
        val tableHolder = view.findViewById<LinearLayout>(R.id.permissionsTableContainer)
        val requestButton = view.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnRequestCorePermissions)

        fun rebuild() {
            tableHolder.removeAllViews()
            tableHolder.addView(createPermissionsTable())
        }

        // Add refresh bar
        refreshBarContainer.addView(createRefreshBar { rebuild() })

        // Set up the request button click listener
        requestButton.setOnClickListener {
            requestCorePermissions()
        }

        rebuild()
        permissionsTableHolder = tableHolder // Track reference for refreshing
        return view
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
                text = "${getString(R.string.dev_perm_error)}: ${e.message ?: ""}"
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
            text = "${getString(R.string.dev_perm_total)}: ${permissions.size}"
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

    private fun setupSystemInfoContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.dev_page_system_info, null)

        // Get references to the layout components
        val appInfoTableContainer = view.findViewById<LinearLayout>(R.id.appInfoTableContainer)
        val deviceInfoTableContainer = view.findViewById<LinearLayout>(R.id.deviceInfoTableContainer)

        val ctx = requireContext()
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

        // Populate app info table
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
        appInfoTableContainer.addView(appTable)

        // Populate device info table
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
        deviceInfoTableContainer.addView(deviceTable)

        return view
    }

    private fun setupArDebugContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.dev_page_ar_debug, null)

        // Get references to the layout components
        val refreshBarContainer = view.findViewById<LinearLayout>(R.id.refreshBarContainer)
        val tableContainer = view.findViewById<LinearLayout>(R.id.arInfoTableContainer)

        val ctx = requireContext()
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

        // Add refresh bar and table to containers
        refreshBarContainer.addView(createRefreshBar { populate() })
        tableContainer.addView(infoTable)

        return view
    }

    private fun setupMapsDebugContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.dev_page_maps_debug, null)

        // Get references to the layout components
        val refreshBarContainer = view.findViewById<LinearLayout>(R.id.refreshBarContainer)
        val tableContainer = view.findViewById<LinearLayout>(R.id.mapsInfoTableContainer)
        val apiKeyControls = view.findViewById<LinearLayout>(R.id.apiKeyControls)
        val revealBtn = view.findViewById<Button>(R.id.btnRevealKey)
        val copyBtn = view.findViewById<Button>(R.id.btnCopyKey)
        val apiKeyFullView = view.findViewById<TextView>(R.id.apiKeyFullView)
        val mapViewTestContainer = view.findViewById<FrameLayout>(R.id.mapViewTestContainer)

        val ctx = requireContext()

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
        val runtimeStatusIndex = rowIndex - 1
        val valueHolder = (table.getChildAt(runtimeStatusIndex*2 -1) as? LinearLayout)?.getChildAt(1) as? TextView

        // Add refresh bar and table to containers
        fun refresh() {
            val parent = view.parent as? ViewGroup
            if (parent != null) {
                val idx = parent.indexOfChild(view)
                parent.removeViewAt(idx)
                parent.addView(setupMapsDebugContent(inflater), idx)
            }
        }
        refreshBarContainer.addView(createRefreshBar { refresh() })
        tableContainer.addView(table)

        // Set up API key reveal/copy functionality
        apiKeyFullView.text = apiKeyFull
        revealBtn.setOnClickListener {
            if (apiKeyFullView.visibility == View.GONE) {
                apiKeyFullView.visibility = View.VISIBLE
                copyBtn.isEnabled = apiKeyFull != "(not set)" && !apiKeyFull.startsWith("(error")
                revealBtn.text = getString(R.string.dev_reveal_key).replace("Reveal", "Hide")
            } else {
                apiKeyFullView.visibility = View.GONE
                revealBtn.text = getString(R.string.dev_reveal_key)
            }
        }
        copyBtn.setOnClickListener {
            val clip = ClipData.newPlainText("Maps API Key", apiKeyFull)
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(clip)
            Toast.makeText(ctx, "API key copied", Toast.LENGTH_SHORT).show()
        }

        // Runtime MapView test
        val miniMapView = MapView(ctx)
        mapViewTestContainer.addView(miniMapView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        miniMapView.onCreate(null)
        val handler = Handler(Looper.getMainLooper())
        var completed = false

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
                gMap.moveCamera(com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(LatLng(0.0,0.0), 1f))
            }
            handler.postDelayed({ if(!completed) updateRuntimeStatus("TIMEOUT", MapsStatus.ERROR) }, 5000)
        } catch (e: Exception) {
            updateRuntimeStatus("ERROR: ${e.message}", MapsStatus.ERROR)
        }

        // Manage mini MapView lifecycle via fragment callbacks
        lifecycle.addObserver(object: androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) { miniMapView.onResume() }
            override fun onPause(owner: androidx.lifecycle.LifecycleOwner) { miniMapView.onPause() }
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) { miniMapView.onDestroy() }
        })

        return view
    }

    private fun setupCoordinatesDevContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.dev_page_coordinates, null)

        // Get references to the layout components
        val generateButton = view.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnGenerateCoords)
        val clearButton = view.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnClearAllCoords)
        val statusText = view.findViewById<TextView>(R.id.statusText)

        fun setStatus(msg: String) { statusText.text = msg }

        // Set up button click listeners
        generateButton.setOnClickListener {
            setStatus("Generating…")
            viewLifecycleOwner.lifecycleScope.launch { generateCoordinates(::setStatus) }
        }

        clearButton.setOnClickListener {
            setStatus("Clearing…")
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching { coordinateRepository.deleteAll() }
                    .onSuccess { setStatus("All coordinates cleared") }
                    .onFailure { setStatus("Clear failed: ${it.message}") }
            }
        }

        return view
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

        // Available icons from AddCoordinateDialogFragment
        val availableIcons = listOf(
            "ic_pin", "ic_home", "ic_star", "ic_circle", "ic_square", "ic_triangle", "ic_diamond"
        )

        // Available colors
        val availableColors = listOf(
            0xFFE57373.toInt(), // Red
            0xFF64B5F6.toInt(), // Blue
            0xFF81C784.toInt(), // Green
            0xFFFFB74D.toInt(), // Orange
            0xFFBA68C8.toInt()  // Purple
        )

        // Realistic yard/property names
        val propertyNames = listOf(
            "Home", "Front Door", "Garage", "Driveway", "Mailbox", "Power Lines", "Water Meter",
            "Property Corner", "Fence Post", "Garden Shed", "Fire Hydrant", "Street Light",
            "Tree Line", "Sidewalk", "Back Yard", "Side Gate", "Utility Pole", "Survey Marker",
            "Well Head", "Septic Tank", "Pool Corner", "Deck Corner", "Patio", "Greenhouse",
            "Tool Shed", "Barn", "Chicken Coop", "Compost Bin", "Rain Gauge", "Weather Station"
        )

        // First coordinate is exact location with "Home" name
        list += Coordinate(
            id = UUID.randomUUID().toString(),
            name = "Home",
            latitude = baseLat,
            longitude = baseLon,
            altitude = baseAlt,
            timestamp = now,
            icon = "ic_home",
            color = availableColors.random()
        )

        val earthRadius = 6378137.0 // meters
        val usedNames = mutableSetOf("Home") // Track used names to avoid duplicates

        repeat(14) { idx ->
            val feet = (1..20).random()
            val meters = feet * 0.3048
            val angle = Math.random() * 2 * Math.PI
            val dLat = (meters * cos(angle)) / earthRadius
            val dLon = (meters * sin(angle)) / (earthRadius * cos(baseLat * Math.PI / 180))
            val newLat = baseLat + dLat * (180 / Math.PI)
            val newLon = baseLon + dLon * (180 / Math.PI)

            // Pick a unique name
            var name = propertyNames.random()
            var attempts = 0
            while (usedNames.contains(name) && attempts < 10) {
                name = propertyNames.random()
                attempts++
            }
            if (usedNames.contains(name)) {
                name = "Point ${idx + 2}" // Fallback if all names used
            }
            usedNames.add(name)

            list += Coordinate(
                id = UUID.randomUUID().toString(),
                name = name,
                latitude = newLat,
                longitude = newLon,
                altitude = baseAlt,
                timestamp = now + idx + 1,
                icon = availableIcons.random(),
                color = availableColors.random()
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


    // ─────────────────────────── GNSS Live pane ──────────────────────────────

    /** Live GNSS viewer with source-specific sections for Internal vs RS2+ providers. */
    private fun setupGnssLiveContent(): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density + 0.5f).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(12f), dp(16f), dp(24f))
        }

        // ── Control row removed – Reset button is now inline with Statistics ──

        data class SectionViews(
            val container: LinearLayout,
            val values: MutableMap<String, TextView>
        )

        // ── Helper: build a key/value row section ─────────────────────────────
        fun buildSection(title: String, fields: List<String>): SectionViews {
            val values = mutableMapOf<String, TextView>()
            val sectionContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val table = buildInfoTable(ctx)
            fields.forEachIndexed { idx, label ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(12, 8, 12, 8)
                    if (idx % 2 == 1) setBackgroundColor(ContextCompat.getColor(ctx, R.color.dev_info_row_alt))
                }
                val lv = TextView(ctx).apply {
                    text = label
                    setTextColor(ContextCompat.getColor(ctx, R.color.dev_info_label))
                    textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
                }
                val vv = TextView(ctx).apply {
                    text = "--"
                    setTextColor(ContextCompat.getColor(ctx, R.color.dev_info_value))
                    textSize = 13f; setTextIsSelectable(true)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
                    maxLines = 3
                }
                values[label] = vv
                row.addView(lv); row.addView(vv)
                table.addView(row)
                if (idx < fields.lastIndex) table.addView(View(ctx).apply {
                    setBackgroundColor(0x14000000)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                })
            }
            sectionContainer.addView(gnssLiveSectionHeader(ctx, title))
            sectionContainer.addView(table)
            root.addView(sectionContainer)
            return SectionViews(sectionContainer, values)
        }

        // ── Source & Status ───────────────────────────────────────────────────
        val sourceSection = buildSection("Source and Time", listOf(
            "GNSS Source",
            "RTK Fix Status",
            "UTC Timestamp",
            "Timestamp Source"
        ))

        // ── Position ──────────────────────────────────────────────────────────
        val posSection = buildSection("Position", listOf(
            "Latitude",
            "Longitude",
            "Mean Sea Level Altitude",
            "Ellipsoidal Altitude",
            "Geoid Separation",
            "Horizontal Accuracy (1σ)",
            "Vertical Accuracy (1σ)",
            "East Precision (σE)",
            "North Precision (σN)",
            "Up Precision (σU)"
        ))

        // ── Motion ────────────────────────────────────────────────────────────
        val motionSection = buildSection("Motion", listOf("Speed", "Course Over Ground"))

        // ── Signal Quality ────────────────────────────────────────────────────
        val qualitySection = buildSection("Signal Quality", listOf(
            "Horizontal Dilution of Precision",
            "Vertical Dilution of Precision",
            "Position Dilution of Precision",
            "Satellites Used",
            "Satellites Visible"
        ))

        // ── Differential corrections (external only) ─────────────────────────
        val correctionsSection = buildSection("Differential Corrections (RS2+)", listOf(
            "Correction Age",
            "Correction Station ID"
        ))

        // ── Satellite Sky ─────────────────────────────────────────────────────
        root.addView(gnssLiveSectionHeader(ctx, "Satellite Sky"))
        val skyTable = buildInfoTable(ctx)
        val skyTotalView = TextView(ctx).apply {
            text = "--"
            setTextColor(ContextCompat.getColor(ctx, R.color.dev_info_value))
            textSize = 13f; setTextIsSelectable(true)
            setPadding(12, 8, 12, 4)
        }
        val skyConstellationView = TextView(ctx).apply {
            text = "(no GSV data yet)"
            setTextColor(ContextCompat.getColor(ctx, R.color.dev_info_value))
            textSize = 12f; setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE
            setPadding(12, 4, 12, 8)
        }
        skyTable.addView(skyTotalView)
        skyTable.addView(View(ctx).apply {
            setBackgroundColor(0x14000000)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })
        skyTable.addView(skyConstellationView)
        root.addView(skyTable)

        // ── RS2+ Device (battery + device info) ──────────────────────────────
        val batterySection = buildSection("RS2+ Battery", listOf(
            "SoC", "Voltage", "Current", "USB Charger Current", "USB Charger Voltage",
            "Temperature", "Charger Status", "OTG Power"
        ))
        val deviceSection = buildSection("RS2+ Device", listOf(
            "IP", "Hostname", "Name", "Model", "Firmware", "GNSS Receiver FW",
            "Serial", "Uptime", "Storage"
        ))

        // ── Skyplot ───────────────────────────────────────────────────────────
        root.addView(gnssLiveSectionHeader(ctx, "Satellite Skyplot"))
        val skyplotView = SkyplotView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(420f)
            )
        }
        root.addView(skyplotView)

        // ── Signal charts (one per constellation) ─────────────────────────────
        root.addView(gnssLiveSectionHeader(ctx, "Signal Strength"))
        val constellations = listOf(
            Constellation.GPS to "GPS",
            Constellation.GLONASS to "GLONASS",
            Constellation.GALILEO to "Galileo",
            Constellation.BEIDOU to "BeiDou",
            Constellation.QZSS to "QZSS",
            Constellation.SBAS to "SBAS"
        )
        val charts = constellations.map { (c, label) ->
            root.addView(gnssLiveSectionHeader(ctx, label).apply {
                textSize = 12f
                setPadding(0, dp(8f), 0, dp(2f))
            })
            val chart = SatelliteSignalChartView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(160f)
                )
                setConstellationFilter(c)
            }
            root.addView(chart)
            c to chart
        }.toMap()

        // ── Stream stats ──────────────────────────────────────────────────────
        val btnReset = androidx.appcompat.widget.AppCompatButton(ctx).apply { text = "Reset" }
        val statsSection = buildSection("Stream Statistics", listOf(
            "Lines/sec", "Error Rate", "Total Lines", "Total Errors"
        ))
        // Swap the plain header for a title + Reset button row
        val statsHeader = statsSection.container.getChildAt(0)
        statsSection.container.removeViewAt(0)
        statsSection.container.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(statsHeader, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnReset, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }, 0)

        // ── NMEA sentence history (last 5 sentences, no inner scroll) ────────
        root.addView(gnssLiveSectionHeader(ctx, "NMEA History (last 5)"))
        val historyText = TextView(ctx).apply {
            text = "(no NMEA data yet)"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            isFocusable = false
            isFocusableInTouchMode = false
            setTextColor(ContextCompat.getColor(ctx, R.color.dev_info_value))
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.dev_info_section_bg))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        root.addView(historyText)

        // ── Live update helpers ───────────────────────────────────────────────
        val utcFmt = SimpleDateFormat("HH:mm:ss.SSS 'UTC'", Locale.US).also { it.timeZone = TimeZone.getTimeZone("UTC") }
        fun fmtM(d: Double?) = d?.let { "%.3f m".format(it) } ?: "--"
        fun fmtDeg(d: Double?) = d?.let { "%.8f°".format(it) } ?: "--"
        fun fmt2(d: Double?) = d?.let { "%.2f".format(it) } ?: "--"

        var latestProviderLabel = "Unknown"
        var latestSkySnapshot: SkySnapshot? = null

        fun setExternalSectionVisibility(providerLabel: String) {
            val isExternal = providerLabel.contains("RS2+", ignoreCase = true) ||
                providerLabel.contains("External", ignoreCase = true)
            correctionsSection.container.visibility = if (isExternal) View.VISIBLE else View.GONE
            batterySection.container.visibility = if (isExternal) View.VISIBLE else View.GONE
            deviceSection.container.visibility = if (isExternal) View.VISIBLE else View.GONE
        }

        fun applyFix(fix: Fix?) {
            sourceSection.values["GNSS Source"]?.text = latestProviderLabel
            if (fix == null) {
                sourceSection.values.values.forEach { it.apply { text = "--"; setTextColor(ContextCompat.getColor(ctx, R.color.dev_info_value)) } }
                sourceSection.values["GNSS Source"]?.text = latestProviderLabel
                posSection.values.values.forEach { it.text = "--" }
                motionSection.values.values.forEach { it.text = "--" }
                qualitySection.values.values.forEach { it.text = "--" }
                correctionsSection.values.values.forEach { it.text = "--" }
                return
            }
            // Source & Status
            sourceSection.values["RTK Fix Status"]?.apply {
                text = fix.rtkStatus.name
                setTextColor(when (fix.rtkStatus) {
                    RtkStatus.FIX            -> 0xFF2E7D32.toInt()
                    RtkStatus.FLOAT          -> 0xFFF9A825.toInt()
                    RtkStatus.DGPS           -> 0xFF1565C0.toInt()
                    RtkStatus.SINGLE         -> 0xFFFF5722.toInt()
                    RtkStatus.DEAD_RECKONING -> 0xFF9E9E9E.toInt()
                    else                     -> 0xFFC62828.toInt()
                })
            }
            sourceSection.values["UTC Timestamp"]?.text = utcFmt.format(Date(fix.timeUtc.toEpochMilli()))
            sourceSection.values["Timestamp Source"]?.text = fix.timestampSource.name.replace('_', ' ')
            // Position
            posSection.values["Latitude"]?.text = fmtDeg(fix.latDeg)
            posSection.values["Longitude"]?.text = fmtDeg(fix.lonDeg)
            posSection.values["Mean Sea Level Altitude"]?.text = fmtM(fix.altMslM)
            posSection.values["Ellipsoidal Altitude"]?.text = fmtM(fix.altEllipsoidalM)
            posSection.values["Geoid Separation"]?.text = fmtM(fix.geoidSeparationM)
            posSection.values["Horizontal Accuracy (1σ)"]?.text = fix.hAccM?.let { "%.3f m".format(it) } ?: "--"
            posSection.values["Vertical Accuracy (1σ)"]?.text = fix.vAccM?.let { "%.3f m".format(it) } ?: "--"
            posSection.values["East Precision (σE)"]?.text = fix.stdDevEastM?.let { "%.3f m".format(it) } ?: "--"
            posSection.values["North Precision (σN)"]?.text = fix.stdDevNorthM?.let { "%.3f m".format(it) } ?: "--"
            posSection.values["Up Precision (σU)"]?.text = fix.stdDevUpM?.let { "%.3f m".format(it) } ?: "--"
            // Motion
            val knots = fix.speedMps?.let { it * 1.94384 }
            motionSection.values["Speed"]?.text = fix.speedMps?.let { "%.2f m/s  (%.1f kn)".format(it, knots!!) } ?: "--"
            motionSection.values["Course Over Ground"]?.text = fix.courseDeg?.let { "%.1f°".format(it) } ?: "--"
            // Quality
            qualitySection.values["Horizontal Dilution of Precision"]?.text = fmt2(fix.hDop)
            qualitySection.values["Vertical Dilution of Precision"]?.text = fmt2(fix.vDop)
            qualitySection.values["Position Dilution of Precision"]?.text = fmt2(fix.pDop)
            // Use SkySnapshot data for satellite counts (from GSV sentences) for consistency with Satellite Sky section
            val sky = latestSkySnapshot
            qualitySection.values["Satellites Used"]?.text = sky?.totalUsed?.toString() ?: fix.satsUsed.toString()
            qualitySection.values["Satellites Visible"]?.text = sky?.totalVisible?.toString() ?: (fix.satsVisible?.toString() ?: "--")
            // Correction Age and Station ID come from Fix (NMEA GGA)
            correctionsSection.values["Correction Age"]?.text = fix.diffAgeS?.let { "%.1f s".format(it) } ?: "--"
            correctionsSection.values["Correction Station ID"]?.text = fix.correctionStationId ?: "--"
        }

        fun applyBattery(b: com.example.surveyingapp.domain.repository.ReachBatteryInfo?) {
            batterySection.values["SoC"]?.text = b?.percent?.let { "$it%" } ?: "--"
            batterySection.values["Voltage"]?.text = b?.voltageV?.let { "%.2f V".format(it) } ?: "--"
            batterySection.values["Current"]?.text = b?.currentA?.let { "%.2f A".format(it) } ?: "--"
            batterySection.values["USB Charger Current"]?.text = b?.usbChargerCurrentA?.let { "%.2f A".format(it) } ?: "--"
            batterySection.values["USB Charger Voltage"]?.text = b?.usbChargerVoltageV?.let { "%.2f V".format(it) } ?: "--"
            batterySection.values["Temperature"]?.text = b?.temperatureC?.let { "%.1f °C".format(it) } ?: "--"
            batterySection.values["Charger Status"]?.text = b?.chargerStatus ?: "--"
            batterySection.values["OTG Power"]?.text = when (b?.otg) {
                true -> "Enabled"
                false -> "Disabled"
                null -> "--"
            }
        }

        fun applyDevice(d: com.example.surveyingapp.domain.repository.ReachDeviceInfo?) {
            deviceSection.values["IP"]?.text = d?.ip ?: "--"
            deviceSection.values["Hostname"]?.text = d?.hostname ?: "--"
            deviceSection.values["Name"]?.text = d?.name ?: "--"
            deviceSection.values["Model"]?.text = d?.model ?: "--"
            deviceSection.values["Firmware"]?.text = d?.firmware ?: "--"
            deviceSection.values["GNSS Receiver FW"]?.text = d?.gnssReceiverFirmware ?: "--"
            deviceSection.values["Serial"]?.text = d?.serial ?: "--"
            deviceSection.values["Uptime"]?.text = d?.uptime ?: d?.uptimeSec?.let {
                val days = it / 86400; val h = (it % 86400) / 3600; val m = (it % 3600) / 60
                when {
                    days > 0 -> "${days}d ${h}h ${m}m"
                    h > 0    -> "${h}h ${m}m"
                    else     -> "${m}m"
                }
            } ?: "--"
            deviceSection.values["Storage"]?.text = d?.storage?.let { s ->
                val usedPct = s.usagePercent
                "${s.usedMB} MB used / ${s.totalMB} MB total (${usedPct}%  —  ${s.availableMB} MB free)"
            } ?: "--"
        }

        fun applySky(sky: SkySnapshot) {
            latestSkySnapshot = sky
            skyTotalView.text = "Total Used / Visible:  ${sky.totalUsed} / ${sky.totalVisible}"
            // Update Signal Quality section with latest satellite counts
            qualitySection.values["Satellites Used"]?.text = sky.totalUsed.toString()
            qualitySection.values["Satellites Visible"]?.text = sky.totalVisible.toString()
            if (sky.satellites.isEmpty()) {
                skyConstellationView.text = "(no GSV data yet)"
                skyplotView.setGeometry(emptyList())
                charts.values.forEach { it.setGeometry(emptyList()) }
                return
            }
            val sb = StringBuilder()
            sky.visibleByConstellation.entries.sortedByDescending { it.value }.forEach { (c, vis) ->
                val used = sky.usedByConstellation[c] ?: 0
                sb.appendLine("  ${c.name.padEnd(10)}  used=$used   vis=$vis")
            }
            skyConstellationView.text = sb.toString().trimEnd()

            // Build SkyGeometry list for charts and skyplot
            val geoms = sky.satellites.mapNotNull { sat ->
                if (sat.azimuthDeg != null && sat.elevationDeg != null) {
                    SkyGeometry(
                        svid = sat.svid,
                        constellation = sat.constellation,
                        azDeg = sat.azimuthDeg,
                        elDeg = sat.elevationDeg,
                        snrDbHz = sat.cn0DbHz,
                        usedInFix = sat.usedInFix ?: false
                    )
                } else null
            }
            skyplotView.setGeometry(geoms)
            charts.values.forEach { it.setGeometry(geoms) }
        }

        fun applyDiagnostics(data: DiagnosticData) {
            statsSection.values["Lines/sec"]?.text = "%.2f".format(data.linesPerSecond)
            statsSection.values["Error Rate"]?.text = "%.2f%%".format(data.parseErrorRate)
            statsSection.values["Total Lines"]?.text = data.totalLinesProcessed.toString()
            statsSection.values["Total Errors"]?.text = data.totalParseErrors.toString()
            historyText.text = data.lastTwentySentences.takeIf { it.isNotEmpty() }
                ?.takeLast(5)?.reversed()?.joinToString("\n") ?: "(no NMEA data yet)"
        }

        viewLifecycleOwner.lifecycleScope.launch {
            launch { viewModel.latestFix.collect { fix -> applyFix(fix) } }
            launch { viewModel.skySnapshot.collect { sky -> applySky(sky) } }
            launch { viewModel.diagnosticData.collect { data -> applyDiagnostics(data) } }
            launch { viewModel.batteryInfo.collect { b -> applyBattery(b) } }
            launch { viewModel.deviceInfo.collect { d -> applyDevice(d) } }
            launch {
                viewModel.activeProviderLabel.collect { providerLabel ->
                    latestProviderLabel = providerLabel
                    sourceSection.values["GNSS Source"]?.text = providerLabel
                    setExternalSectionVisibility(providerLabel)
                }
            }
        }

        btnReset.setOnClickListener {
            viewModel.resetDiagnostics()
            Toast.makeText(ctx, "Diagnostics counters reset", Toast.LENGTH_SHORT).show()
        }

        setExternalSectionVisibility(latestProviderLabel)
        return root
    }

    /** Small bold section header used by the GNSS Live pane. */
    private fun gnssLiveSectionHeader(ctx: android.content.Context, title: String): TextView {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density + 0.5f).toInt()
        return TextView(ctx).apply {
            text = title
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.dev_info_label))
            setPadding(0, dp(16f), 0, dp(6f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
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
}
