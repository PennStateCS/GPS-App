package com.example.surveyingapp

import android.content.SharedPreferences
import android.os.Bundle
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
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.surveyingapp.data.local.db.AppDatabase
import com.example.surveyingapp.databinding.ActivityMainBinding
import com.example.surveyingapp.domain.model.Fix
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.model.LocationStatus
import com.example.surveyingapp.domain.model.RtkStatus
import com.example.surveyingapp.service.EmlidBatteryService
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
import android.location.GnssStatus
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ClipDrawable
import java.util.Locale

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

    private var statusCollectJob: Job? = null
    private var batteryJob: Job? = null

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
            requestOptionalPermissions()
        } else {
            showPermissionRationale(denied.toList(), isEssential = true)
        }
    }

    private val optionalPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val denied = permissions.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            showPermissionRationale(denied.toList(), isEssential = false)
        }
        ensureLocationServiceStarted()
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
        tokenFix = findToken(R.id.token_fix)
        tokenSats = findToken(R.id.token_sats)
        tokenCoord = findToken(R.id.token_coord)
        tokenAlt = findToken(R.id.token_alt)
        tokenBatt = findToken(R.id.token_batt)

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

        // Initial Nav setup
        navController = findNavController(R.id.nav_host_fragment_content_main)
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

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment

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
                // also make sure battery is hidden when internal
                updateBatteryVisibility(false)
            } else {
                tokenFix.root.isVisible = true
                tokenFix.value.text = "--"
                tokenSats.root.isVisible = false
            }
            listOf(tokenCoord, tokenAlt, tokenBatt).forEach { it.root.isVisible = false }
        }

        // Start observers
        startStatusBarObservers()

        // Permissions flow
        requestAllPermissions()
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        statusCollectJob?.cancel()
        batteryJob?.cancel()
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
            requestOptionalPermissions()
        }
    }

    private fun requestEssentialPermissions() {
        val missing = PermissionManager.getMissingEssentialPermissions(this)
        if (missing.isNotEmpty()) {
            essentialPermissionLauncher.launch(missing.toTypedArray())
        } else {
            requestOptionalPermissions()
        }
    }

    private fun requestOptionalPermissions() {
        val missing = PermissionManager.getMissingOptionalPermissions(this)
        if (missing.isNotEmpty()) {
            optionalPermissionLauncher.launch(missing.toTypedArray())
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
        val title = if (isEssential) "Essential Permissions Required" else "Optional Permissions"
        val message = buildString {
            if (isEssential) {
                append("The following permissions are required for the app to function properly:\n\n")
            } else {
                append("The following permissions would enhance your experience but are optional:\n\n")
            }
            deniedPermissions.forEach { permission ->
                append("• ${PermissionManager.getPermissionDescription(permission)}\n")
            }
            if (isEssential) {
                append("\nPlease grant these permissions to use the app.")
            } else {
                append("\nYou can grant these permissions later in Settings if needed.")
            }
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(if (isEssential) "Grant Permissions" else "OK") { _, _ ->
                if (isEssential) requestEssentialPermissions() else ensureLocationServiceStarted()
            }
            .setNegativeButton("Cancel") { _, _ ->
                if (isEssential) showEssentialPermissionWarning() else ensureLocationServiceStarted()
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
        statusCollectJob?.cancel()
        statusCollectJob = lifecycleScope.launch {
            val settingsRepo = SurveyingApp.settingsRepo
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val manager = SurveyingApp.locationManager
                combine(
                    manager.fixFlow,
                    settingsRepo.locationSource,
                    manager.gnssStatusFlow,
                    manager.statusFlow
                ) { fix: Fix, source: LocationSourceType, gnss, status: LocationStatus ->
                    Pair(Triple(fix, source, gnss), status)
                }
                    // Sample keeps UI smooth under fast streams
                    .sample(750)
                    .distinctUntilChanged { old, new ->
                        val (oTriple, oStatus) = old
                        val (nTriple, nStatus) = new
                        val (oFix, oSrc, _) = oTriple
                        val (nFix, nSrc, _) = nTriple
                        // Only compare what actually renders
                        val same =
                            oSrc == nSrc &&
                                    oStatus::class == nStatus::class &&
                                    oFix.rtkStatus == nFix.rtkStatus &&
                                    oFix.satsUsed == nFix.satsUsed &&
                                    oFix.satsVisible == nFix.satsVisible &&
                                    // quantize to displayed precision
                                    (oFix.lat * 1e6).toLong() == (nFix.lat * 1e6).toLong() &&
                                    (oFix.lon * 1e6).toLong() == (nFix.lon * 1e6).toLong() &&
                                    (((oFix.altOrthometricM ?: oFix.altEllipsoidalM) ?: -999.0) * 100).toLong() ==
                                    (((nFix.altOrthometricM ?: nFix.altEllipsoidalM) ?: -999.0) * 100).toLong()
                        same
                    }
                    .collectLatest { (triple, status) ->
                        val (fix, source, gnss) = triple
                        updateStatusTokens(source, fix, gnss, status)
                        val showBatt = source == LocationSourceType.EXTERNAL && status is LocationStatus.Streaming
                        updateBatteryVisibility(showBatt)
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
            return
        }
        tokenBatt.root.isVisible = true
        tokenBatt.value.text = "${percentage.coerceIn(0, 100)}%"

        // Initialize and cache the layered drawable once
        if (batteryLayer == null) {
            val base = tokenBatt.icon.drawable ?: return
            batteryLayer = (base.mutate() as? LayerDrawable)
            tokenBatt.icon.setImageDrawable(batteryLayer)
            // Resolve a ClipDrawable fill layer, by id or by scanning
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

        // Update level 0..10000
        batteryFillClip?.level = (percentage.coerceIn(0, 100) * 100)

        // Pick color
        val fillColor = when {
            charging == true -> 0xFF4CAF50.toInt()
            percentage <= 15 -> 0xFFF44336.toInt()
            percentage <= 30 -> 0xFFFFC107.toInt()
            else -> 0xFF4CAF50.toInt()
        }

        // Show/hide bolt if present
        try {
            batteryLayer?.findDrawableByLayerId(R.id.battery_bolt)?.alpha = if (charging == true) 0xFF else 0x00
        } catch (_: Exception) {}

        // Try tinting inner drawable of the ClipDrawable; fall back to tinting the clip itself
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

        // Dim background when critical
        try {
            batteryLayer?.findDrawableByLayerId(R.id.battery_bg)?.alpha = if (percentage <= 15) 0x66 else 0x22
        } catch (_: Exception) {}

        batteryLayer?.invalidateSelf()
        tokenBatt.icon.invalidate()
    }

    private fun startBatteryPolling() {
        batteryJob?.cancel()
        batteryJob = lifecycleScope.launch {
            val service = EmlidBatteryService()
            while (true) {
                try {
                    val ip: String? = try {
                        SurveyingApp.settingsRepo.externalTcpHost.first()
                    } catch (_: Exception) { null }

                    val batt = if (ip.isNullOrBlank()) {
                        null
                    } else withContext(Dispatchers.IO) {
                        runCatching { service.getBattery(ip) }.getOrNull()
                    }

                    val isCharging = batt?.chargerStatus?.contains("charg", ignoreCase = true)
                    updateBatteryIcon(batt?.stateOfCharge, isCharging)
                } catch (_: Exception) {
                    updateBatteryIcon(null)
                }
                delay(15_000)
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

    private fun updateStatusTokens(source: LocationSourceType, fix: Fix, gnss: GnssStatus?, status: LocationStatus) {
        // Source token
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
            val latStr = String.format(Locale.US, "%.6f", fix.lat)
            val lonStr = String.format(Locale.US, "%.6f", fix.lon)
            tokenCoord.value.text = "$latStr, $lonStr"
            tokenCoord.root.isVisible = true

            // Altitude: prefer MSL if available
            val altMsl = fix.altOrthometricM
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
        if (status !is LocationStatus.Streaming) {
            tokenFix.root.isVisible = true
            tokenFix.value.text = "--"
            tokenFix.value.setTextColor(0xFF9E9E9E.toInt())
            tokenSats.value.text = "--"
            tokenSats.root.isVisible = true
            tokenCoord.value.text = "--"
            tokenCoord.root.isVisible = true
            tokenAlt.value.text = "--"
            tokenAlt.root.isVisible = true
            return
        }

        // FIX with color coding
        tokenFix.root.isVisible = true
        val (fixLabel, fixColor) = when (fix.rtkStatus) {
            RtkStatus.INVALID -> "--" to 0xFF9E9E9E.toInt()
            RtkStatus.SINGLE -> "Single" to 0xFFFFC107.toInt()
            RtkStatus.DGPS   -> "DGPS" to 0xFFFF9800.toInt()
            RtkStatus.FLOAT  -> "Float" to 0xFF2196F3.toInt()
            RtkStatus.FIX    -> "Fixed" to 0xFF4CAF50.toInt()
            null             -> "--" to 0xFF9E9E9E.toInt()
        }
        tokenFix.value.text = fixLabel
        tokenFix.value.setTextColor(fixColor)

        // Satellites (sanitize impossible combos)
        val su = (fix.satsUsed ?: 0).coerceAtLeast(0)
        val sv = (fix.satsVisible ?: 0).coerceAtLeast(0)
        val used = su.coerceAtMost(if (sv > 0) sv else su)
        val vis  = sv.coerceAtLeast(used)
        if (used > 0 || vis > 0) {
            tokenSats.value.text = "$used/$vis"
            tokenSats.root.isVisible = true
        } else {
            tokenSats.root.isVisible = false
        }

        // Coordinates
        val latStr = String.format(Locale.US, "%.6f", fix.lat)
        val lonStr = String.format(Locale.US, "%.6f", fix.lon)
        tokenCoord.value.text = "$latStr, $lonStr"
        tokenCoord.root.isVisible = true

        // Altitude: prefer MSL, else ellipsoidal
        val altMsl = fix.altOrthometricM
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
}
