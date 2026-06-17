package com.example.surveyingapp

import android.content.Intent
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.gnss.model.RtkStatus
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.gnss.bus.adapters.ExternalAdapter
import com.example.surveyingapp.databinding.ActivityMainBinding
import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.service.LocationService
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


    @Inject lateinit var settingsRepository: SettingsRepository

    private var batteryJob: Job? = null

    // Track last GNSS data update time for stale detection
    @Volatile
    private var lastDataUpdateTime = System.currentTimeMillis()

    // Track latest sky snapshot for accurate satellite counts
    @Volatile
    private var latestSkySnapshot: com.example.surveyingapp.gnss.model.SkySnapshot? = null

    // Hilt injected GNSS components
    @Inject lateinit var switchboard: FixSwitchboard
    @Inject lateinit var externalAdapter: ExternalAdapter
    @Inject lateinit var nmeaRegistry: NmeaRegistry
    @Inject lateinit var fixAccumulator: FixAccumulator
    @Inject lateinit var diagnosticsService: DiagnosticsService
    @Inject lateinit var sourceSettings: com.example.surveyingapp.gnss.settings.SourceSettings

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

    // Prevent repeated essential permission dialogs while one is already showing
    private var showingEssentialRationale: Boolean = false

    // Permission launchers
    private val essentialPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.filterValues { it }.keys
        val denied  = permissions.filterValues { !it }.keys
        android.util.Log.d("MainActivity", "Essential permission result: granted=$granted, denied=$denied")

        // Reset guard so future requests are never blocked
        showingEssentialRationale = false

        // Use ground-truth check (covers permissions not in this batch)
        val stillMissing = PermissionManager.getMissingEssentialPermissions(this)
        if (stillMissing.isEmpty()) {
            android.util.Log.d("MainActivity", "All essential permissions granted")
            ensureLocationServiceStarted()
        } else {
            android.util.Log.d("MainActivity", "Still missing: ${stillMissing.joinToString()}")
            // Distinguish: can we still ask, or did the user permanently deny?
            if (PermissionManager.isPermanentlyDenied(this, stillMissing)) {
                showPermanentlyDeniedDialog(stillMissing)
            } else {
                showPermissionRationale(stillMissing)
            }
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
        appBarConfiguration = AppBarConfiguration(initialTopLevel, drawerLayout)
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepository.developerSettings.collect { devSettings ->
                    updateDevToolsVisibility(devSettings.developerToolsEnabled)
                }
            }
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id == R.id.nav_open_in_ar) {
                navHostFragment.childFragmentManager.executePendingTransactions()
            }
        }

        // Pre-populate initial status tokens with placeholders
        lifecycleScope.launch {
            val initialProvider = try { 
                sourceSettings.activeProvider.first() 
            } catch (_: Exception) { 
                com.example.surveyingapp.gnss.settings.SourceSettings.ProviderChoice.INTERNAL 
            }
            
            val isInternal = initialProvider == com.example.surveyingapp.gnss.settings.SourceSettings.ProviderChoice.INTERNAL
            val srcLabel = if (isInternal) "Internal" else "RS2+"
            
            // Set initial source label
            tokenSource.value.text = srcLabel
            tokenSource.separator?.isVisible = !isInternal
            
            // Set visibility based on source type
            tokenFix.root.isVisible = !isInternal
            if (!isInternal) {
                tokenFix.value.text = "--"
                tokenFix.value.setTextColor(0xFF9E9E9E.toInt())
            }
            
            tokenSats.root.isVisible = false // Hidden until first fix with sat data
            
            // Show coordinate placeholder
            tokenCoord.value.text = "--"
            tokenCoord.root.isVisible = true
            
            // Hide altitude until first fix
            tokenAlt.root.isVisible = false
            
            // Battery only for external
            tokenBatt.root.isVisible = false
            updateBatteryVisibility(!isInternal)
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
                        if (e is kotlinx.coroutines.CancellationException) throw e
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
                        if (e is kotlinx.coroutines.CancellationException) throw e
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
        val missing = PermissionManager.getMissingEssentialPermissions(this)
        if (missing.isEmpty()) {
            ensureLocationServiceStarted()
        } else {
            android.util.Log.d("MainActivity", "Requesting essential permissions: ${missing.joinToString()}")
            essentialPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requestBackgroundLocationPermission() {
        if (PermissionManager.hasLocationPermissions(this) &&
            !PermissionManager.hasBackgroundLocationPermission(this)
        ) {
            backgroundLocationLauncher.launch(PermissionManager.BACKGROUND_LOCATION_PERMISSION)
        }
    }

    // Storage: this app uses app-scoped storage and SAF for user-picked files.
    // No broad storage permission is needed. This method is kept as a no-op entry point
    // so that call sites don't need updating, but it no longer forces the user through
    // the MANAGE_EXTERNAL_STORAGE settings screen on every launch.
    @Suppress("unused")
    fun requestStoragePermissions() {
        // Nothing to do — app-scoped storage (getFilesDir / getExternalFilesDir) requires
        // no runtime permission, and user file-picking uses SAF (ACTION_OPEN_DOCUMENT).
        android.util.Log.d("MainActivity", "requestStoragePermissions: using scoped storage — no runtime permission needed")
    }

    override fun onResume() {
        super.onResume()

        // Note: choreographer/frame callbacks belong in ModelViewerActivity; do not call them from MainActivity
    }

    /**
     * Shows a rationale dialog explaining WHY the permissions are needed,
     * then re-launches the system permission dialog on confirmation.
     * Only called when shouldShowRequestPermissionRationale() returned true
     * (i.e., the user denied once but did NOT select "Don't ask again").
     */
    private fun showPermissionRationale(missingPermissions: List<String>) {
        if (showingEssentialRationale) return
        showingEssentialRationale = true

        android.util.Log.d("MainActivity", "Showing permission rationale for: ${missingPermissions.joinToString()}")

        val message = buildString {
            append("This app needs the following permissions to work properly:\n\n")
            missingPermissions.forEach { perm ->
                append("• ${PermissionManager.getPermissionDisplayName(perm)} — ")
                append(PermissionManager.getPermissionDescription(perm))
                append("\n")
            }
            append("\nPlease tap \"Grant\" to continue.")
        }

        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage(message)
            .setPositiveButton("Grant") { _, _ ->
                showingEssentialRationale = false
                essentialPermissionLauncher.launch(missingPermissions.toTypedArray())
            }
            .setNegativeButton("Not now") { _, _ ->
                showingEssentialRationale = false
                // Allow the app to continue in a degraded state
                ensureLocationServiceStarted()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Shows a dialog directing the user to app Settings because the permissions
     * were permanently denied ("Don't ask again" was selected).
     * We cannot show the system dialog again; only Settings can fix this.
     */
    private fun showPermanentlyDeniedDialog(missingPermissions: List<String>) {
        android.util.Log.d("MainActivity", "Permissions permanently denied: ${missingPermissions.joinToString()}")

        val names = missingPermissions.joinToString(", ") { PermissionManager.getPermissionDisplayName(it) }
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage(
                "The following permissions were permanently denied:\n\n$names\n\n" +
                "Please open App Settings and grant them manually under Permissions."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", packageName, null)
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Cannot open app settings", e)
                }
            }
            .setNegativeButton("Continue without") { _, _ ->
                ensureLocationServiceStarted()
            }
            .setCancelable(false)
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

        // Single consolidated observer for all status bar updates
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var consecutiveErrors = 0

                combine(
                    switchboard.fixes,
                    switchboard.sky,
                    sourceSettings.activeProvider
                ) { fix, sky, provider ->
                    Triple(fix, sky, provider)
                }
                    .catch { e ->
                        android.util.Log.e("MainActivity", "Error in GNSS status bar observer: ", e)
                        consecutiveErrors++
                        if (consecutiveErrors >= 3) {
                            // After 3 consecutive errors, reset tokens
                            withContext(Dispatchers.Main) {
                                tokenSource.value.text = "Error"
                                tokenFix.value.text = "--"
                                tokenSats.value.text = "--"
                                tokenCoord.value.text = "--"
                                tokenAlt.value.text = "--"
                                updateBatteryVisibility(false)
                            }
                        }
                        // Emit a retry signal after delay
                        delay(5000)
                        emitAll(flowOf())
                    }
                    .collectLatest { (fix, sky, provider) ->
                        consecutiveErrors = 0 // Reset error counter on success
                        lastDataUpdateTime = System.currentTimeMillis() // Update the shared timestamp
                        latestSkySnapshot = sky // Store latest sky snapshot

                        val source = if (provider == com.example.surveyingapp.gnss.settings.SourceSettings.ProviderChoice.INTERNAL) {
                            LocationSourceType.INTERNAL
                        } else {
                            LocationSourceType.EXTERNAL
                        }

                        android.util.Log.d("MainActivity", "Updating status tokens: provider=$provider, source=$source, RTK=${fix.rtkStatus}, fix_sats=${fix.satsUsed}, sky_sats=${sky.totalUsed}/${sky.totalVisible}")

                        withContext(Dispatchers.Main) {
                            updateStatusTokens(source, fix, sky)
                            updateBatteryVisibility(source == LocationSourceType.EXTERNAL)
                            // Reset alpha when fresh data arrives
                            tokenSource.value.alpha = 1.0f
                        }
                    }
            }
        }

        // Stale data detection - dim source token if no updates for 15 seconds
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(5_000) // Check every 5 seconds
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastDataUpdateTime

                    // If we haven't seen an update in 15+ seconds, show stale indicator
                    withContext(Dispatchers.Main) {
                        if (elapsed > 15_000) {
                            // Dim the source token to indicate stale data
                            tokenSource.value.alpha = 0.5f
                        } else {
                            tokenSource.value.alpha = 1.0f
                        }
                    }
                }
            }
        }
    }

    private fun updateBatteryVisibility(shouldShow: Boolean) {
        if (shouldShow) {
            tokenAlt.separator?.isVisible = true
            // Show token immediately with placeholder so the user knows it's present
            // while the HTTP poll is in flight; the icon / value are filled once data arrives.
            if (!tokenBatt.root.isVisible) {
                tokenBatt.value.text = "--"
                tokenBatt.root.isVisible = true
            }
            if (batteryJob == null) startBatteryPolling()
        } else {
            tokenAlt.separator?.isVisible = false
            tokenBatt.root.isVisible = false
            batteryJob?.cancel(); batteryJob = null
        }
    }

    private fun updateBatteryIcon(percentage: Int?, charging: Boolean? = null) {
        // No data: show "--" placeholder if token is already visible (external mode),
        // otherwise keep it hidden.
        if (percentage == null) {
            if (tokenBatt.root.isVisible) {
                tokenBatt.value.text = "--"
                // Reset the fill so the bar looks empty
                batteryFillClip?.level = 0
                try { batteryLayer?.findDrawableByLayerId(R.id.battery_bolt)?.alpha = 0x00 } catch (_: Exception) {}
            }
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

            android.util.Log.d("MainActivity", "Battery polling started")

            while (true) {
                try {
                    // Get configured IP from settings
                    val ip = runCatching {
                        SurveyingApp.settingsRepo.externalTcpHost.first()
                    }.getOrNull()

                    // No IP configured - hide battery and wait
                    if (ip.isNullOrBlank()) {
                        if (!batteryHidden) {
                            android.util.Log.d("MainActivity", "No IP configured, hiding battery")
                            updateBatteryIcon(null)
                            batteryHidden = true
                            hideStartTime = SystemClock.elapsedRealtime()
                            consecutiveFailures = 0
                        }
                        delay(60_000)
                        continue
                    }

                    // IP changed - reinitialize client
                    if (ip != lastIp) {
                        android.util.Log.d("MainActivity", "Battery IP changed to: $ip")

                        try {
                            client = ReachHttpClient(ip)
                            service = ReachBatteryService(client)
                            lastIp = ip
                            consecutiveFailures = 0 // Reset failures on new IP
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Error initializing battery service for $ip", e)
                            updateBatteryIcon(null)
                            delay(30_000)
                            continue
                        }
                    }

                    // Auto-unhide after 60 seconds if hidden
                    if (batteryHidden && SystemClock.elapsedRealtime() - hideStartTime >= 60_000) {
                        android.util.Log.d("MainActivity", "Auto-unhiding battery after timeout")
                        batteryHidden = false
                        consecutiveFailures = 0
                    }

                    // Skip fetch if hidden (waiting for timeout)
                    if (batteryHidden) {
                        delay(15_000)
                        continue
                    }

                    // Fetch battery data
                    val batt = try {
                        withContext(Dispatchers.IO) {
                            runCatching { service?.read() }.getOrNull()
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Error reading battery data", e)
                        null
                    }

                    // Process battery data
                    if (batt?.percent != null) {
                        consecutiveFailures = 0
                        val isCharging = batt.chargerStatus?.contains("charg", ignoreCase = true) ?: false
                        updateBatteryIcon(batt.percent, isCharging)
                        delay(15_000)
                    } else {
                        // Failed to get battery data
                        consecutiveFailures++
                        val now = SystemClock.elapsedRealtime()

                        // Log failure periodically
                        if (now - lastLoggedFailure >= 60_000) {
                            android.util.Log.w("MainActivity", "Battery fetch failed for $ip (failure #$consecutiveFailures)")
                            lastLoggedFailure = now
                        }

                        // Hide after 3 consecutive failures
                        if (consecutiveFailures >= 3 && !batteryHidden) {
                            android.util.Log.w("MainActivity", "Hiding battery after $consecutiveFailures consecutive failures")
                            updateBatteryIcon(null)
                            batteryHidden = true
                            hideStartTime = SystemClock.elapsedRealtime()
                        }

                        // Progressive backoff delay
                        val delayMs = when (consecutiveFailures) {
                            1 -> 15_000L
                            2 -> 30_000L
                            else -> 45_000L
                        }
                        delay(delayMs)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Battery polling error", e)
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


    private fun updateDevToolsVisibility(devEnabled: Boolean) {
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
            appBarConfiguration = AppBarConfiguration(topLevel, binding.drawerLayout)
            setupActionBarWithNavController(navController, appBarConfiguration)
        }
    }

    private fun updateStatusTokens(
        source: LocationSourceType,
        fix: Fix,
        sky: com.example.surveyingapp.gnss.model.SkySnapshot
    ) {
        // Validate coordinate ranges (WGS84 bounds)
        val latValid = fix.latDeg in -90.0..90.0
        val lonValid = fix.lonDeg in -180.0..180.0

        if (!latValid || !lonValid) {
            android.util.Log.w("MainActivity", "Invalid coordinates: lat=${fix.latDeg}, lon=${fix.lonDeg}")
            tokenCoord.value.text = "Invalid"
            tokenCoord.root.isVisible = true
            return
        }

        val srcLabel = if (source == LocationSourceType.INTERNAL) "Internal" else "RS2+"
        tokenSource.value.text = srcLabel

        val isInternal = source == LocationSourceType.INTERNAL
        tokenSource.separator?.isVisible = !isInternal

        if (isInternal) {
            // Internal GPS: Hide FIX/SATS tokens entirely
            tokenFix.root.isVisible = false
            tokenSats.root.isVisible = false
            // Hide battery immediately when internal
            updateBatteryVisibility(false)

            // Coordinates with validation
            val latStr = String.format(Locale.US, "%.6f", fix.latDeg)
            val lonStr = String.format(Locale.US, "%.6f", fix.lonDeg)
            tokenCoord.value.text = "$latStr, $lonStr"
            tokenCoord.root.isVisible = true

            // Altitude: prefer MSL if available, validate range
            val altMsl = fix.altMslM
            val altEllip = fix.altEllipsoidalM
            when {
                altMsl != null && altMsl in -500.0..10000.0 -> {
                    tokenAlt.value.text = String.format(Locale.US, "%.2fm", altMsl)
                    tokenAlt.root.isVisible = true
                }
                altEllip != null && altEllip in -500.0..10000.0 -> {
                    tokenAlt.value.text = String.format(Locale.US, "%.2fm", altEllip)
                    tokenAlt.root.isVisible = true
                }
                else -> tokenAlt.root.isVisible = false
            }
            return
        }

        // External (RS2+): Show FIX and SATS tokens
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

        // Satellites: Use SkySnapshot data (GSV) for consistency with Developer Tools
        // Fallback to Fix data (GGA) if SkySnapshot has no data yet
        val used = if (sky.totalUsed > 0) sky.totalUsed else fix.satsUsed.coerceIn(0, 100)
        val vis = if (sky.totalVisible > 0) sky.totalVisible else (fix.satsVisible ?: used).coerceIn(used, 100)

        if (used > 0 || vis > 0) {
            tokenSats.value.text = "$used/$vis"
            tokenSats.root.isVisible = true
        } else {
            // Even for RS2+, hide sats if zero (waiting for first fix)
            tokenSats.root.isVisible = false
        }

        // Coordinates with validation
        val latStr = String.format(Locale.US, "%.6f", fix.latDeg)
        val lonStr = String.format(Locale.US, "%.6f", fix.lonDeg)
        tokenCoord.value.text = "$latStr, $lonStr"
        tokenCoord.root.isVisible = true

        // Altitude: prefer MSL, else ellipsoidal, validate range
        val altMsl = fix.altMslM
        val altEllip = fix.altEllipsoidalM
        when {
            altMsl != null && altMsl in -500.0..10000.0 -> {
                tokenAlt.value.text = String.format(Locale.US, "%.2fm", altMsl)
                tokenAlt.root.isVisible = true
            }
            altEllip != null && altEllip in -500.0..10000.0 -> {
                tokenAlt.value.text = String.format(Locale.US, "%.2fm", altEllip)
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
                val prov = try { sourceSettings.activeProvider.first() } catch (_: Exception) { com.example.surveyingapp.gnss.settings.SourceSettings.ProviderChoice.INTERNAL }
                val srcLabel = if (prov == com.example.surveyingapp.gnss.settings.SourceSettings.ProviderChoice.INTERNAL) "Internal" else "RS2+"
                tokenSource.value.text = srcLabel
            }

            android.util.Log.i("MainActivity", "Stopped NMEA replay")

        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error stopping NMEA replay", e)
        }
    }
}
