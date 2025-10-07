package com.example.surveyingapp

import android.content.SharedPreferences
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.surveyingapp.data.local.db.AppDatabase
import com.example.surveyingapp.databinding.ActivityMainBinding
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.gnss.model.RtkStatus
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.gnss.bus.adapters.ExternalAdapter
import com.example.surveyingapp.service.LocationService
import com.example.surveyingapp.ui.openinar.OpenInARFragment
import com.example.surveyingapp.ui.settings.SettingsFragment
import com.example.surveyingapp.util.PermissionManager
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ClipDrawable
import android.os.SystemClock
import java.util.Locale
import com.example.surveyingapp.gnss.reach.ReachBatteryService
import com.example.surveyingapp.gnss.reach.ReachHttpClient
import com.example.surveyingapp.gnss.service.GnssController
import com.example.surveyingapp.gnss.source.ReplaySource
import com.example.surveyingapp.gnss.accumulator.FixAccumulator
import com.example.surveyingapp.gnss.nmea.parse.NmeaRegistry
import com.example.surveyingapp.gnss.diagnostics.DiagnosticsService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    // Single DB instance (Room handles threading); lazy avoids early init cost
    private val database by lazy { AppDatabase.getDatabase(applicationContext) }

    private lateinit var prefs: SharedPreferences
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SettingsFragment.PREF_DEV_TOOLS) {
            updateDevToolsVisibility()
        }
    }

    private var batteryJob: Job? = null

    // Hilt injected GNSS components
    @Inject lateinit var switchboard: FixSwitchboard
    @Inject lateinit var externalAdapter: ExternalAdapter
    @Inject lateinit var nmeaRegistry: NmeaRegistry
    @Inject lateinit var fixAccumulator: FixAccumulator
    @Inject lateinit var diagnosticsService: DiagnosticsService

    // Replay controller for NMEA playback
    private var replayController: GnssController? = null

    // Token view holder for status bar
    private data class TokenViews(
        val root: View,
        val label: TextView,
        val value: TextView,
        val icon: ImageView,
        val separator: TextView? = null
    )

    private lateinit var tokenSource: TokenViews
    private lateinit var tokenFix: TokenViews
    private lateinit var tokenSats: TokenViews
    private lateinit var tokenCoord: TokenViews
    private lateinit var tokenAlt: TokenViews
    private lateinit var tokenBatt: TokenViews

    // Cache the battery drawable once and mutate in place
    private var batteryLayer: LayerDrawable? = null
    private var batteryFillClip: ClipDrawable? = null

    // Permission launchers
    private val essentialPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val denied = permissions.filterValues { !it }.keys
        if (denied.isEmpty()) {
            ensureLocationServiceStarted()
        } else {
            showPermissionRationale(denied.toList(), isEssential = true)
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            showBackgroundLocationRationale()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Preferences first
        prefs = getSharedPreferences(SettingsFragment.PREFS_NAME, MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Bind token views from included layout
        tokenSource = findToken(R.id.token_source)
        tokenFix    = findToken(R.id.token_fix)
        tokenSats   = findToken(R.id.token_sats)
        tokenCoord  = findToken(R.id.token_coord)
        tokenAlt    = findToken(R.id.token_alt)
        tokenBatt   = findToken(R.id.token_batt)

        // Static labels
        tokenSource.label.setText(R.string.status_token_src)
        tokenFix.label.setText(R.string.status_token_fix)
        tokenSats.label.setText(R.string.status_token_sats)
        tokenCoord.label.setText(R.string.status_token_ll)
        tokenAlt.label.setText(R.string.status_token_alt)

        // Battery icon setup
        tokenBatt.label.text = ""
        tokenBatt.icon.setImageResource(R.drawable.battery_level)
        androidx.core.widget.ImageViewCompat.setImageTintList(tokenBatt.icon, null)
        tokenBatt.icon.scaleType = ImageView.ScaleType.FIT_XY
        tokenBatt.icon.adjustViewBounds = false
        tokenBatt.icon.minimumWidth = (32 * resources.displayMetrics.density).toInt()
        tokenBatt.icon.setPadding(0, 0, 0, 0)
        (tokenBatt.root as? View)?.minimumWidth = (48 * resources.displayMetrics.density).toInt()
        tokenBatt.icon.isVisible = true
        moveBatteryIconToRight()
        tokenBatt.root.isVisible = false

        // Trailing separators
        tokenSats.separator?.isVisible = false
        tokenAlt.separator?.isVisible = false
        tokenBatt.separator?.isVisible = false

        setSupportActionBar(binding.appBarMain.toolbar)

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView

        val devEnabled = prefs.getBoolean(SettingsFragment.PREF_DEV_TOOLS, false)

        // Fixed NavController initialization
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        navController = navHostFragment.navController

        val initialTopLevel = mutableSetOf(
            R.id.nav_home,
            R.id.nav_models,
            R.id.nav_view_coordinates,
            R.id.nav_render_map,
            R.id.nav_open_in_ar,
            R.id.nav_settings
        )
        if (devEnabled) initialTopLevel.add(R.id.nav_development)
        appBarConfiguration = AppBarConfiguration(initialTopLevel, drawerLayout)
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)
        updateDevToolsVisibility()

        // RS2 visibility initial + updates
        lifecycleScope.launch {
            val src = SurveyingApp.settingsRepo.locationSource.first()
            updateRs2Visibility(src == LocationSourceType.EXTERNAL)
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SurveyingApp.settingsRepo.locationSource
                    .distinctUntilChanged()
                    .collectLatest { src -> updateRs2Visibility(src == LocationSourceType.EXTERNAL) }
            }
        }

        // Attach DAO for OpenInAR
        navHostFragment.childFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager,
                    f: Fragment,
                    v: View,
                    savedInstanceState: Bundle?
                ) {
                    if (f is OpenInARFragment) {
                        f.attachCoordinateDao(database.coordinateDao())
                    }
                }
            },
            true
        )

        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id == R.id.nav_open_in_ar) {
                navHostFragment.childFragmentManager.executePendingTransactions()
                val current = navHostFragment.childFragmentManager.primaryNavigationFragment
                    ?: navHostFragment.childFragmentManager.fragments.firstOrNull()
                if (current is OpenInARFragment) current.attachCoordinateDao(database.coordinateDao())
            }
        }

        // Pre-populate minimal status based on initial source
        lifecycleScope.launch {
            val initialSource = SurveyingApp.settingsRepo.locationSource.first()
            val srcLabel = if (initialSource == LocationSourceType.INTERNAL) "Internal" else "RS2+"
            tokenSource.value.text = srcLabel
            tokenSource.separator?.isVisible = initialSource != LocationSourceType.INTERNAL
            if (initialSource == LocationSourceType.INTERNAL) {
                tokenFix.root.isVisible = false
                tokenSats.root.isVisible = false
                updateBatteryVisibility(false)
            } else {
                tokenFix.root.isVisible = true
                tokenFix.value.text = "--"
                tokenSats.root.isVisible = false
            }
            listOf(tokenCoord, tokenAlt, tokenBatt).forEach { it.root.isVisible = false }
        }

        // Permissions flow
        requestAllPermissions()

        // Initialize GNSS graph routing & observers BEFORE status observers
        initGnss()

        // Start observers AFTER GNSS init
        startStatusBarObservers()
    }

    private fun initGnss() {
        // Idempotent
        try {
            switchboard.start()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error starting GNSS switchboard: ", e)
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Routed fixes (follows provider choice)
                launch {
                    try {
                        switchboard.fixes.collect { fix ->
                            // TODO: push into VM / UI as needed
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Error collecting GNSS fixes: ", e)
                    }
                }
                // Routed sky (follows provider choice)
                launch {
                    try {
                        switchboard.sky.collect { sky ->
                            // TODO: update sky UI
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Error collecting GNSS sky data: ", e)
                    }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        batteryJob?.cancel()
        replayController?.stop()
    }

    private fun moveBatteryIconToRight() {
        val batteryContainer = tokenBatt.root as? android.widget.LinearLayout ?: return
        batteryContainer.removeView(tokenBatt.icon)
        val labelIndex = batteryContainer.indexOfChild(tokenBatt.label)
        batteryContainer.addView(tokenBatt.icon, labelIndex + 1)
        (tokenBatt.icon.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
            val marginPx = (10 * resources.displayMetrics.density).toInt()
            lp.width = (32 * resources.displayMetrics.density).toInt()
            lp.height = (12 * resources.displayMetrics.density).toInt()
            lp.setMargins(marginPx, 0, 0, 0)
            tokenBatt.icon.layoutParams = lp
            tokenBatt.icon.requestLayout()
            batteryContainer.requestLayout()
        }
    }

    private fun findToken(id: Int): TokenViews {
        val root = findViewById<ViewGroup>(id)
        val label = root.findViewById<TextView>(R.id.label)
        val value = root.findViewById<TextView>(R.id.value)
        val icon = root.findViewById<ImageView>(R.id.icon)
        val sep = root.findViewById<TextView?>(R.id.separator)
        return TokenViews(root, label, value, icon, sep)
    }

    /** Permission flow */
    private fun requestAllPermissions() {
        if (!PermissionManager.hasEssentialPermissions(this)) {
            requestEssentialPermissions()
        } else {
            ensureLocationServiceStarted()
        }
    }

    private fun requestEssentialPermissions() {
        val missing = PermissionManager.getMissingEssentialPermissions(this)
        if (missing.isNotEmpty()) {
            essentialPermissionLauncher.launch(missing.toTypedArray())
        } else {
            ensureLocationServiceStarted()
        }
    }

    private fun requestBackgroundLocationPermission() {
        if (PermissionManager.hasLocationPermissions(this) &&
            !PermissionManager.hasBackgroundLocationPermission(this)
        ) {
            backgroundLocationLauncher.launch(PermissionManager.BACKGROUND_LOCATION_PERMISSION)
        }
    }

    private fun showPermissionRationale(deniedPermissions: List<String>, isEssential: Boolean) {
        val title = "Essential Permissions Required"
        val message = buildString {
            append("The following permissions are required for the app to function properly:\n\n")
            deniedPermissions.forEach { permission ->
                append("• ${PermissionManager.getPermissionDescription(permission)}\n")
            }
            append("\nPlease grant these permissions to use the app.")
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Grant Permissions") { _, _ ->
                requestEssentialPermissions()
            }
            .setNegativeButton("Cancel") { _, _ ->
                showEssentialPermissionWarning()
            }
            .setCancelable(false)
            .show()
    }

    private fun showEssentialPermissionWarning() {
        AlertDialog.Builder(this)
            .setTitle("Warning")
            .setMessage("Some core features may not work without the required permissions. You can grant them later in the app settings.")
            .setPositiveButton("OK") { _, _ -> ensureLocationServiceStarted() }
            .show()
    }

    private fun showBackgroundLocationRationale() {
        AlertDialog.Builder(this)
            .setTitle("Background Location")
            .setMessage(
                "Background location access allows continuous tracking when the app is not actively being used. " +
                        "This is useful for extended surveying sessions.\n\nYou can enable this later in Settings > Location if needed."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun ensureLocationServiceStarted() {
        if (!PermissionManager.hasLocationPermissions(this)) return
        LocationService.start(this)
        lifecycleScope.launch {
            delay(3000)
            if (PermissionManager.hasLocationPermissions(this@MainActivity) &&
                !PermissionManager.hasBackgroundLocationPermission(this@MainActivity)
            ) {
                requestBackgroundLocationPermission()
            }
        }
    }

    /** Status bar observers */
    private fun startStatusBarObservers() {
        lifecycleScope.launch {
            val settingsRepo = SurveyingApp.settingsRepo
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    switchboard.fixes, // updated to use injected switchboard
                    settingsRepo.locationSource
                ) { fix: Fix, source: LocationSourceType -> fix to source }
                    .sample(750)
                    .distinctUntilChanged { (oFix, oSrc), (nFix, nSrc) ->
                        oSrc == nSrc &&
                                oFix.rtkStatus == nFix.rtkStatus &&
                                oFix.satsUsed == nFix.satsUsed &&
                                oFix.satsVisible == nFix.satsVisible &&
                                (oFix.latDeg * 1e6).toLong() == (nFix.latDeg * 1e6).toLong() &&
                                (oFix.lonDeg * 1e6).toLong() == (nFix.lonDeg * 1e6).toLong() &&
                                (((oFix.altMslM ?: oFix.altEllipsoidalM) ?: -999.0) * 100).toLong() ==
                                (((nFix.altMslM ?: nFix.altEllipsoidalM) ?: -999.0) * 100).toLong()
                    }
                    .catch { e ->
                        android.util.Log.e("MainActivity", "Error in GNSS status bar observer: ", e)
                        // Optionally update UI to show error state
                        runOnUiThread {
                            tokenSource.value.text = "--"
                            tokenFix.value.text = "--"
                            tokenSats.value.text = "--"
                            tokenCoord.value.text = "--"
                            tokenAlt.value.text = "--"
                            tokenBatt.value.text = "--"
                        }
                    }
                    .collectLatest { (fix, source) ->
                        updateStatusTokens(source, fix)
                        updateBatteryVisibility(source == LocationSourceType.EXTERNAL)
                    }
            }
        }
    }

    private fun updateBatteryVisibility(shouldShow: Boolean) {
        if (shouldShow) {
            tokenAlt.separator?.isVisible = true
            if (batteryJob == null) startBatteryPolling()
        } else {
            tokenAlt.separator?.isVisible = false
            tokenBatt.root.isVisible = false
            batteryJob?.cancel(); batteryJob = null
        }
    }

    private fun updateBatteryIcon(percentage: Int?, charging: Boolean? = null) {
        // Hide if no data
        if (percentage == null) {
            tokenBatt.root.isVisible = false
            tokenBatt.value.text = "" // Clear stale text to prevent flash when becoming visible again
            return
        }
        tokenBatt.root.isVisible = true
        tokenBatt.value.text = "${percentage.coerceIn(0, 100)}%"

        if (batteryLayer == null) {
            val base = tokenBatt.icon.drawable ?: return
            batteryLayer = (base.mutate() as? LayerDrawable)
            tokenBatt.icon.setImageDrawable(batteryLayer)
            batteryFillClip = (batteryLayer?.findDrawableByLayerId(R.id.battery_fill) as? ClipDrawable)
                ?: run {
                    var found: ClipDrawable? = null
                    try {
                        val count = batteryLayer?.numberOfLayers ?: 0
                        for (i in 0 until count) {
                            val d = batteryLayer?.getDrawable(i)
                            if (d is ClipDrawable) { found = d; break }
                        }
                    } catch (_: Exception) {}
                    found
                }
        }

        batteryFillClip?.level = (percentage.coerceIn(0, 100) * 100)

        val fillColor = when {
            charging == true -> 0xFF4CAF50.toInt()
            percentage <= 15 -> 0xFFF44336.toInt()
            percentage <= 30 -> 0xFFFFC107.toInt()
            else -> 0xFF4CAF50.toInt()
        }

        try {
            batteryLayer?.findDrawableByLayerId(R.id.battery_bolt)?.alpha = if (charging == true) 0xFF else 0x00
        } catch (_: Exception) {}

        val clip = batteryFillClip
        val inner = try { clip?.drawable } catch (_: Exception) { null }
        var tinted = false
        try {
            if (inner is android.graphics.drawable.GradientDrawable) {
                inner.setColor(fillColor); tinted = true
            }
        } catch (_: Exception) {}
        try {
            if (inner != null) {
                androidx.core.graphics.drawable.DrawableCompat.setTint(inner, fillColor); tinted = true
            }
        } catch (_: Exception) {}
        if (!tinted) {
            try { clip?.let { androidx.core.graphics.drawable.DrawableCompat.setTint(it, fillColor) } } catch (_: Exception) {}
        }

        try {
            batteryLayer?.findDrawableByLayerId(R.id.battery_bg)?.alpha = if (percentage <= 15) 0x66 else 0x22
        } catch (_: Exception) {}

        batteryLayer?.invalidateSelf()
        tokenBatt.icon.invalidate()
    }

    private fun startBatteryPolling() {
        batteryJob?.cancel()
        batteryJob = lifecycleScope.launch {
            var consecutiveFailures = 0
            var lastLoggedFailure = 0L
            var batteryHidden = false
            var hideStartTime = 0L

            var lastIp: String? = null
            var client: ReachHttpClient? = null
            var service: ReachBatteryService? = null

            while (true) {
                try {
                    val ip = runCatching { SurveyingApp.settingsRepo.externalTcpHost.first() }.getOrNull()

                    if (ip.isNullOrBlank()) {
                        if (!batteryHidden) {
                            updateBatteryIcon(null)
                            batteryHidden = true
                            hideStartTime = SystemClock.elapsedRealtime()
                            consecutiveFailures = 0
                        }
                        delay(60_000)
                        continue
                    }

                    if (ip != lastIp) {
                        try {
                            client = ReachHttpClient(ip)
                            service = ReachBatteryService(client!!)
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Error initializing battery service: ", e)
                            updateBatteryIcon(null)
                            delay(30_000)
                            continue
                        }
                        lastIp = ip
                    }

                    if (batteryHidden && SystemClock.elapsedRealtime() - hideStartTime >= 60_000) {
                        batteryHidden = false
                        consecutiveFailures = 0
                    }
                    if (batteryHidden) { delay(15_000); continue }

                    val batt = try {
                        withContext(Dispatchers.IO) { runCatching { service?.read() }.getOrNull() }
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Error reading battery data: ", e)
                        null
                    }

                    if (batt?.percent != null) {
                        consecutiveFailures = 0
                        val isCharging = batt.chargerStatus?.contains("charg", ignoreCase = true)
                        updateBatteryIcon(batt.percent, isCharging)
                        delay(15_000)
                    } else {
                        consecutiveFailures++
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastLoggedFailure >= 60_000) {
                            android.util.Log.w("MainActivity", "Battery fetch failed for $ip (#$consecutiveFailures)")
                            lastLoggedFailure = now
                        }
                        if (consecutiveFailures >= 3 && !batteryHidden) {
                            updateBatteryIcon(null)
                            batteryHidden = true
                            hideStartTime = SystemClock.elapsedRealtime()
                        }
                        delay( when (consecutiveFailures) { 1 -> 15_000L; 2 -> 30_000L; else -> 45_000L } )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Battery polling error: ", e)
                    if (!batteryHidden) {
                        updateBatteryIcon(null)
                        batteryHidden = true
                        hideStartTime = SystemClock.elapsedRealtime()
                    }
                    delay(60_000)
                }
            }
        }
    }


    private fun updateDevToolsVisibility() {
        val devEnabled = prefs.getBoolean(SettingsFragment.PREF_DEV_TOOLS, false)
        val navView: NavigationView = binding.navView
        val menu = navView.menu
        val devMenuItem = menu.findItem(R.id.nav_development)
        devMenuItem?.isVisible = devEnabled

        val topLevel = mutableSetOf(
            R.id.nav_home,
            R.id.nav_models,
            R.id.nav_view_coordinates,
            R.id.nav_render_map,
            R.id.nav_open_in_ar,
            R.id.nav_settings
        )
        if (devEnabled) topLevel.add(R.id.nav_development)

        lifecycleScope.launch {
            val src = try { SurveyingApp.settingsRepo.locationSource.first() } catch (_: Exception) { LocationSourceType.INTERNAL }
            if (src == LocationSourceType.EXTERNAL) {
                topLevel.add(R.id.nav_rs2)
            }
            appBarConfiguration = AppBarConfiguration(topLevel, binding.drawerLayout)
            setupActionBarWithNavController(navController, appBarConfiguration)
        }
    }

    private fun updateRs2Visibility(shouldShow: Boolean) {
        val navView: NavigationView = binding.navView
        val menu = navView.menu
        val rs2MenuItem = menu.findItem(R.id.nav_rs2)
        rs2MenuItem?.isVisible = shouldShow

        val topLevel = mutableSetOf(
            R.id.nav_home,
            R.id.nav_models,
            R.id.nav_view_coordinates,
            R.id.nav_render_map,
            R.id.nav_open_in_ar,
            R.id.nav_settings
        )
        if (shouldShow) topLevel.add(R.id.nav_rs2)
        val devEnabled = prefs.getBoolean(SettingsFragment.PREF_DEV_TOOLS, false)
        if (devEnabled) topLevel.add(R.id.nav_development)

        appBarConfiguration = AppBarConfiguration(topLevel, binding.drawerLayout)
        setupActionBarWithNavController(navController, appBarConfiguration)
    }

    private fun updateStatusTokens(
        source: LocationSourceType,
        fix: Fix
    ) {
        val srcLabel = if (source == LocationSourceType.INTERNAL) "Internal" else "RS2+"
        tokenSource.value.text = srcLabel

        val isInternal = source == LocationSourceType.INTERNAL
        tokenSource.separator?.isVisible = !isInternal
        if (isInternal) {
            // Hide FIX/SATS entirely
            tokenFix.root.isVisible = false
            tokenSats.root.isVisible = false
            // Hide battery immediately when internal
            updateBatteryVisibility(false)

            // Coordinates
            val latStr = String.format(Locale.US, "%.6f", fix.latDeg)
            val lonStr = String.format(Locale.US, "%.6f", fix.lonDeg)
            tokenCoord.value.text = "$latStr, $lonStr"
            tokenCoord.root.isVisible = true

            // Altitude: prefer MSL if available
            val altMsl = fix.altMslM
            val altEllip = fix.altEllipsoidalM
            when {
                altMsl != null -> {
                    tokenAlt.value.text = String.format(Locale.US, "%.2f", altMsl) + "m"
                    tokenAlt.root.isVisible = true
                }
                altEllip != null -> {
                    tokenAlt.value.text = String.format(Locale.US, "%.2f", altEllip) + "m"
                    tokenAlt.root.isVisible = true
                }
                else -> tokenAlt.root.isVisible = false
            }
            return
        }

        // External (RS2+)
        tokenFix.root.isVisible = true
        val (fixLabel, fixColor) = when (fix.rtkStatus) {
            RtkStatus.NONE    -> "--"      to 0xFF9E9E9E.toInt()
            RtkStatus.SINGLE  -> "Single"  to 0xFFFF5722.toInt()
            RtkStatus.DGPS    -> "DGPS"    to 0xFFFF9800.toInt()
            RtkStatus.FLOAT   -> "Float"   to 0xFF2196F3.toInt()
            RtkStatus.FIX     -> "Fixed"   to 0xFF4CAF50.toInt()
            RtkStatus.DEAD_RECKONING -> "DR" to 0xFFFF9800.toInt()
            RtkStatus.INVALID -> "Invalid" to 0xFFF44336.toInt()
        }
        tokenFix.value.text = fixLabel
        tokenFix.value.setTextColor(fixColor)

        // Satellites (sanitize impossible combos)
        val used = fix.satsUsed.coerceAtLeast(0)
        val vis  = (fix.satsVisible ?: used).coerceAtLeast(used)
        if (used > 0 || vis > 0) {
            tokenSats.value.text = "$used/$vis"
            tokenSats.root.isVisible = true
        } else {
            tokenSats.root.isVisible = false
        }

        // Coordinates
        val latStr = String.format(Locale.US, "%.6f", fix.latDeg)
        val lonStr = String.format(Locale.US, "%.6f", fix.lonDeg)
        tokenCoord.value.text = "$latStr, $lonStr"
        tokenCoord.root.isVisible = true

        // Altitude: prefer MSL, else ellipsoidal
        val altMsl = fix.altMslM
        val altEllip = fix.altEllipsoidalM
        when {
            altMsl != null -> {
                tokenAlt.value.text = String.format(Locale.US, "%.2f", altMsl) + "m"
                tokenAlt.root.isVisible = true
            }
            altEllip != null -> {
                tokenAlt.value.text = String.format(Locale.US, "%.2f", altEllip) + "m"
                tokenAlt.root.isVisible = true
            }
            else -> tokenAlt.root.isVisible = false
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_replay -> {
                showNmeaFileSelectionDialog()
                true
            }
            R.id.action_diagnostics -> {
                navController.navigate(R.id.nav_diagnostics)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Shows a dialog to select available NMEA files from assets and start replay
     */
    private fun showNmeaFileSelectionDialog() {
        lifecycleScope.launch {
            try {
                // Get list of .nmea files from assets
                val nmeaFiles = assets.list("")?.filter { it.endsWith(".nmea") } ?: emptyList()

                if (nmeaFiles.isEmpty()) {
                    runOnUiThread {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("No NMEA Files")
                            .setMessage("No .nmea files found in assets folder.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    return@launch
                }

                runOnUiThread {
                    val fileNames = nmeaFiles.toTypedArray()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Select NMEA File for Replay")
                        .setItems(fileNames) { _, which ->
                            val selectedFile = fileNames[which]
                            startNmeaReplay(selectedFile)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error listing NMEA files", e)
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Error")
                        .setMessage("Failed to list NMEA files: ${e.message}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    /**
     * Starts NMEA replay from the selected asset file
     */
    private fun startNmeaReplay(fileName: String) {
        try {
            // Stop any existing replay
            replayController?.stop()

            // Create new replay source
            val replaySource = ReplaySource(
                context = this,
                assetFileName = fileName,
                delayBetweenLines = 1000L, // 1 second between NMEA sentences
                name = "Replay ($fileName)"
            )

            // Create new controller with replay source
            replayController = GnssController(
                scope = lifecycleScope,
                source = replaySource,
                registry = nmeaRegistry,
                accumulator = fixAccumulator
            )

            // Start the replay
            replayController?.start()

            // Update status to show replay is active
            tokenSource.value.text = "Replay"

            // Show confirmation dialog with option to stop
            AlertDialog.Builder(this)
                .setTitle("NMEA Replay Started")
                .setMessage("Replaying NMEA data from $fileName\n\nThis will simulate GNSS data for demo and testing purposes.")
                .setPositiveButton("Stop Replay") { _, _ ->
                    stopNmeaReplay()
                }
                .setNegativeButton("Keep Running", null)
                .show()

            android.util.Log.i("MainActivity", "Started NMEA replay from $fileName")

        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error starting NMEA replay", e)
            AlertDialog.Builder(this)
                .setTitle("Replay Error")
                .setMessage("Failed to start NMEA replay: ${e.message}")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    /**
     * Stops the current NMEA replay
     */
    private fun stopNmeaReplay() {
        try {
            replayController?.stop()
            replayController = null

            // Reset status display
            lifecycleScope.launch {
                val currentSource = SurveyingApp.settingsRepo.locationSource.first()
                val srcLabel = if (currentSource == LocationSourceType.INTERNAL) "Internal" else "RS2+"
                tokenSource.value.text = srcLabel
            }

            android.util.Log.i("MainActivity", "Stopped NMEA replay")

        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error stopping NMEA replay", e)
        }
    }
}
