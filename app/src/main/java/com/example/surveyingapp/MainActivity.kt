package com.example.surveyingapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
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
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.gnss.bus.FixSwitchboard
import com.example.surveyingapp.gnss.external.ExternalAdapter
import com.example.surveyingapp.databinding.ActivityMainBinding
import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.ui.toolbar.GnssStatusLevel
import com.example.surveyingapp.ui.toolbar.GnssToolbarState
import com.example.surveyingapp.ui.toolbar.GnssToolbarRenderer
import com.example.surveyingapp.ui.toolbar.ToolbarTokens
import com.example.surveyingapp.ui.toolbar.GnssToolbarStateMapper
import com.example.surveyingapp.ui.toolbar.ToolbarMapResult
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
import com.example.surveyingapp.gnss.external.ReachBatteryService
import com.example.surveyingapp.gnss.external.ReachHttpClient
import com.example.surveyingapp.gnss.replay.NmeaReplayController
import com.example.surveyingapp.gnss.replay.AssetNmeaReplaySource
import com.example.surveyingapp.gnss.accumulator.FixAccumulator
import com.example.surveyingapp.gnss.nmea.parse.NmeaRegistry
import com.example.surveyingapp.gnss.diagnostics.DiagnosticsService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private const val LOG_GNSS_UI = false

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
    @Inject lateinit var sourceSettings: com.example.surveyingapp.gnss.source.SourceSettings

    // Replay controller for NMEA playback
    private var replayController: NmeaReplayController? = null

    // Token view holder for status bar (ToolbarTokens lives in ui.toolbar, shared with the renderer)
    private lateinit var tokenSource: ToolbarTokens
    private lateinit var tokenFix: ToolbarTokens
    private lateinit var tokenSats: ToolbarTokens
    private lateinit var tokenAcc: ToolbarTokens
    private lateinit var tokenCoord: ToolbarTokens
    private lateinit var tokenAlt: ToolbarTokens
    private lateinit var tokenBatt: ToolbarTokens

    /** View-only renderer for the GNSS status toolbar (see [GnssToolbarRenderer]). */
    private lateinit var toolbarRenderer: GnssToolbarRenderer

    /** Short label of the selected external receiver profile (e.g. "RS2+"/"RS4"); updated from settings. */
    @Volatile private var externalReceiverLabel: String = "RS2+"

    // Cache the battery drawable once and mutate in place
    private var batteryLayer: LayerDrawable? = null
    private var batteryFillClip: ClipDrawable? = null
    // Last battery reading, cached so the token can be re-rendered on orientation change
    // (MainActivity handles configChanges itself, so it is not recreated on rotation).
    private var lastBatteryPercent: Int? = null
    private var lastBatteryCharging: Boolean? = null

    // AR mode: true while the AR fragment is the current destination
    private var isArMode = false
    private var gnssStripCachedShow = true

    // Drawer navigation: true while a navigation is in progress, to drop rapid duplicate taps
    private var drawerNavigationInProgress = false

    // Dev tools: last value applied to avoid redundant app-bar rebuilds
    private var lastDevToolsEnabled: Boolean? = null

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
        tokenAcc    = findToken(R.id.token_acc)
        tokenCoord  = findToken(R.id.token_coord)
        tokenAlt    = findToken(R.id.token_alt)
        tokenBatt   = findToken(R.id.token_batt)

        // View-only toolbar renderer. Color resolution and battery-polling lifecycle stay here.
        toolbarRenderer = GnssToolbarRenderer(
            source = tokenSource, fix = tokenFix, sats = tokenSats, acc = tokenAcc,
            coord = tokenCoord, alt = tokenAlt, batt = tokenBatt,
            colorFor = ::levelColor,
            onBatteryVisibilityChanged = ::updateBatteryVisibility
        )

        // Static labels
        tokenSource.label.setText(R.string.status_token_src)
        tokenFix.label.text = ""   // dynamic: "GPS" / "RTK Float" / etc.
        tokenSats.label.setText(R.string.status_token_sats)
        tokenAcc.label.setText(R.string.status_token_hacc)
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

        // Trailing separators (managed dynamically in renderGnssToolbarState)
        tokenSats.separator?.isVisible = false
        tokenAcc.separator?.isVisible = false
        tokenAlt.separator?.isVisible = false
        tokenBatt.separator?.isVisible = false

        // Hide the SRC label in portrait to save horizontal space.
        applyStatusBarOrientationLayout()

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

        // Keep checked-state sync; handle item taps ourselves for immediate navigation.
        navView.setupWithNavController(navController)
        navView.setNavigationItemSelectedListener { menuItem ->
            val destId = menuItem.itemId
            val currentId = navController.currentDestination?.id

            if (destId == currentId) {
                drawerLayout.closeDrawer(GravityCompat.START)
                return@setNavigationItemSelectedListener true
            }

            if (drawerNavigationInProgress) return@setNavigationItemSelectedListener true

            drawerNavigationInProgress = true
            menuItem.isChecked = true

            val handled = try {
                navController.navigate(destId)
                true
            } catch (e: IllegalArgumentException) {
                android.util.Log.w("MainActivity", "Drawer destination not found: $destId", e)
                false
            } finally {
                drawerLayout.closeDrawer(GravityCompat.START)
                drawerLayout.postDelayed({ drawerNavigationInProgress = false }, 300)
            }

            handled
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepository.developerSettings.collect { devSettings ->
                    updateDevToolsVisibility(devSettings.developerToolsEnabled)
                }
            }
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val isAr = destination.id == R.id.nav_open_in_ar
            applyArModeChrome(isAr)
            if (isAr) {
                navHostFragment.childFragmentManager.executePendingTransactions()
            }
        }

        // Pre-populate initial status tokens with placeholders
        lifecycleScope.launch {
            val initialProvider = try { 
                sourceSettings.activeProvider.first() 
            } catch (_: Exception) { 
                com.example.surveyingapp.gnss.source.SourceSettings.ProviderChoice.INTERNAL 
            }
            
            val isInternal = initialProvider == com.example.surveyingapp.gnss.source.SourceSettings.ProviderChoice.INTERNAL
            val srcLabel = if (isInternal) "Internal" else externalReceiverLabel

            // Set initial source label
            tokenSource.value.text = srcLabel
            tokenSource.separator?.isVisible = true

            // SOL token: Internal shows "--" (acquires quickly); External shows "Waiting" so the
            // RS2+ label is never paired with a blank/ambiguous status while the receiver connects.
            tokenFix.root.isVisible = true
            tokenFix.label.text = "SOL"
            tokenFix.value.text = if (isInternal) "--" else "Waiting"
            tokenFix.value.setTextColor(getColor(R.color.app_on_status_strip_variant))

            tokenSats.root.isVisible = false // Hidden until first fix with sat data
            tokenAcc.root.isVisible = false  // Hidden until first fix with accuracy data

            // Show coordinate placeholder
            tokenCoord.value.text = "--"
            tokenCoord.root.isVisible = true

            // Show altitude placeholder (label + --) like the LL token
            tokenAlt.value.text = "--"
            tokenAlt.root.isVisible = true

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

    private fun findToken(id: Int): ToolbarTokens {
        val root = findViewById<ViewGroup>(id)
        val label = root.findViewById<TextView>(R.id.label)
        val value = root.findViewById<TextView>(R.id.value)
        val icon = root.findViewById<ImageView>(R.id.icon)
        val sep = root.findViewById<TextView?>(R.id.separator)
        return ToolbarTokens(root, label, value, icon, sep)
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

    private fun applyArModeChrome(isAr: Boolean) {
        isArMode = isAr
        if (isAr) {
            supportActionBar?.hide()
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            }
            binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            findViewById<View>(R.id.location_status_bar)?.visibility = View.GONE
        } else {
            supportActionBar?.show()
            binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
            findViewById<View>(R.id.location_status_bar)?.visibility =
                if (gnssStripCachedShow) View.VISIBLE else View.GONE
        }
    }

    /**
     * Applies window-level display behaviour from Appearance settings.
     * Must be called on the main thread.
     *
     * keepScreenAwake   → FLAG_KEEP_SCREEN_ON add/clear (no system setting changed)
     * maxBrightnessWhileOpen → window.attributes.screenBrightness 1.0f / BRIGHTNESS_OVERRIDE_NONE
     *   Uses app-window brightness only — does not require WRITE_SETTINGS permission and
     *   does not change the Android system brightness permanently.
     */
    private fun applyDisplayBehaviorSettings(settings: com.example.surveyingapp.settings.model.AppearanceSettings) {
        if (settings.keepScreenAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val lp = window.attributes
        lp.screenBrightness = if (settings.maxBrightnessWhileOpen) 1.0f
                              else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    /** Status bar observers */
    private fun startStatusBarObservers() {

        // Track the selected external receiver profile so the source token shows its label
        // (e.g. "RS4") instead of always "RS2+". Profile changes rarely; the latest value is read
        // when the toolbar state is rendered.
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepository.externalReceiverSettings.collect { externalReceiverLabel = it.profile.shortLabel }
            }
        }

        // Immediate source label + battery update whenever the SELECTED source changes.
        // We key off the persisted locationSource (set the instant the user taps a radio),
        // NOT activeProvider — activeProvider only flips to EXTERNAL_TCP after the TCP/HTTP
        // probe in connectViaTcpFlow succeeds, which can take several seconds. Keying off the
        // selection makes the toolbar flip to placeholders immediately in both directions.
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var previousSource: LocationSourceType? = null
                settingsRepository.locationSource
                    .collect { source ->
                        if (previousSource == null || previousSource != source) {
                            resetStatusTokensForSource(source)
                        }
                        previousSource = source
                    }
            }
        }

        // Single consolidated observer for all status bar live data.
        //
        // Live values are driven by switchboard.currentFix — the nullable, current-provider-only
        // fix state. Unlike the replay=1 `fixes` SharedFlow it can never replay the previous
        // provider's last fix: FixSwitchboard sets it to null the instant a provider switch begins
        // and only repopulates it from the newly-bound provider. So `fix == null` here means
        // "no live fix from the current provider yet" → show the waiting/blank state.
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var consecutiveErrors = 0

                combine(
                    switchboard.currentFix,
                    switchboard.sky,
                    settingsRepository.locationSource
                ) { fix, sky, source ->
                    Triple(fix, sky, source)
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
                    .collectLatest { (fix, sky, source) ->
                        consecutiveErrors = 0 // Reset error counter on success

                        // All display decisions live in the mapper. MainActivity only collects,
                        // maps, and renders — it no longer contains GNSS toolbar logic.
                        when (val result = GnssToolbarStateMapper.map(source, fix, sky, System.currentTimeMillis(), externalReceiverLabel)) {
                            is ToolbarMapResult.Ignore -> {
                                // Wrong-provider / stale / invalid fix: keep the existing
                                // placeholders untouched (throttled diagnostics only).
                                logToolbarIgnore(result.reason)
                            }
                            is ToolbarMapResult.Render -> {
                                val state = result.state
                                if (state.isWaiting) {
                                    if (source == LocationSourceType.EXTERNAL && !loggedExternalWaiting) {
                                        loggedExternalWaiting = true
                                        com.example.surveyingapp.util.DiagnosticsLogger.i(
                                            "Toolbar", "External selected, waiting for current fix")
                                    }
                                    latestSkySnapshot = null
                                } else {
                                    latestSkySnapshot = sky
                                    if (source == LocationSourceType.EXTERNAL && !loggedFirstExternalToolbarFix) {
                                        loggedFirstExternalToolbarFix = true
                                        loggedExternalWaiting = false
                                        com.example.surveyingapp.util.DiagnosticsLogger.i(
                                            "Toolbar", "First current external fix displayed after startup")
                                    }
                                }
                                lastDataUpdateTime = System.currentTimeMillis()
                                withContext(Dispatchers.Main) {
                                    toolbarRenderer.render(state)
                                    tokenSource.value.alpha = 1.0f
                                }
                            }
                        }
                    }
            }
        }

        // Immediate reset whenever the LIVE provider actually changes (the switchboard rebinds).
        // This complements the locationSource reset above: that one fires on the user's selection,
        // this one fires when routing actually flips (e.g. External finishing its connect), so the
        // previous provider's fix/sats/correction/battery clear right away.
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var previousProvider: com.example.surveyingapp.gnss.source.SourceSettings.ProviderChoice? = null
                sourceSettings.activeProvider.collect { provider ->
                    if (previousProvider != null && previousProvider != provider) {
                        val isExternalLive =
                            provider == com.example.surveyingapp.gnss.source.SourceSettings.ProviderChoice.EXTERNAL_TCP
                        com.example.surveyingapp.util.DiagnosticsLogger.i(
                            "Toolbar", "Reset due to provider switch $previousProvider -> $provider"
                        )
                        val liveSource = if (isExternalLive) LocationSourceType.EXTERNAL else LocationSourceType.INTERNAL
                        withContext(Dispatchers.Main) { resetStatusTokensForSource(liveSource) }
                    }
                    previousProvider = provider
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

        // Show/hide the entire status bar based on Appearance setting (suppressed while in AR)
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepository.appearanceSettings
                    .map { it.showLiveGnssStatusBar }
                    .distinctUntilChanged()
                    .collect { show ->
                        gnssStripCachedShow = show
                        if (!isArMode) {
                            findViewById<View>(R.id.location_status_bar)?.visibility =
                                if (show) View.VISIBLE else View.GONE
                        }
                    }
            }
        }

        // Apply Field Display settings (keep awake + max brightness) whenever they change.
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepository.appearanceSettings
                    .distinctUntilChanged { a, b ->
                        a.keepScreenAwake == b.keepScreenAwake &&
                        a.maxBrightnessWhileOpen == b.maxBrightnessWhileOpen
                    }
                    .collect { settings -> applyDisplayBehaviorSettings(settings) }
            }
        }
    }

    /**
     * Resets the toolbar to the waiting/placeholder state for [source] (label + blank live fields).
     * Delegates all presentation to the mapper + renderer; this method only does the bookkeeping
     * (clear cached sky, reset the stale-dim timer). Called on provider switch so the previous
     * provider's values never linger.
     */
    private fun resetStatusTokensForSource(
        source: LocationSourceType
    ) {
        latestSkySnapshot = null
        lastDataUpdateTime = System.currentTimeMillis()
        toolbarRenderer.render(GnssToolbarStateMapper.waiting(source, externalReceiverLabel))
        tokenSource.value.alpha = 1.0f
    }

    // Throttle for wrong-provider/stale/invalid-fix diagnostics so a steady stream of dropped
    // fixes (e.g. internal fixes during the External-connecting window) can't flood the log.
    private var lastToolbarIgnoreLogMs = 0L
    private var lastToolbarIgnoreReason: String? = null

    // One-shot transition logs for the External waiting → first-fix flow at startup.
    private var loggedExternalWaiting = false
    private var loggedFirstExternalToolbarFix = false
    private fun logToolbarIgnore(reason: String) {
        val now = System.currentTimeMillis()
        if (reason != lastToolbarIgnoreReason || now - lastToolbarIgnoreLogMs >= 10_000L) {
            lastToolbarIgnoreLogMs = now
            lastToolbarIgnoreReason = reason
            com.example.surveyingapp.util.DiagnosticsLogger.w("Toolbar", "Ignored $reason fix for live display")
        }
    }

    private fun updateBatteryVisibility(shouldShow: Boolean) {
        if (shouldShow) {
            // External source: poll the receiver for battery. The token stays hidden until real
            // battery data arrives — updateBatteryIcon() reveals it (and its separator) once we
            // have a percentage, and hides it again if the data is lost.
            if (batteryJob == null) startBatteryPolling()
        } else {
            // Internal source (or no receiver): hide the token + its separator and stop polling.
            tokenAlt.separator?.isVisible = false
            tokenBatt.root.isVisible = false
            batteryJob?.cancel(); batteryJob = null
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // MainActivity handles configChanges itself, so it is not recreated on rotation. Re-apply
        // orientation-dependent status-bar tweaks immediately:
        //  - show/hide the SRC label (hidden in portrait to save horizontal space), and
        //  - re-render the battery token so its graphical icon is shown (landscape) or hidden
        //    (portrait, percentage text only) to match the new orientation.
        applyStatusBarOrientationLayout()
        if (lastBatteryPercent != null) {
            updateBatteryIcon(lastBatteryPercent, lastBatteryCharging)
        }
    }

    private fun updateBatteryIcon(percentage: Int?, charging: Boolean? = null) {
        // Cache the latest reading so onConfigurationChanged() can re-render for the new orientation.
        lastBatteryPercent = percentage
        lastBatteryCharging = charging

        // No data: hide the token entirely (along with its separator) instead of showing "--".
        if (percentage == null) {
            tokenBatt.root.isVisible = false
            tokenAlt.separator?.isVisible = false
            return
        }
        // Data available: reveal the token and the separator that precedes it.
        tokenBatt.root.isVisible = true
        tokenAlt.separator?.isVisible = true
        tokenBatt.value.text = "${percentage.coerceIn(0, 100)}%"

        // Narrow width or portrait: percentage text is sufficient; skip the graphical icon.
        tokenBatt.icon.isVisible = !batteryTextOnly
        if (batteryTextOnly) return

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

            // Counts how many consecutive iterations returned a blank IP.
            // setActiveProvider(EXTERNAL_TCP) fires before settingsRepo.setExternalTcp()
            // completes its DataStore write, so the first few reads can return null.
            // Retry quickly until the write propagates, then back off to 60 s.
            var blankIpCount = 0

            android.util.Log.d("MainActivity", "Battery polling started")

            while (true) {
                try {
                    // Get configured IP from settings
                    val ip = runCatching {
                        settingsRepository.externalTcpHost.first()
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
                        // Retry quickly on the first few misses to handle the race where
                        // setActiveProvider fires before externalTcpHost is persisted.
                        delay(if (blankIpCount++ < 5) 1_000L else 60_000L)
                        continue
                    }
                    blankIpCount = 0

                    // IP changed - reinitialize client
                    if (ip != lastIp) {
                        android.util.Log.d("MainActivity", "Battery IP changed to: $ip")

                        try {
                            // Short timeouts: /battery may not exist on all firmware versions.
                            // A 1.5 s timeout means the fallback to /status costs at most 3 s
                            // instead of the default 8 s, so battery appears quickly on connect.
                            client = ReachHttpClient(ip, connectTimeoutMs = 1500, readTimeoutMs = 1500)
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

                        // Progressive backoff: retry quickly on first miss (receiver may
                        // not have served the HTTP API yet), then slow down.
                        val delayMs = when (consecutiveFailures) {
                            1 -> 5_000L
                            2 -> 15_000L
                            else -> 30_000L
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
        if (devEnabled == lastDevToolsEnabled) return
        lastDevToolsEnabled = devEnabled

        val menu = binding.navView.menu
        menu.findItem(R.id.nav_development)?.isVisible = devEnabled

        val topLevel = mutableSetOf(
            R.id.nav_home,
            R.id.nav_models,
            R.id.nav_view_coordinates,
            R.id.nav_render_map,
            R.id.nav_open_in_ar,
            R.id.nav_settings
        )
        if (devEnabled) topLevel.add(R.id.nav_development)

        appBarConfiguration = AppBarConfiguration(topLevel, binding.drawerLayout)
        setupActionBarWithNavController(navController, appBarConfiguration)
    }

    /** True when the available toolbar width is too narrow for the full battery icon. */
    private val isCompact: Boolean
        get() = resources.configuration.screenWidthDp < 700

    /** True in portrait orientation, where we drop the battery graphic to save horizontal space. */
    private val isPortrait: Boolean
        get() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

    /** When true, the battery token shows the percentage text only (no graphical icon). */
    private val batteryTextOnly: Boolean
        get() = isCompact || isPortrait

    /**
     * Hides the "SRC" label in portrait to save horizontal space so the status strip fits on one
     * line; the source value (e.g. "RS2+") and all other token labels stay visible.
     */
    private fun applyStatusBarOrientationLayout() {
        tokenSource.label.isVisible = !isPortrait
    }

    /** Resolves a mapper [GnssStatusLevel] to the toolbar color. View concern only. */
    private fun levelColor(level: GnssStatusLevel): Int = when (level) {
        GnssStatusLevel.SUCCESS -> getColor(R.color.app_success)
        GnssStatusLevel.WARNING -> getColor(R.color.app_warning)
        GnssStatusLevel.INFO    -> getColor(R.color.app_info)
        GnssStatusLevel.ERROR   -> getColor(R.color.app_error)
        GnssStatusLevel.NONE    -> getColor(android.R.color.darker_gray)
    }

    // Toolbar rendering now lives in GnssToolbarRenderer (see toolbarRenderer); call render(state).


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
            val replaySource = AssetNmeaReplaySource(
                context = this,
                assetFileName = fileName,
                delayBetweenLines = 1000L, // 1 second between NMEA sentences
                name = "Replay ($fileName)"
            )

            // Create new controller with replay source
            replayController = NmeaReplayController(
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
                val prov = try { sourceSettings.activeProvider.first() } catch (_: Exception) { com.example.surveyingapp.gnss.source.SourceSettings.ProviderChoice.INTERNAL }
                val srcLabel = if (prov == com.example.surveyingapp.gnss.source.SourceSettings.ProviderChoice.INTERNAL) "Internal" else externalReceiverLabel
                tokenSource.value.text = srcLabel
            }

            android.util.Log.i("MainActivity", "Stopped NMEA replay")

        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error stopping NMEA replay", e)
        }
    }
}
