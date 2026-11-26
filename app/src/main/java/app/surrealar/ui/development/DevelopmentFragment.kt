package app.surrealar.ui.development

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import androidx.activity.result.contract.ActivityResultContracts

import androidx.lifecycle.lifecycleScope
import app.surrealar.R
import app.surrealar.ui.common.BaseTwoPaneFragment
import app.surrealar.ui.map.MapThemeHelper
import app.surrealar.ui.settings.SettingsCategory
import app.surrealar.gnss.bus.FixSwitchboard
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
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlin.math.*
import androidx.fragment.app.viewModels
import app.surrealar.ui.common.SatelliteSignalChartView
import app.surrealar.ui.common.SkyplotView
import io.github.pennstatecs.geokit.gnss.model.Constellation
import io.github.pennstatecs.geokit.gnss.model.SkyGeometry
import app.surrealar.gnss.diagnostics.DiagnosticData
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import io.github.pennstatecs.geokit.gnss.model.SkySnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Debug-only developer screen showing live GNSS fix, sky, and diagnostics data for troubleshooting.
 * Presentation only; gated behind the developer-tools setting.
 */
@AndroidEntryPoint
class DevelopmentFragment : BaseTwoPaneFragment() {

    @Inject
    lateinit var fixSwitchboard: FixSwitchboard

    @Inject
    lateinit var dataHealthChecker: app.surrealar.data.health.DataHealthChecker

    @Inject
    lateinit var rawNmeaRecorder: app.surrealar.gnss.diagnostics.raw.RawNmeaRecorder

    @Inject
    lateinit var developerSettingsRepo: app.surrealar.domain.repository.SettingsRepository

    /** Raw NMEA Recording page controls; kept for the fragment's lifetime so the duration choice survives. */
    private val rawNmeaSection by lazy {
        app.surrealar.ui.settings.categories.RawNmeaRecordingSection(
            this, rawNmeaRecorder, developerSettingsRepo,
            saveScope = kotlinx.coroutines.MainScope(),   // outlives the page: finishing a share/delete or a save
        )
    }

    private val viewModel: DevelopmentViewModel by viewModels()

    // Developer categories: lightweight side list for specialized debug panes.
    // Add/remove here to extend; IDs must remain stable for state restoration.
    private val devCategories = listOf(
        SettingsCategory(3, "AR Debug",     R.drawable.ic_dev_tools),
        SettingsCategory(7, "GNSS",         R.drawable.ic_satellite_24),
        SettingsCategory(8, "Raw NMEA Recording", R.drawable.ic_record_24),
        SettingsCategory(4, "Maps Debug",   R.drawable.ic_map),
        SettingsCategory(2, "Permissions",  R.drawable.ic_lock_24),
        SettingsCategory(1, "System Info",  R.drawable.ic_section_info)
    )

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
        showDeveloperMessage(message)
        // Refresh the permissions table to show updated status
        refreshPermissionsTable()
    }

    private var permissionsTableHolder: LinearLayout? = null

    override fun provideCategories(): List<SettingsCategory> = devCategories

    override fun buildCategoryContent(category: SettingsCategory, inflater: LayoutInflater): View? =
        try {
            when (category.id) {
                1 -> setupSystemInfoContent(inflater)     // App & device/runtime diagnostics
                2 -> setupPermissionsContent(inflater)    // Manifest + grant snapshot
                3 -> setupArDebugContent(inflater)        // ARCore capability probe (no camera start)
                4 -> setupMapsDebugContent(inflater)      // Google Maps / Play Services debug
                7 -> setupGnssLiveContent()               // Live GNSS fix + NMEA sentence history
                8 -> setupRawNmeaContent(inflater)        // Raw NMEA recording (developer diagnostic)
                else -> null
            }
        } catch (e: Exception) {
            app.surrealar.util.DiagnosticsLogger.e(app.surrealar.util.diagnostics.DiagTags.APP, "Developer page category build failed categoryId=${category.id}", e)
            null
        }

    /**
     * Raw NMEA Recording page. The controls are bound for as long as the page is shown: switching to another
     * Developer page (the view is detached) or leaving Developer Tools ends a running recording (LIFECYCLE), but a
     * rotation does not (the section checks isChangingConfigurations).
     */
    private fun setupRawNmeaContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.dev_page_raw_nmea, null)
        val pageScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
        rawNmeaSection.bind(view, pageScope)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                rawNmeaSection.onDestroyView()
                pageScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        })
        return view
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
        val requestButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRequestCorePermissions)

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
                setTextColor(resolveAttrColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
        } catch (e: Exception) {
            TextView(ctx).apply {
                text = "${getString(R.string.dev_perm_error)}: ${e.message ?: ""}"
                textSize = 14f
                setTextColor(resolveAttrColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
        }
    }

    private fun createPermissionsTableLayout(permissions: Array<String>, pm: android.content.pm.PackageManager, packageName: String): View {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * density + 0.5f).toInt()

        val (card, rows) = buildInfoCard(ctx, ctx.getString(R.string.dev_section_permission_status))

        // Summary header inside card before rows
        val summaryView = TextView(ctx).apply {
            text = "${getString(R.string.dev_perm_total)}: ${permissions.size}"
            textSize = 12f
            setTextColor(resolveAttrColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(20f), 0, dp(20f), dp(8f))
        }
        // Insert summary before the row container (card header is index 0, rowContainer is index 1 inside cardContent)
        (rows.parent as? LinearLayout)?.addView(summaryView, 1)

        permissions.forEachIndexed { idx, perm ->
            val granted = pm.checkPermission(perm, packageName) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val shortName = perm.substringAfterLast('.')
            val isLast = idx == permissions.lastIndex

            // Vertical layout: short name + status on one row, full path below
            val rowWrap = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
            }
            val topRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val nameView = TextView(ctx).apply {
                text = shortName
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(resolveAttrColor(ctx, com.google.android.material.R.attr.colorOnSurface))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val statusView = TextView(ctx).apply {
                text = if (granted) "GRANTED" else "DENIED"
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(if (granted) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
            }
            topRow.addView(nameView)
            topRow.addView(statusView)
            val pathView = TextView(ctx).apply {
                text = perm
                textSize = 11f
                setTextColor(resolveAttrColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant))
                setTextIsSelectable(true)
                maxLines = 2
            }
            rowWrap.addView(topRow)
            rowWrap.addView(pathView)
            rows.addView(rowWrap)

            if (!isLast) rows.addView(View(ctx).apply {
                setBackgroundColor(dividerColor(ctx))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                    marginStart = dp(20f)
                }
            })
        }
        return card
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

        // App Info card
        val appInfoData = listOf(
            ctx.getString(R.string.dev_label_package_name) to packageName,
            ctx.getString(R.string.dev_label_version_name) to versionName,
            ctx.getString(R.string.dev_label_version_code) to versionCode,
            ctx.getString(R.string.dev_label_first_install) to formatTime(firstInstall),
            ctx.getString(R.string.dev_label_last_update) to formatTime(lastUpdate),
            ctx.getString(R.string.dev_label_target_sdk) to targetSdk,
            ctx.getString(R.string.dev_label_min_sdk) to minSdk,
            ctx.getString(R.string.dev_label_debuggable) to debuggable.toString(),
            ctx.getString(R.string.dev_label_system_app) to systemApp.toString(),
            ctx.getString(R.string.dev_label_app_size) to (if (apkSize >= 0) formatBytes(apkSize) else getString(R.string.dev_value_unknown)),
            ctx.getString(R.string.dev_label_source_dir) to (appInfo?.sourceDir ?: getString(R.string.dev_value_unknown)),
            ctx.getString(R.string.dev_label_data_dir) to (appInfo?.dataDir ?: getString(R.string.dev_value_unknown))
        )
        val (appCard, appRows) = buildInfoCard(ctx, ctx.getString(R.string.dev_section_app_info))
        appInfoData.forEachIndexed { idx, (label, value) ->
            appRows.addInfoRow(ctx, label, value, isLast = idx == appInfoData.lastIndex)
        }
        appInfoTableContainer.addView(appCard)

        // Device Info card
        val deviceInfoData = listOf(
            ctx.getString(R.string.dev_label_android_version) to androidVersion,
            ctx.getString(R.string.dev_label_sdk_int) to sdkInt,
            ctx.getString(R.string.dev_label_device_model) to model,
            ctx.getString(R.string.dev_label_manufacturer) to manufacturer,
            ctx.getString(R.string.dev_label_brand) to brand,
            ctx.getString(R.string.dev_label_abis) to abis,
            ctx.getString(R.string.dev_label_process_name) to processName,
            ctx.getString(R.string.dev_label_runtime_threads) to threadCount.toString(),
            ctx.getString(R.string.dev_label_heap_used) to formatBytes(heapUsed),
            ctx.getString(R.string.dev_label_heap_free) to formatBytes(heapFree),
            ctx.getString(R.string.dev_label_heap_max) to formatBytes(heapMax),
            ctx.getString(R.string.dev_label_internal_free) to formatBytes(internalFree),
            ctx.getString(R.string.dev_label_internal_total) to formatBytes(internalTotal)
        )
        val (deviceCard, deviceRows) = buildInfoCard(ctx, ctx.getString(R.string.dev_section_device_info))
        deviceInfoData.forEachIndexed { idx, (label, value) ->
            deviceRows.addInfoRow(ctx, label, value, isLast = idx == deviceInfoData.lastIndex)
        }
        deviceInfoTableContainer.addView(deviceCard)

        // Data health check — read-only scan of coordinate/model records. Strictly debug-only:
        // there is no release-safe internal diagnostics policy, so it is never exposed in release.
        if (app.surrealar.BuildConfig.DEBUG) {
            val healthBtn = Button(ctx).apply {
                text = "Run Data Health Check"
                setOnClickListener { btn ->
                    btn.isEnabled = false
                    categoryScope.launch {
                        val report = runCatching { dataHealthChecker.check() }
                            .onFailure {
                                if (it is kotlinx.coroutines.CancellationException) throw it
                                app.surrealar.util.DiagnosticsLogger.w(app.surrealar.util.diagnostics.DiagTags.DATA, "Data health check failed", it)
                            }
                            .getOrNull()
                        btn.isEnabled = true
                        if (report == null) {
                            Snackbar.make(view, "Health check failed (see logcat)", Snackbar.LENGTH_SHORT).show()
                            return@launch
                        }
                        // logcat only: the report can name records, so it stays out of the diagnostics file.
                        app.surrealar.util.DevLog.d("DataHealth") { report.format() }
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle("Data Health · ${report.errorCount} errors, ${report.warningCount} warnings")
                            .setMessage(report.format())
                            .setPositiveButton("Close", null)
                            .show()
                    }
                }
            }
            deviceInfoTableContainer.addView(healthBtn)
        }

        return view
    }

    private fun setupArDebugContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.dev_page_ar_debug, null)

        // Get references to the layout components
        val refreshBarContainer = view.findViewById<LinearLayout>(R.id.refreshBarContainer)
        val tableContainer = view.findViewById<LinearLayout>(R.id.arInfoTableContainer)

        val ctx = requireContext()
        val (arCard, arRows) = buildInfoCard(ctx, ctx.getString(R.string.dev_section_ar_diagnostics))
        // Runs off the main thread: creating/configuring an ARCore Session can take hundreds of ms.
        fun gather(): List<Pair<String,String>> {
            val list = mutableListOf<Pair<String,String>>()
            val availability = runCatching { ArCoreApk.getInstance().checkAvailability(ctx).toString() }.getOrElse { it.javaClass.simpleName }
            list += "ARCore Availability" to availability
            var session: Session? = null
            try {
                val sessionResult = runCatching {
                    val s = Session(ctx) // Will fail if ARCore services missing/outdated.
                    session = s
                    val cfg = Config(s).apply {
                        if (s.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) geospatialMode = Config.GeospatialMode.ENABLED
                    }
                    s.configure(cfg)
                    "OK"
                }.getOrElse { e -> e.message ?: e.javaClass.simpleName }
                list += "Session Create" to sessionResult
                val s = session
                if (s != null) {
                    val geospatialSupported = runCatching { s.isGeospatialModeSupported(Config.GeospatialMode.ENABLED) }.getOrDefault(false)
                    val depthSupported = runCatching { s.isDepthModeSupported(Config.DepthMode.AUTOMATIC) }.getOrDefault(false)
                    // ARCore has no support query for instant placement, and configure() accepting the
                    // mode does not prove it works, so only a rejection is a definite answer.
                    val instantPlacement = try {
                        s.configure(Config(s).apply { instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP })
                        "unknown (config accepted)"
                    } catch (_: com.google.ar.core.exceptions.UnsupportedConfigurationException) {
                        "false"
                    } catch (e: Exception) {
                        "unknown (${e.javaClass.simpleName})"
                    } finally {
                        runCatching { s.configure(Config(s)) } // never leave instant placement on
                    }
                    list += "Geospatial Supported" to geospatialSupported.toString()
                    list += "Depth Supported" to depthSupported.toString()
                    list += "Instant Placement Supported" to instantPlacement
                    list += "Earth Tracking" to "(Session not resumed)" // Not resumed: no camera usage for quick diagnostics
                }
            } finally {
                runCatching { session?.close() }
            }
            // Permissions snapshot (not requesting here – purely informative)
            val camGranted = ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val locGranted = ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            list += "Camera Permission" to camGranted.toString()
            list += "Location Permission" to locGranted.toString()
            list += "Device Model" to android.os.Build.MODEL
            list += "Android SDK" to android.os.Build.VERSION.SDK_INT.toString()
            return list
        }
        fun showRows(data: List<Pair<String, String>>) {
            arRows.removeAllViews()
            data.forEachIndexed { idx, pair ->
                arRows.addInfoRow(ctx, pair.first, pair.second, isLast = idx == data.lastIndex)
            }
        }
        // Show "Checking…" rows, gather on a background thread, then fill the card if still shown.
        // categoryScope is cancelled when the user switches category, so a stale result is dropped.
        var gatherJob: kotlinx.coroutines.Job? = null
        fun populate() {
            gatherJob?.cancel()
            showRows(listOf(
                "ARCore Availability" to getString(R.string.dev_checking),
                "Session Create" to getString(R.string.dev_checking),
            ))
            gatherJob = categoryScope.launch {
                val data = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { gather() }
                if (view.parent != null) showRows(data)
            }
        }
        populate()

        // Add refresh bar and card to containers
        refreshBarContainer.addView(createRefreshBar { populate() })
        tableContainer.addView(arCard)

        return view
    }

    private fun setupMapsDebugContent(inflater: LayoutInflater): View {
        val view = inflater.inflate(R.layout.dev_page_maps_debug, null)

        // Get references to the layout components
        val refreshBarContainer = view.findViewById<LinearLayout>(R.id.refreshBarContainer)
        val tableContainer = view.findViewById<LinearLayout>(R.id.mapsInfoTableContainer)
        val mapViewTestContainer = view.findViewById<FrameLayout>(R.id.mapViewTestContainer)

        val ctx = requireContext()

        // Card for maps diagnostics rows (color-coded by status)
        val (mapsCard, mapsRows) = buildInfoCard(ctx, ctx.getString(R.string.dev_section_maps_diagnostics))

        // Slow checks (package manager, Play services, MapsInitializer, signing certs). Runs on a
        // background thread; only the runtime MapView test below stays on the main thread.
        fun gather(): List<Triple<String, String, MapsStatus?>> {
            val mapsRowsList = mutableListOf<Triple<String, String, MapsStatus?>>()
            fun addRow(label: String, value: String, status: MapsStatus? = null) {
                mapsRowsList.add(Triple(label, value, status))
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
            // Network capability info — computed eagerly so rows can be added inline
            data class NetInfo(
                val internetVal: String, val internetStatus: MapsStatus,
                val validatedVal: String, val validatedStatus: MapsStatus,
                val transportVal: String, val transportStatus: MapsStatus,
                val warningVal: String?
            )
            val netInfo = runCatching {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val active = cm.activeNetwork
                val caps = active?.let { cm.getNetworkCapabilities(it) }
                val hasInternet = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                val hasValidated = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                val isWifi = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
                val isCellular = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
                val isVpn = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                NetInfo(
                    internetVal = if (active == null) "no active network" else hasInternet.toString(),
                    internetStatus = if (active == null) MapsStatus.ERROR else if (hasInternet) MapsStatus.OK else MapsStatus.ERROR,
                    validatedVal = if (active == null) "no active network" else hasValidated.toString(),
                    validatedStatus = when { active == null -> MapsStatus.ERROR; hasValidated -> MapsStatus.OK; hasInternet -> MapsStatus.WARN; else -> MapsStatus.ERROR },
                    transportVal = when { active == null -> "None"; caps == null -> "Unknown"; isVpn -> "VPN"; isWifi -> "Wi-Fi"; isCellular -> "Cellular"; else -> "Other" },
                    transportStatus = if (active == null) MapsStatus.ERROR else if (isVpn) MapsStatus.WARN else MapsStatus.OK,
                    warningVal = if (active != null && hasInternet && !hasValidated)
                        "Connected but not validated — map tiles may not load" else null
                )
            }.getOrElse {
                NetInfo("error", MapsStatus.ERROR, "error", MapsStatus.ERROR, "error", MapsStatus.ERROR, it.message)
            }
            val mapsLibPresent = runCatching { Class.forName("com.google.android.gms.maps.GoogleMap"); true }.getOrDefault(false)
            val fusedLocPresent = runCatching { Class.forName("com.google.android.gms.location.FusedLocationProviderClient"); true }.getOrDefault(false)

            // Populate rows (color-coded)
            addRow("Play Services Availability", availabilityText.first, availabilityText.second)
            addRow("Play Services Version", playServicesVersion, if (availabilityText.second == MapsStatus.OK) MapsStatus.OK else availabilityText.second)
            addRow("Play Services Last Update", if (playServicesUpdated>0) formatTime(playServicesUpdated) else "--", if (playServicesUpdated>0) MapsStatus.OK else MapsStatus.WARN)
            addRow("Maps Library Present", mapsLibPresent.toString(), if (mapsLibPresent) MapsStatus.OK else MapsStatus.ERROR)
            addRow("Fused Location Present", fusedLocPresent.toString(), if (fusedLocPresent) MapsStatus.OK else MapsStatus.WARN)
            addRow("Maps Initialize", mapsInitResult, mapsInitStatus)
            val rendererName = app.surrealar.SurRealApplication.activeMapsRenderer
            addRow("Maps Renderer", rendererName, when (rendererName) {
                "LATEST" -> MapsStatus.OK
                "LEGACY" -> MapsStatus.WARN
                else -> MapsStatus.ERROR
            })
            val buildType = if (app.surrealar.BuildConfig.DEBUG) "DEBUG" else "RELEASE"
            addRow("Build Type", buildType, if (app.surrealar.BuildConfig.DEBUG) MapsStatus.WARN else MapsStatus.OK)
            addRow("Package Name", ctx.packageName, null)
            // Signing SHA-1 — the value that must be allowed in the Maps key Android restriction.
            val sha1 = app.surrealar.util.diagnostics.AppSigningInfo.fingerprints(ctx)?.sha1
            addRow("Signing SHA-1", sha1 ?: "unavailable", if (sha1 != null) MapsStatus.OK else MapsStatus.ERROR)
            // API key presence only — never display the raw key (testers screenshot this screen).
            addRow("API Key Meta", app.surrealar.util.diagnostics.MapDiagnosticCollector.redactApiKey(apiKeyFull), apiKeyStatus)
            addRow("Location Permission", locationPerm.toString(), if (locationPerm) MapsStatus.OK else MapsStatus.WARN)
            addRow("Internet Permission", internetPerm.toString(), if (internetPerm) MapsStatus.OK else MapsStatus.ERROR)
            addRow("Camera Permission", cameraPerm.toString(), if (cameraPerm) MapsStatus.OK else MapsStatus.WARN)
            addRow("Network: Internet", netInfo.internetVal, netInfo.internetStatus)
            addRow("Network: Validated", netInfo.validatedVal, netInfo.validatedStatus)
            addRow("Network: Transport", netInfo.transportVal, netInfo.transportStatus)
            if (netInfo.warningVal != null) addRow("Network: Warning", netInfo.warningVal, MapsStatus.WARN)
            return mapsRowsList
        }

        // Runtime MapView test row: kept as state so it survives the card being refilled when the
        // background checks finish.
        var runtimeText = "PENDING"
        var runtimeStatus = MapsStatus.WARN
        var runtimeValueHolder: TextView? = null
        fun statusColor(status: MapsStatus?) = when (status) {
            MapsStatus.OK   -> 0xFF2E7D32.toInt()
            MapsStatus.WARN -> 0xFFF9A825.toInt()
            MapsStatus.ERROR -> 0xFFC62828.toInt()
            null -> resolveAttrColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant)
        }
        // Flush rows into the card (gathered rows + the runtime test row), capturing the runtime TextView.
        fun showRows(rows: List<Triple<String, String, MapsStatus?>>) {
            mapsRows.removeAllViews()
            val all = rows + Triple("Runtime MapView Test", runtimeText, runtimeStatus)
            all.forEachIndexed { idx, (label, value, status) ->
                val vv = mapsRows.addInfoRow(ctx, label, value, isLast = idx == all.lastIndex)
                vv.setTextColor(statusColor(status))
                if (label == "Runtime MapView Test") runtimeValueHolder = vv
            }
        }
        showRows(listOf(Triple("Diagnostics", getString(R.string.dev_checking), null)))
        categoryScope.launch {
            val rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { gather() }
            if (view.parent != null) showRows(rows)
        }

        // Add refresh bar and card to containers
        fun refresh() {
            val parent = view.parent as? ViewGroup
            if (parent != null) {
                val idx = parent.indexOfChild(view)
                parent.removeViewAt(idx)
                parent.addView(setupMapsDebugContent(inflater), idx)
            }
        }
        refreshBarContainer.addView(createRefreshBar { refresh() })
        tableContainer.addView(mapsCard)

        // Runtime MapView test. Only one live test map at a time: a refresh or another visit used to add a
        // new MapView each time (tens of MB of native/GL memory) that lived until the page was left.
        releaseDebugMap()
        val miniMapView = MapView(ctx)
        mapViewTestContainer.addView(miniMapView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        miniMapView.onCreate(null)
        val handler = Handler(Looper.getMainLooper())
        debugMap = miniMapView
        debugMapHandler = handler
        var completed = false

        fun updateRuntimeStatus(text: String, status: MapsStatus) {
            runtimeText = text
            runtimeStatus = status
            runtimeValueHolder?.text = text
            runtimeValueHolder?.setTextColor(statusColor(status))
        }
        try {
            miniMapView.getMapAsync { gMap ->
                completed = true
                updateRuntimeStatus("MAP_READY — waiting for tiles", MapsStatus.WARN)
                val mapCtx = context ?: return@getMapAsync
                MapThemeHelper.applyTheme(mapCtx, gMap, gMap.mapType)
                gMap.moveCamera(com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(LatLng(0.0,0.0), 1f))
                gMap.setOnMapLoadedCallback {
                    updateRuntimeStatus("MAP_LOADED", MapsStatus.OK)
                }
                // If MAP_LOADED hasn't fired after 10 s, tiles likely failed to load
                handler.postDelayed({
                    if (runtimeText.startsWith("MAP_READY"))
                        updateRuntimeStatus("MAP_READY — MAP_LOADED did not fire", MapsStatus.WARN)
                }, 10_000)
            }
            handler.postDelayed({ if (!completed) updateRuntimeStatus("TIMEOUT", MapsStatus.ERROR) }, 5000)
        } catch (e: Exception) {
            updateRuntimeStatus("ERROR: ${e.message}", MapsStatus.ERROR)
        }

        val lifecycle = viewLifecycleOwner.lifecycle
        val observer = object: androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) { miniMapView.onStart() }
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) { miniMapView.onResume() }
            override fun onPause(owner: androidx.lifecycle.LifecycleOwner) { miniMapView.onPause() }
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { miniMapView.onStop() }
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) { releaseDebugMap() }
        }
        lifecycle.addObserver(observer)
        debugMapObserver = lifecycle to observer
        // Switching to another category cancels this category's scope: tear the map down with it.
        categoryScope.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion {
            if (debugMap === miniMapView) Handler(Looper.getMainLooper()).post { if (debugMap === miniMapView) releaseDebugMap() }
        }

        return view
    }


    // ─────────────────────────── GNSS Live pane ──────────────────────────────

    private var debugMap: MapView? = null
    private var debugMapHandler: Handler? = null
    private var debugMapObserver: Pair<androidx.lifecycle.Lifecycle, androidx.lifecycle.DefaultLifecycleObserver>? = null

    /** Stops and destroys the Maps-debug test MapView (if any) and drops its observer and timers. */
    private fun releaseDebugMap() {
        val map = debugMap ?: return
        debugMap = null
        debugMapHandler?.removeCallbacksAndMessages(null); debugMapHandler = null
        debugMapObserver?.let { (lc, obs) -> lc.removeObserver(obs) }; debugMapObserver = null
        runCatching { map.onPause(); map.onStop(); map.onDestroy() }
        (map.parent as? ViewGroup)?.removeView(map)
    }

    /** Live GNSS viewer with source-specific sections for Internal vs RS2+ providers. */
    private fun setupGnssLiveContent(): View {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density + 0.5f).toInt()

        // Same page frame as the XML dev pages (dev_page_*.xml): 24dp top/bottom, responsive side padding.
        val sidePadding = resources.getDimensionPixelSize(R.dimen.settings_content_padding_h)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(sidePadding, dp(24f), sidePadding, dp(24f))
        }
        fun themeTextAppearance(attr: Int): Int {
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(attr, tv, true)
            return tv.resourceId
        }

        // Page title + subtitle (textAppearanceHeadlineSmall / BodyMedium, as in the XML pages)
        root.addView(TextView(ctx).apply {
            text = getString(R.string.dev_page_title_gnss)
            androidx.core.widget.TextViewCompat.setTextAppearance(this,
                themeTextAppearance(com.google.android.material.R.attr.textAppearanceHeadlineSmall))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        root.addView(TextView(ctx).apply {
            text = getString(R.string.dev_page_subtitle_gnss)
            androidx.core.widget.TextViewCompat.setTextAppearance(this,
                themeTextAppearance(com.google.android.material.R.attr.textAppearanceBodyMedium))
            alpha = 0.7f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4f)
                bottomMargin = dp(20f)
            }
        })

        // ── Control row removed – Reset button is now inline with Statistics ──

        data class SectionViews(
            val container: View,
            val values: MutableMap<String, TextView>
        )

        // ── Helper: build a key/value section as a Material 3 card ───────────
        fun buildSection(title: String, fields: List<String>): SectionViews {
            val values = mutableMapOf<String, TextView>()
            val (card, rowContainer) = buildInfoCard(ctx, title)
            fields.forEachIndexed { idx, label ->
                val vv = rowContainer.addInfoRow(ctx, label, "--", isLast = idx == fields.lastIndex)
                vv.maxLines = 3
                values[label] = vv
            }
            root.addView(card)
            return SectionViews(card, values)
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
            "Datum",
            "Latitude",
            "Longitude",
            "Orthometric Height H (receiver geoid, model unknown)",
            "Ellipsoidal Height h",
            "Geoid Separation N (receiver geoid)",
            "Horizontal Accuracy (68% radius)",
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
        val (skyCard, skyCardRows) = buildInfoCard(ctx, "Satellite Sky")
        val skyTotalView = TextView(ctx).apply {
            text = "--"
            textSize = 13f
            setTextIsSelectable(true)
            setTextColor(resolveAttrColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(20f), dp(8f), dp(20f), dp(4f))
        }
        val skyConstellationView = TextView(ctx).apply {
            text = "(no GSV data yet)"
            textSize = 12f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(resolveAttrColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(20f), dp(4f), dp(20f), dp(12f))
        }
        skyCardRows.addView(skyTotalView)
        skyCardRows.addView(View(ctx).apply {
            setBackgroundColor(dividerColor(ctx))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = dp(20f)
            }
        })
        skyCardRows.addView(skyConstellationView)
        root.addView(skyCard)

        // ── RS2+ Device (battery + device info) ──────────────────────────────
        val batterySection = buildSection("RS2+ Battery", listOf(
            "SoC", "Voltage", "Current", "USB Charger Current", "USB Charger Voltage",
            "Temperature", "Charger Status", "OTG Power"
        ))
        val deviceSection = buildSection("RS2+ Device", listOf(
            "IP", "Hostname", "Name", "Model", "Firmware", "GNSS Receiver FW",
            "Serial", "Uptime", "Storage"
        ))

        // ── RS4 / RS4 Pro tilt (status only) ──────────────────────────────────
        val tiltSection = buildSection("Receiver Tilt (RS4 / RS4 Pro)", listOf(
            "Tilt", "Warning", "Position reference", "Interpretation", "Compensated output", "Firmware", "ETC rate",
        ))

        // ── Skyplot ───────────────────────────────────────────────────────────
        val (skyplotCard, skyplotCardRows) = buildInfoCard(ctx, "Satellite Skyplot")
        val skyplotView = SkyplotView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(420f)
            )
        }
        skyplotCardRows.addView(skyplotView)
        root.addView(skyplotCard)

        // ── Signal charts (one per constellation) ─────────────────────────────
        val (signalCard, signalCardRows) = buildInfoCard(ctx, "Signal Strength")
        val constellations = listOf(
            Constellation.GPS to "GPS",
            Constellation.GLONASS to "GLONASS",
            Constellation.GALILEO to "Galileo",
            Constellation.BEIDOU to "BeiDou",
            Constellation.QZSS to "QZSS",
            Constellation.SBAS to "SBAS"
        )
        val charts = constellations.map { (c, label) ->
            val subLabel = TextView(ctx).apply {
                text = label
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(resolveAttrColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(dp(20f), dp(12f), dp(20f), dp(2f))
            }
            signalCardRows.addView(subLabel)
            val chart = SatelliteSignalChartView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(160f)
                )
                setConstellationFilter(c)
            }
            signalCardRows.addView(chart)
            c to chart
        }.toMap()
        root.addView(signalCard)

        // ── Stream stats ──────────────────────────────────────────────────────
        val btnReset = com.google.android.material.button.MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "Reset" }
        val statsSection = buildSection("Stream Statistics", listOf(
            "Lines/sec", "Error Rate", "Total Lines", "Total Errors"
        ))
        // Replace the card's section header with a header+Reset button row.
        // Card structure: card → cardContent (LinearLayout) → [header at 0, rowContainer at 1]
        val statsCardContent = (statsSection.container as? com.google.android.material.card.MaterialCardView)?.getChildAt(0) as? LinearLayout
        val statsHeaderView = statsCardContent?.getChildAt(0)
        if (statsCardContent != null && statsHeaderView != null) {
            statsCardContent.removeViewAt(0)
            statsCardContent.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(statsHeaderView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(btnReset, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(12f)
                })
            }, 0)
        }

        // ── NMEA sentence history (last 5 sentences, no inner scroll) ────────
        val (historyCard, historyCardRows) = buildInfoCard(ctx, "NMEA History (last 5)")
        val historyText = TextView(ctx).apply {
            text = "(no NMEA data yet)"
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            isFocusable = false
            isFocusableInTouchMode = false
            setTextColor(resolveAttrColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(20f), dp(8f), dp(20f), dp(16f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        historyCardRows.addView(historyText)
        root.addView(historyCard)

        // ── Live update helpers ───────────────────────────────────────────────
        val utcFmt = SimpleDateFormat("HH:mm:ss.SSS 'UTC'", Locale.US).also { it.timeZone = TimeZone.getTimeZone("UTC") }
        fun fmtM(d: Double?) = d?.let { "%.3f m".format(it) } ?: "--"
        fun fmtDeg(d: Double?) = d?.let { "%.8f°".format(it) } ?: "--"
        fun fmt2(d: Double?) = d?.let { "%.2f".format(it) } ?: "--"

        // Fields the current source has sent at least once. A null field never seen from this source
        // reads "Not reported by this receiver" (e.g. σE/σN/σU need GST, which the tablet's internal
        // GNSS never sends); one that was reporting and briefly drops keeps "--". Reset on source change.
        val seenFields = HashSet<String>()
        val notReported = "Not reported by this receiver"
        fun <T> field(key: String, value: T?, format: (T) -> String): String =
            if (value != null) { seenFields += key; format(value) }
            else if (key in seenFields) "--" else notReported

        var latestProviderLabel = "Unknown"
        var latestSkySnapshot: SkySnapshot? = null

        fun setExternalSectionVisibility(providerLabel: String) {
            val isExternal = providerLabel.contains("RS2+", ignoreCase = true) ||
                providerLabel.contains("External", ignoreCase = true)
            val caps = viewModel.receiverCapabilities.value
            // Corrections age/station come from GGA, so that section shows for any external receiver;
            // battery and device sections only when the receiver reports them.
            correctionsSection.container.visibility = if (isExternal) View.VISIBLE else View.GONE
            batterySection.container.visibility = if (isExternal && caps.hasBattery) View.VISIBLE else View.GONE
            deviceSection.container.visibility = if (isExternal && caps.hasDeviceStatus) View.VISIBLE else View.GONE
        }

        fun applyFix(fix: Fix?) {
            sourceSection.values["GNSS Source"]?.text = latestProviderLabel
            if (fix == null) {
                val noFixColor = resolveAttrColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant)
                sourceSection.values.values.forEach { it.apply { text = "--"; setTextColor(noFixColor) } }
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
            // Position. The feed is always WGS84; say when it was converted from receiver NAD83(2011).
            posSection.values["Datum"]?.text =
                if (fix.provider != io.github.pennstatecs.geokit.gnss.model.Provider.INTERNAL &&
                    viewModel.receiverDatum.value == app.surrealar.settings.model.ReceiverDatum.NAD83_2011)
                    "WGS84 (converted from receiver NAD83(2011) at fix epoch)"
                else "WGS84 / ITRF (as received)"
            posSection.values["Latitude"]?.text = fmtDeg(fix.latDeg)
            posSection.values["Longitude"]?.text = fmtDeg(fix.lonDeg)
            val m3: (Double) -> String = { "%.3f m".format(it) }
            posSection.values["Orthometric Height H (receiver geoid, model unknown)"]?.text = field("msl", fix.altMslM, m3)
            posSection.values["Ellipsoidal Height h"]?.text = field("ellH", fix.altEllipsoidalM, m3)
            posSection.values["Geoid Separation N (receiver geoid)"]?.text = field("sep", fix.geoidSeparationM, m3)
            posSection.values["Horizontal Accuracy (68% radius)"]?.text = field("hAcc", fix.hAccM, m3)
            posSection.values["Vertical Accuracy (1σ)"]?.text = field("vAcc", fix.vAccM, m3)
            posSection.values["East Precision (σE)"]?.text = field("sdE", fix.stdDevEastM, m3)
            posSection.values["North Precision (σN)"]?.text = field("sdN", fix.stdDevNorthM, m3)
            posSection.values["Up Precision (σU)"]?.text = field("sdU", fix.stdDevUpM, m3)
            // Motion
            motionSection.values["Speed"]?.text = field("speed", fix.speedMps) { "%.2f m/s  (%.1f kn)".format(it, it * 1.94384) }
            // Receivers leave course blank when stationary (RMC "…,0.000,,…"): say so rather than "--".
            motionSection.values["Course Over Ground"]?.text = when {
                fix.courseDeg != null -> { seenFields += "course"; "%.1f°".format(fix.courseDeg) }
                fix.speedMps?.let { it < 0.5 } == true -> "Not moving"
                else -> field("course", null as Double?) { "" }
            }
            // Quality
            qualitySection.values["Horizontal Dilution of Precision"]?.text = field("hdop", fix.hDop) { "%.2f".format(it) }
            qualitySection.values["Vertical Dilution of Precision"]?.text = field("vdop", fix.vDop) { "%.2f".format(it) }
            qualitySection.values["Position Dilution of Precision"]?.text = field("pdop", fix.pDop) { "%.2f".format(it) }
            // Use SkySnapshot data for satellite counts (from GSV sentences) for consistency with Satellite Sky section
            val sky = latestSkySnapshot
            qualitySection.values["Satellites Used"]?.text =
                sky?.totalUsed?.toString() ?: field("satsUsed", fix.satsUsed) { it.toString() }
            // Same fallback as the toolbar (HomeFieldStatusMapper): visible = satsVisible ?: used.
            qualitySection.values["Satellites Visible"]?.text =
                sky?.totalVisible?.toString() ?: field("satsVisible", fix.satsVisible ?: fix.satsUsed) { it.toString() }
            // Correction Age and Station ID come from Fix (NMEA GGA)
            correctionsSection.values["Correction Age"]?.text = field("diffAge", fix.diffAgeS) { "%.1f s".format(it) }
            correctionsSection.values["Correction Station ID"]?.text = field("station", fix.correctionStationId) { it }
        }

        fun applyBattery(b: io.github.pennstatecs.geokit.gnss.receiver.ReceiverBattery?) {
            batterySection.values["SoC"]?.text = b?.percent?.let { "$it%" } ?: "--"
            batterySection.values["Voltage"]?.text = b?.voltageV?.let { "%.2f V".format(it) } ?: "--"
            batterySection.values["Current"]?.text = b?.currentA?.let { "%.2f A".format(it) } ?: "--"
            // Vendor extras arrive pre-formatted in details, keyed by the row label.
            batterySection.values["USB Charger Current"]?.text = b?.details?.get("USB Charger Current") ?: "--"
            batterySection.values["USB Charger Voltage"]?.text = b?.details?.get("USB Charger Voltage") ?: "--"
            batterySection.values["Temperature"]?.text = b?.temperatureC?.let { "%.1f °C".format(it) } ?: "--"
            batterySection.values["Charger Status"]?.text = b?.chargerStatus ?: "--"
            batterySection.values["OTG Power"]?.text = b?.details?.get("OTG Power") ?: "--"
        }

        fun applyDevice(d: io.github.pennstatecs.geokit.gnss.receiver.ReceiverDeviceInfo?) {
            deviceSection.values["IP"]?.text = d?.host ?: "--"
            deviceSection.values["Hostname"]?.text = d?.hostname ?: "--"
            deviceSection.values["Name"]?.text = d?.name ?: "--"
            deviceSection.values["Model"]?.text = d?.model ?: "--"
            deviceSection.values["Firmware"]?.text = d?.firmware ?: "--"
            deviceSection.values["GNSS Receiver FW"]?.text = d?.details?.get("GNSS Receiver FW") ?: "--"
            deviceSection.values["Serial"]?.text = d?.serial ?: "--"
            deviceSection.values["Uptime"]?.text = d?.uptimeText ?: d?.uptimeS?.let {
                val days = it / 86400; val h = (it % 86400) / 3600; val m = (it % 3600) / 60
                when {
                    days > 0 -> "${days}d ${h}h ${m}m"
                    h > 0    -> "${h}h ${m}m"
                    else     -> "${m}m"
                }
            } ?: "--"
            deviceSection.values["Storage"]?.text = d?.storage?.let { s ->
                val usedPct = s.usagePercent
                "${s.usedMb} MB used / ${s.totalMb} MB total (${usedPct}%  —  ${s.availableMb} MB free)"
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

        categoryScope.launch {
            launch { viewModel.latestFix.collect { fix -> applyFix(fix) } }
            launch { viewModel.skySnapshot.collect { sky -> applySky(sky) } }
            launch { viewModel.diagnosticData.collect { data -> applyDiagnostics(data) } }
            launch { viewModel.batteryInfo.collect { b -> applyBattery(b) } }
            launch { viewModel.deviceInfo.collect { d -> applyDevice(d) } }
            launch {
                viewModel.tiltStatus.collect { st ->
                    val t = app.surrealar.receiver.tilt.TiltStatusText
                    tiltSection.values["Tilt"]?.text = if (st.applicable) t.stateLine(st).removePrefix("Tilt: ") else "not applicable (profile has no IMU)"
                    tiltSection.values["Warning"]?.text = t.warningLine(st)?.removePrefix("Tilt warning: ") ?: "none"
                    tiltSection.values["Position reference"]?.text = st.reference?.let { t.referenceLabel(it) } ?: "--"
                    tiltSection.values["Interpretation"]?.text = (st.reference?.let { t.referenceExplanation(it, st.result) } ?: "--") +
                        (st.result?.let { " (${it.reason})" } ?: "")
                    tiltSection.values["Compensated output"]?.text = st.compensatedOutput.label
                    tiltSection.values["Firmware"]?.text = st.firmware ?: "--"
                    tiltSection.values["ETC rate"]?.text = "${t.etcRateText(st)} · ${st.etcCount} total"
                }
            }
            launch { viewModel.receiverCapabilities.collect { setExternalSectionVisibility(latestProviderLabel) } }
            launch {
                viewModel.activeProviderLabel.collect { providerLabel ->
                    if (providerLabel != latestProviderLabel) seenFields.clear()   // new source: start over
                    latestProviderLabel = providerLabel
                    sourceSection.values["GNSS Source"]?.text = providerLabel
                    setExternalSectionVisibility(providerLabel)
                }
            }
        }

        btnReset.setOnClickListener {
            viewModel.resetDiagnostics()
            showDeveloperMessage("Diagnostics counters reset")
        }

        setExternalSectionVisibility(latestProviderLabel)
        return root
    }

    private fun showDeveloperMessage(message: String) {
        val v = view ?: return
        Snackbar.make(v, message, Snackbar.LENGTH_SHORT).show()
    }

    /** Resolves a theme color attribute to an ARGB int using the current context's theme. */
    private fun dividerColor(ctx: Context): Int {
        val tv = TypedValue()
        return if (ctx.theme.resolveAttribute(com.google.android.material.R.attr.colorOutlineVariant, tv, true)) tv.data
        else 0x30808080
    }

    /** Resolves any Material/theme color attribute to an ARGB int. */
    private fun resolveAttrColor(ctx: Context, attr: Int): Int {
        val tv = TypedValue()
        return if (ctx.theme.resolveAttribute(attr, tv, true)) tv.data
        else 0xFF808080.toInt()
    }

    /**
     * Builds a Material 3 outlined card containing a primary-colored section header and a
     * vertical LinearLayout for info rows. Returns the card (to add to a parent) and the
     * inner row container (to add rows into).
     */
    private fun buildInfoCard(ctx: Context, sectionTitle: String): Pair<com.google.android.material.card.MaterialCardView, LinearLayout> {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * density + 0.5f).toInt()
        val rowContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val header = TextView(ctx).apply {
            text = sectionTitle
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(resolveAttrColor(ctx, androidx.appcompat.R.attr.colorPrimary))
            setPadding(dp(20f), dp(16f), dp(20f), dp(8f))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val cardContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(header)
            addView(rowContainer)
        }
        val card = com.google.android.material.card.MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16f) }
            setCardBackgroundColor(resolveAttrColor(ctx, com.google.android.material.R.attr.colorSurface))
            strokeColor = resolveAttrColor(ctx, com.google.android.material.R.attr.colorOutlineVariant)
            strokeWidth = dp(1f)
            radius = dp(12f).toFloat()
            cardElevation = 0f
            addView(cardContent)
        }
        return card to rowContainer
    }

    /**
     * Adds a label/value info row to this LinearLayout using M3 theme colors and dp-based
     * padding. Returns the value TextView so callers can update or color-code it.
     */
    private fun LinearLayout.addInfoRow(ctx: Context, label: String, value: String, isLast: Boolean = false): TextView {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * density + 0.5f).toInt()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(48f)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20f), dp(12f), dp(20f), dp(12f))
        }
        val labelView = TextView(ctx).apply {
            text = label
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(resolveAttrColor(ctx, com.google.android.material.R.attr.colorOnSurface))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }
        val valueView = TextView(ctx).apply {
            text = value
            textSize = 13f
            setTextColor(resolveAttrColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
            setTextIsSelectable(true)
            maxLines = 4
        }
        row.addView(labelView)
        row.addView(valueView)
        addView(row)
        if (!isLast) addView(View(ctx).apply {
            setBackgroundColor(dividerColor(ctx))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = dp(20f)
            }
        })
        return valueView
    }

    // Helpers
    private fun DevelopmentFragment.dpToPx(dp: Float): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

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

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resume(null) }
        addOnCanceledListener { if (cont.isActive) cont.resume(null) }
    }

    // Add maps status enum outside the fragment for reuse
    private enum class MapsStatus { OK, WARN, ERROR }
}
