package app.surrealar

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
import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.gnss.external.ExternalAdapter
import app.surrealar.databinding.ActivityMainBinding
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.ui.toolbar.GnssStatusLevel
import app.surrealar.ui.toolbar.GnssToolbarState
import app.surrealar.ui.toolbar.GnssToolbarRenderer
import app.surrealar.ui.toolbar.ToolbarTokens
import app.surrealar.ui.toolbar.GnssToolbarStateMapper
import app.surrealar.ui.toolbar.ToolbarFrame
import app.surrealar.ui.toolbar.ToolbarInputs
import app.surrealar.ui.toolbar.ToolbarPipeline
import app.surrealar.ui.toolbar.ToolbarPipeline.retryForever
import app.surrealar.ui.toolbar.ToolbarMapResult
import app.surrealar.ui.navigation.topLevelNavOptions
import app.surrealar.service.LocationService
import app.surrealar.util.PermissionManager
import app.surrealar.util.DevLog
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ClipDrawable
import app.surrealar.gnss.replay.AssetNmeaReplaySource
import app.surrealar.gnss.diagnostics.DiagnosticsService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Permission names for the log: short names only ("ACCESS_FINE_LOCATION"), e.g. "[A,B]". */
internal fun permissionNames(perms: Collection<String>): String =
    perms.joinToString(",", "[", "]") { it.substringAfterLast('.') }

// Short beat between a drawer item tap and the drawer closing, so the item's ripple/pressed
// state is visible before the panel slides away (Material 3 feel). Kept small to avoid lag.
private const val NAV_DRAWER_CLOSE_DELAY_MS = 120L

/**
 * Single host activity: owns the navigation drawer, the Navigation component `NavController`, and the
 * always-visible GNSS status toolbar. It observes the injected [FixSwitchboard]'s fix/sky streams,
 * maps them to a [GnssToolbarState] via [GnssToolbarStateMapper], and hands that to the view-only
 * [GnssToolbarRenderer] — the activity holds no GNSS logic of its own. Individual screens are
 * fragments under the `NavHostFragment`; this activity only wires chrome, navigation, and the
 * foreground [LocationService]/permission prompts around them.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController


    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var coordinateRepository: app.surrealar.domain.repository.CoordinateRepository
    @Inject lateinit var modelRepository: app.surrealar.domain.repository.ModelRepository

    @Inject lateinit var receiverDevices: app.surrealar.receiver.ReceiverDeviceManager
    // True while the selected source is external, i.e. the battery token may be shown.
    private var batteryShouldShow = false
    /** What the source asked for (external selected); the receiver must also report a battery. */
    private var batteryRequested = false

    // Track latest sky snapshot for accurate satellite counts
    @Volatile
    private var latestSkySnapshot: io.github.pennstatecs.geokit.gnss.model.SkySnapshot? = null

    // Hilt injected GNSS components
    @Inject lateinit var switchboard: FixSwitchboard
    @Inject lateinit var coordinateSystemService: app.surrealar.coords.CoordinateSystemService
    @Inject lateinit var externalAdapter: ExternalAdapter
    @Inject lateinit var replayAdapter: io.github.pennstatecs.geokit.gnss.replay.ReplayAdapter
    @Inject lateinit var gnssSourceCoordinator: app.surrealar.gnss.source.GnssSourceCoordinator
    @Inject lateinit var diagnosticsService: DiagnosticsService
    @Inject lateinit var sourceSettings: app.surrealar.gnss.source.SourceSettings

    // Replay controller for NMEA playback
    /** Provider active before replay started, restored when replay stops (null = not replaying). */
    private var providerBeforeReplay: app.surrealar.gnss.source.SourceSettings.ProviderChoice? = null

    // Token view holder for status bar (ToolbarTokens lives in ui.toolbar, shared with the renderer)
    private lateinit var tokenSource: ToolbarTokens
    private lateinit var tokenFix: ToolbarTokens
    private lateinit var tokenSats: ToolbarTokens
    private lateinit var tokenAcc: ToolbarTokens
    private lateinit var tokenAge: ToolbarTokens
    /** Toolbar-only display override from tapping LL/NE (doesn't change Settings). */
    private val toolbarPreferLatLon = MutableStateFlow(false)
    /** Last state the toolbar rendered (tap actions read it; decisions stay in the mapper). */
    private var lastToolbarState: app.surrealar.ui.toolbar.GnssToolbarState? = null
    private lateinit var tokenCoord: ToolbarTokens
    private lateinit var tokenAlt: ToolbarTokens
    private lateinit var tokenBatt: ToolbarTokens

    /** View-only renderer for the GNSS status toolbar (see [GnssToolbarRenderer]). */
    private lateinit var toolbarRenderer: GnssToolbarRenderer

    /** Short label of the selected external receiver profile (e.g. "RS2+"/"RS4"); updated from settings. */
    /** Active external profile's short label; "External" until settings load (never blank, never a vendor guess). */
    @Volatile private var externalReceiverLabel: String = app.surrealar.gnss.format.GnssStatusFormatter.EXTERNAL_FALLBACK_LABEL

    // Cache the battery drawable once and mutate in place
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
        // Permission names only (no user data).
        DiagnosticsLogger.i(DiagTags.SYSTEM, "Permission result granted=${permissionNames(granted)} denied=${permissionNames(denied)}")

        // Reset guard so future requests are never blocked
        showingEssentialRationale = false

        // Use ground-truth check (covers permissions not in this batch)
        val stillMissing = PermissionManager.getMissingEssentialPermissions(this)
        if (stillMissing.isEmpty()) {
            DevLog.d("MainActivity") { "All essential permissions granted" }
            ensureLocationServiceStarted()
            // Permission just became available — recover an internal provider that was waiting for it.
            runCatching { switchboard.retryActiveSourceIfStalled("permission-granted") }
                .onFailure { DiagnosticsLogger.w(DiagTags.GNSS, "GNSS source retry after permission grant failed", it) }
        } else {
            DevLog.d("MainActivity") { "Still missing: ${permissionNames(stillMissing)}" }
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
        tokenAge    = findToken(R.id.token_age)
        tokenCoord  = findToken(R.id.token_coord)
        tokenAlt    = findToken(R.id.token_alt)
        tokenBatt   = findToken(R.id.token_batt)

        // View-only toolbar renderer. Color resolution and battery-polling lifecycle stay here.
        toolbarRenderer = GnssToolbarRenderer(
            source = tokenSource, fix = tokenFix, sats = tokenSats, acc = tokenAcc, age = tokenAge,
            coord = tokenCoord, alt = tokenAlt, batt = tokenBatt,
            colorFor = ::levelColor,
            onBatteryVisibilityChanged = ::updateBatteryVisibility,
            strip = findViewById(R.id.location_status_bar),
        )
        setupToolbarActions()

        // Battery comes from the active ReceiverDevice (one poller, shared with Settings and the
        // Developer page). Null while the receiver is unreachable or not selected; the token is hidden
        // entirely for receivers that don't report a battery.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    receiverDevices.battery.collect { b ->
                        if (batteryShouldShow) updateBatteryIcon(b?.percent, b?.isCharging)
                    }
                }
                launch { receiverDevices.capabilities.collect { updateBatteryVisibility(batteryRequested) } }
            }
        }

        // Hide the SRC label in portrait to save horizontal space.
        toolbarRenderer.setPortrait(isPortrait)

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

        // Drawer header: edge-to-edge (enforced on Android 15+) draws the drawer behind the status
        // bar, so the header's logo/title collided with the clock. Push the header content below the
        // status bar (and any display cutout) by adding that inset to its own top padding.
        navView.getHeaderView(0)?.let { header ->
            val baseTop = header.paddingTop
            fun topOf(insets: androidx.core.view.WindowInsetsCompat?): Int = insets?.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )?.top ?: 0
            fun applyTop(insets: androidx.core.view.WindowInsetsCompat?) {
                // NavigationView consumes the inset before dispatching (the header then sees 0), so take
                // the larger of the dispatched and the window's root insets — never let a 0 undo it.
                val top = maxOf(topOf(insets), topOf(androidx.core.view.ViewCompat.getRootWindowInsets(header)))
                if (top == 0) return
                if (header.paddingTop != baseTop + top) {
                    header.setPadding(header.paddingLeft, baseTop + top, header.paddingRight, header.paddingBottom)
                }
            }
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(header) { _, insets -> applyTop(insets); insets }
            // NavigationView may consume insets before they reach the header; fall back to the
            // window's root insets once the header is attached.
            header.post { applyTop(androidx.core.view.ViewCompat.getRootWindowInsets(header)) }
        }

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
                // Top-level switch: pop back to Home instead of stacking another copy.
                navController.navigate(destId, null, topLevelNavOptions(navController.graph.startDestinationId))
                true
            } catch (e: IllegalArgumentException) {
                DiagnosticsLogger.w(DiagTags.NAV, "Drawer navigation failed: destination not found destId=$destId", e)
                false
            }

            // Navigation ran immediately above; defer the close one short beat so the tapped
            // item's ripple/pressed feedback is visible before the drawer slides away. The new
            // screen renders behind the drawer and is revealed smoothly as it closes.
            drawerLayout.postDelayed(
                { drawerLayout.closeDrawer(GravityCompat.START) },
                NAV_DRAWER_CLOSE_DELAY_MS,
            )
            // Release the re-entrancy guard after the close animation settles.
            drawerLayout.postDelayed({ drawerNavigationInProgress = false }, 300)

            handled
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepository.developerSettings.collect { devSettings ->
                    updateDevToolsVisibility(devSettings.developerToolsEnabled)
                }
            }
        }

        setupDrawerCountBadges(navView)

        navController.addOnDestinationChangedListener(app.surrealar.util.diagnostics.NavLogger)
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val isAr = destination.id == R.id.nav_open_in_ar
            applyArModeChrome(isAr)
            if (isAr) {
                navHostFragment.childFragmentManager.executePendingTransactions()
            }
        }

        // Initial placeholders: the mapper's waiting state for the starting provider, via the renderer.
        lifecycleScope.launch {
            val initialProvider = try {
                sourceSettings.activeProvider.first()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                DiagnosticsLogger.w(DiagTags.GNSS, "Toolbar active provider read failed: using INTERNAL", e)
                app.surrealar.gnss.source.SourceSettings.ProviderChoice.INTERNAL
            }
            val isInternal = initialProvider == app.surrealar.gnss.source.SourceSettings.ProviderChoice.INTERNAL
            toolbarRenderer.render(GnssToolbarStateMapper.waiting(
                if (isInternal) LocationSourceType.INTERNAL else LocationSourceType.EXTERNAL, externalReceiverLabel))
        }

        // Permissions flow
        requestAllPermissions()

        // Initialize GNSS graph routing & observers BEFORE status observers
        initGnss()

        // Start observers AFTER GNSS init
        startStatusBarObservers()
    }

    private fun initGnss() {
        // The switchboard is started once by DI (GnssModule.provideFixSwitchboard). Calling start()
        // again used to cancel and recreate its provider watcher, racing a startup provider switch;
        // start() is now a no-op when already running, so this is only a safety net.
        try {
            switchboard.start()
        } catch (e: Exception) {
            DiagnosticsLogger.e(DiagTags.GNSS, "GNSS switchboard start failed", e)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Replay is app-scoped now; leaving the app ends it and restores the previous source.
        providerBeforeReplay?.let { gnssSourceCoordinator.endReplay(it, "activity destroyed") }
        providerBeforeReplay = null
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
            DevLog.d("MainActivity") { "Requesting essential permissions: ${permissionNames(missing)}" }
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
    }

    override fun onResume() {
        super.onResume()

        // Recover the internal GNSS provider if its startup was blocked waiting for location
        // permission (cold-launch race) or for location services. Resuming is the moment the user
        // returns after granting permission / enabling location in system settings. No-op when the
        // active source is already streaming or not waiting, so it never double-registers.
        runCatching { switchboard.retryActiveSourceIfStalled("app-resume") }
            .onFailure { DiagnosticsLogger.w(DiagTags.GNSS, "GNSS source retry on resume failed", it) }

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

        DevLog.d("MainActivity") { "Showing permission rationale for: ${permissionNames(missingPermissions)}" }

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
        DiagnosticsLogger.i(DiagTags.SYSTEM, "Permission permanently denied permissions=${permissionNames(missingPermissions)}")

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
                    DiagnosticsLogger.e(DiagTags.NAV, "Permission settings screen open failed", e)
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
    private fun applyDisplayBehaviorSettings(settings: app.surrealar.settings.model.AppearanceSettings) {
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
                // Pipeline (see ToolbarPipeline): combine raw inputs → sample(250 ms) → convert + map on
                // Dispatchers.Default → render here on Main. retryForever keeps it alive after an error
                // (shows the error state, backs off 1 s → 5 s, resubscribes) instead of ending it.
                val gnss = combine(
                    switchboard.currentFix,
                    switchboard.sky,
                    settingsRepository.locationSource,
                    settingsRepository.coordinateSystemSettings,
                    externalLabelFlow(),
                ) { fix, sky, source, coordSettings, label -> ToolbarInputs(source, fix, sky, coordSettings, label) }
                val replayActive = sourceSettings.activeProvider.map { it == app.surrealar.gnss.source.SourceSettings.ProviderChoice.REPLAY }
                val inputs = combine(gnss, toolbarLinkFlow(), toolbarTickFlow(), receiverKeyFlow(),
                    combine(toolbarPreferLatLon, replayActive) { ll, rp -> ll to rp }) { g, link, tick, key, opts ->
                    g.copy(link = link.copy(locationEnabled = tick.second), receiverKey = key, tick = tick.first,
                        preferLatLon = opts.first, replayActive = opts.second)
                }
                ToolbarPipeline.frames(inputs, coordinateSystemService::convertFix,
                    nowMs = { android.os.SystemClock.elapsedRealtime() })
                    .onEach { frame -> renderToolbarFrame(frame) }
                    .retryForever { e, attempt ->
                        // Retried forever with back-off: the first failure in full, then at most once a minute.
                        DiagnosticsLogger.every(DiagTags.GNSS, "toolbar.pipeline.error", 60_000L, 'E', e) {
                            "Toolbar GNSS pipeline failed (will retry) attempt=${attempt + 1}"
                        }
                        val source = runCatching { settingsRepository.locationSource.first() }.getOrDefault(LocationSourceType.INTERNAL)
                        toolbarRenderer.render(GnssToolbarStateMapper.error(source, externalReceiverLabel))
                    }
                    .collect()
            }
        }
        // Immediate reset whenever the LIVE provider actually changes (the switchboard rebinds).
        // This complements the locationSource reset above: that one fires on the user's selection,
        // this one fires when routing actually flips (e.g. External finishing its connect), so the
        // previous provider's fix/sats/correction/battery clear right away.
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var previousProvider: app.surrealar.gnss.source.SourceSettings.ProviderChoice? = null
                sourceSettings.activeProvider.collect { provider ->
                    if (previousProvider != null && previousProvider != provider) {
                        val isExternalLive =
                            provider == app.surrealar.gnss.source.SourceSettings.ProviderChoice.EXTERNAL_TCP
                        // The switch itself is logged once by GnssFeedTracker ("Source A → B"); this is detail.
                        val from = previousProvider
                        DiagnosticsLogger.d(DiagTags.GNSS) { "Toolbar reset: provider $from → $provider" }
                        val liveSource = if (isExternalLive) LocationSourceType.EXTERNAL else LocationSourceType.INTERNAL
                        withContext(Dispatchers.Main) { resetStatusTokensForSource(liveSource) }
                    }
                    previousProvider = provider
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

    /** Applies one pipeline frame (Main thread): diagnostics bookkeeping, then the renderer. */
    private fun renderToolbarFrame(frame: ToolbarFrame) {
        val (fix, sky, source) = Triple(frame.inputs.fix, frame.inputs.sky, frame.inputs.source)
        when (val result = frame.result) {
            is ToolbarMapResult.Ignore -> {
                app.surrealar.gnss.debug.GnssDebugLog.every("toolbar.ignore.${result.reason}", "toolbar") {
                    "fix ignored (${result.reason}): selected=$source fixProvider=${fix?.provider}"
                }
                // Wrong-provider / stale / invalid fix: keep the existing placeholders untouched
                // (throttled diagnostics only).
                logToolbarIgnore(result.reason)
            }
            is ToolbarMapResult.Render -> {
                val state = result.state
                app.surrealar.ui.toolbar.ToolbarLinkLog.onState(state)
                app.surrealar.gnss.debug.GnssDebugLog.every("toolbar.render", "toolbar") {
                    "shown: src=${state.sourceText} sol=${state.fixText} sats=${state.satelliteText} " +
                        "acc=${state.accuracyText} (${state.accuracyLevel}) ll=${state.latLonText} " +
                        "alt=${state.altitudeText} waiting=${state.isWaiting} | from fix: " +
                        (fix?.let { "${it.provider} msl=${it.altMslM} ellH=${it.altEllipsoidalM} " +
                            "hAcc=${it.hAccM} sats=${it.satsUsed}/${it.satsVisible} t=${it.timeUtc}" } ?: "none") +
                        " | sky used=${sky.totalUsed} visible=${sky.totalVisible}"
                }
                if (state.isWaiting) {
                    if (source == LocationSourceType.EXTERNAL && !loggedExternalWaiting) {
                        loggedExternalWaiting = true
                        DiagnosticsLogger.i(DiagTags.GNSS, "Toolbar waiting for first external fix")
                    }
                    latestSkySnapshot = null
                } else {
                    latestSkySnapshot = sky
                    if (source == LocationSourceType.EXTERNAL && !loggedFirstExternalToolbarFix) {
                        loggedFirstExternalToolbarFix = true
                        loggedExternalWaiting = false
                        DiagnosticsLogger.i(DiagTags.GNSS, "Toolbar showing first external fix")
                    }
                }
                lastToolbarState = state
                toolbarRenderer.render(state)
            }
        }
    }

    /**
     * Link state for the toolbar, read-only: the external adapter's connection phase and the monotonic
     * time of the last raw NMEA line (sampled; at most twice a second).
     */
    private fun toolbarLinkFlow(): Flow<app.surrealar.ui.toolbar.ToolbarLinkInputs> = combine(
        externalAdapter.connectionState.map {
            if (it == io.github.pennstatecs.geokit.gnss.link.ReconnectingNmeaAdapter.ConnectionState.CONNECTED) app.surrealar.ui.toolbar.ExternalLinkPhase.CONNECTED
            else app.surrealar.ui.toolbar.ExternalLinkPhase.CONNECTING
        },
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        switchboard.rawNmea.sample(500L).map<Unit, Long?> { android.os.SystemClock.elapsedRealtime() }.onStart { emit(null) },
    ) { phase, lastRaw -> app.surrealar.ui.toolbar.ToolbarLinkInputs(externalPhase = phase, lastRawNmeaAtMs = lastRaw) }

    /** Once a second: (monotonic now, location permission + setting on). Keeps ages counting. */
    private fun toolbarTickFlow(): Flow<Pair<Long, Boolean>> = flow {
        while (true) {
            emit(android.os.SystemClock.elapsedRealtime() to isLocationEnabledForToolbar())
            delay(1_000L)
        }
    }

    private fun isLocationEnabledForToolbar(): Boolean {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val lm = getSystemService(android.location.LocationManager::class.java)
        val on = lm != null && androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)
        return granted && on
    }

    /** Identifies the external receiver (profile + host:port): a change forgets the toolbar's last fix. */
    private fun receiverKeyFlow(): Flow<String> =
        settingsRepository.externalReceiverSettings.map { "${it.profile.prefKey}@${it.tcpHost}:${it.tcpPort}" }
            .onStart { emit("") }.distinctUntilChanged()

    // ── Toolbar tap actions (wiring only; what's available comes from the mapped state) ──

    private fun setupToolbarActions() {
        toolbarRenderer.bindActions(app.surrealar.ui.toolbar.ToolbarActions(
            onStatus = ::openToolbarGnssStatus,
            onBattery = ::openReceiverDetails,
            // Lat/lon ↔ grid only when a grid system is configured; otherwise a no-op.
            onCoordTap = { if (lastToolbarState?.coordToggleAvailable == true) toolbarPreferLatLon.value = !toolbarPreferLatLon.value },
            onCoordLongPress = ::copyToolbarPosition,
        ))
    }

    /** SOL / SATS: the sky + satellite view (Developer Tools) when enabled, else Settings › Receiver status. */
    private fun openToolbarGnssStatus() {
        if (lastDevToolsEnabled == true) navigateFromToolbar(R.id.nav_development) else openReceiverDetails()
    }

    /** Battery: Settings › Receiver (status, battery, device info). */
    private fun openReceiverDetails() = navigateFromToolbar(
        R.id.nav_settings,
        androidx.core.os.bundleOf(app.surrealar.ui.settings.SettingsFragment.ARG_INITIAL_CATEGORY_ID to
            app.surrealar.ui.settings.SettingsFragment.CATEGORY_RECEIVER),
    )

    private fun navigateFromToolbar(destId: Int, args: android.os.Bundle? = null) {
        if (!::navController.isInitialized) return
        runCatching {
            // Pop back to Home first so toolbar taps don't stack screens. No restoreState here: these
            // taps can carry arguments (e.g. open Settings on the receiver category) that a restored
            // copy would ignore.
            navController.navigate(destId, args, androidx.navigation.navOptions {
                popUpTo(navController.graph.startDestinationId)
                launchSingleTop = true
            })
        }.onFailure { DiagnosticsLogger.w(DiagTags.NAV, "Toolbar navigation failed destId=$destId", it) }
    }

    /** Long-press on the coordinates: copy what's shown (position + height) to the clipboard. */
    private fun copyToolbarPosition() {
        val s = lastToolbarState
        if (s == null || s.latLonText == "--") {
            android.widget.Toast.makeText(this, "No position to copy", android.widget.Toast.LENGTH_SHORT).show(); return
        }
        val text = "${s.coordLabel} ${s.latLonText}, ${s.altitudeLabel} ${s.altitudeText}"
        getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText("Position", text))
        android.widget.Toast.makeText(this, "Position copied", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** The active external receiver profile's short label; "External" until settings have loaded. */
    private fun externalLabelFlow(): Flow<String> =
        settingsRepository.externalReceiverSettings
            .map { it.profile.shortLabel.ifBlank { app.surrealar.gnss.format.GnssStatusFormatter.EXTERNAL_FALLBACK_LABEL } }
            .onStart { emit(externalReceiverLabel) }
            .distinctUntilChanged()

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
        // Reset the one-shot transition logs only on a real INTERNAL ↔ EXTERNAL switch, so a new source
        // session re-logs its waiting → first-fix flow. This also runs when the activity comes back to
        // the foreground (e.g. returning from the model viewer) with the same source: no re-log then.
        if (source != lastLoggedToolbarSource) {
            lastLoggedToolbarSource = source
            loggedExternalWaiting = false
            loggedFirstExternalToolbarFix = false
        }
        toolbarRenderer.render(GnssToolbarStateMapper.waiting(source, externalReceiverLabel))
    }

    // One-shot transition logs for the External waiting → first-fix flow at startup.
    private var loggedExternalWaiting = false
    private var loggedFirstExternalToolbarFix = false
    private var lastLoggedToolbarSource: LocationSourceType? = null

    /**
     * Dropped-fix diagnostics, throttled per reason to once a minute. Wrong-provider fixes are normal
     * while an External source connects (internal fixes keep arriving), so that reason is debug-only;
     * stale / invalid fixes get one info line a minute. The Home map logs the same gate debug-only.
     */
    private fun logToolbarIgnore(reason: String) {
        val level = if (reason == "wrong-provider") 'D' else 'I'
        DiagnosticsLogger.every(DiagTags.GNSS, "toolbar.ignore.$reason", 60_000L, level) { "Toolbar ignored fix reason=$reason" }
    }

    private fun updateBatteryVisibility(requested: Boolean) {
        batteryRequested = requested
        // Shown for an external source whose receiver reports a battery (ReceiverDevice capability).
        batteryShouldShow = requested && receiverDevices.capabilities.value.hasBattery
        val b = receiverDevices.battery.value
        updateBatteryIcon(b?.percent, b?.isCharging)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // MainActivity handles configChanges itself, so it is not recreated on rotation. Re-apply
        // orientation-dependent status-bar tweaks immediately:
        //  - show/hide the SRC label (hidden in portrait to save horizontal space), and
        //  - re-render the battery token so its graphical icon is shown (landscape) or hidden
        //    (portrait, percentage text only) to match the new orientation.
        toolbarRenderer.setPortrait(isPortrait)
        if (lastBatteryPercent != null) {
            updateBatteryIcon(lastBatteryPercent, lastBatteryCharging)
        }
    }

    private fun updateBatteryIcon(percentage: Int?, charging: Boolean? = null) {
        // Cache the latest reading so onConfigurationChanged() can re-render for the new orientation.
        lastBatteryPercent = percentage
        lastBatteryCharging = charging
        toolbarRenderer.renderBattery(batteryShouldShow, percentage, charging, textOnly = batteryTextOnly)
    }

    /**
     * Binds the Coordinates / Models drawer rows' [app:actionLayout] badges to the live count
     * flows the repositories already expose (the same flows Home uses). Lifecycle-aware: collection
     * runs only while STARTED and is torn down with the activity lifecycle, so there is no leak and
     * no duplicate collector per drawer open. Badges are purely informational and never intercept
     * the row's click.
     */
    private fun setupDrawerCountBadges(navView: NavigationView) {
        val coordBadge = navView.menu.findItem(R.id.nav_view_coordinates)?.actionView
            ?.findViewById<TextView>(R.id.text_nav_badge)
        val modelBadge = navView.menu.findItem(R.id.nav_models)?.actionView
            ?.findViewById<TextView>(R.id.text_nav_badge)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                coordinateRepository.coordinateCountFlow.collect { count ->
                    coordBadge?.apply {
                        text = app.surrealar.ui.common.DrawerBadgeFormatter.format(count)
                        // Row title already announces "Coordinates"; the badge adds just the count.
                        contentDescription = "$count saved"
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                modelRepository.observeModelCount().collect { count ->
                    modelBadge?.apply {
                        text = app.surrealar.ui.common.DrawerBadgeFormatter.format(count)
                        // Row title already announces "Models"; the badge adds just the count.
                        contentDescription = "$count imported"
                    }
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
                DiagnosticsLogger.e(DiagTags.GNSS, "NMEA replay asset listing failed", e)
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
            // Replay runs as a regular source (ProviderChoice.REPLAY): same NmeaFuser and FixSwitchboard
            // path as live data, so the map, toolbar and sky view show it. Restarting replay keeps the
            // provider that was active before the FIRST replay.
            val replaySource = AssetNmeaReplaySource(
                context = this,
                assetFileName = fileName,
                // ~15 sentences per 1 Hz Emlid epoch → roughly real time (was 1 s per sentence).
                delayBetweenLines = 60L,
                name = "Replay ($fileName)"
            )
            if (sourceSettings.activeProvider.value == app.surrealar.gnss.source.SourceSettings.ProviderChoice.REPLAY) {
                // Switch away and back so the adapter restarts with the new file.
                replayAdapter.stop()
                replayAdapter.load(replaySource)
                replayAdapter.start()
            } else {
                replayAdapter.load(replaySource)
                providerBeforeReplay = gnssSourceCoordinator.startReplay("user started replay of $fileName")
            }

            // Show confirmation dialog with option to stop
            AlertDialog.Builder(this)
                .setTitle("NMEA Replay Started")
                .setMessage("Replaying NMEA data from $fileName\n\nThis will simulate GNSS data for demo and testing purposes.")
                .setPositiveButton("Stop Replay") { _, _ ->
                    stopNmeaReplay()
                }
                .setNegativeButton("Keep Running", null)
                .show()

            // The source switch to REPLAY is logged by the coordinator / GnssFeedTracker.
            DevLog.d("MainActivity") { "Started NMEA replay from $fileName" }

        } catch (e: Exception) {
            DiagnosticsLogger.e(DiagTags.GNSS, "NMEA replay start failed file=$fileName", e)
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
            providerBeforeReplay?.let { gnssSourceCoordinator.endReplay(it, "user stopped replay") }
            providerBeforeReplay = null

            DevLog.d("MainActivity") { "Stopped NMEA replay" }

        } catch (e: Exception) {
            DiagnosticsLogger.e(DiagTags.GNSS, "NMEA replay stop failed", e)
        }
    }
}
