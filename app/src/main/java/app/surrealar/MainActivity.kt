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
import app.surrealar.service.LocationService
import app.surrealar.util.PermissionManager
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

private const val LOG_GNSS_UI = false

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

    // Track last GNSS data update time for stale detection
    @Volatile
    private var lastDataUpdateTime = System.currentTimeMillis()

    // Track latest sky snapshot for accurate satellite counts
    @Volatile
    private var latestSkySnapshot: io.github.pennstatecs.geokit.gnss.model.SkySnapshot? = null

    // Hilt injected GNSS components
    @Inject lateinit var switchboard: FixSwitchboard
    @Inject lateinit var coordinateSystemService: app.surrealar.coords.CoordinateSystemService
    @Inject lateinit var externalAdapter: ExternalAdapter
    @Inject lateinit var replayAdapter: app.surrealar.gnss.replay.ReplayAdapter
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
    private lateinit var tokenCoord: ToolbarTokens
    private lateinit var tokenAlt: ToolbarTokens
    private lateinit var tokenBatt: ToolbarTokens

    /** View-only renderer for the GNSS status toolbar (see [GnssToolbarRenderer]). */
    private lateinit var toolbarRenderer: GnssToolbarRenderer

    /** Short label of the selected external receiver profile (e.g. "RS2+"/"RS4"); updated from settings. */
    /** Active external profile's short label; "External" until settings load (never blank, never a vendor guess). */
    @Volatile private var externalReceiverLabel: String = app.surrealar.gnss.format.GnssStatusFormatter.EXTERNAL_FALLBACK_LABEL

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
            // Permission just became available — recover an internal provider that was waiting for it.
            runCatching { switchboard.retryActiveSourceIfStalled("permission-granted") }
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
        tokenAge    = findToken(R.id.token_age)
        tokenCoord  = findToken(R.id.token_coord)
        tokenAlt    = findToken(R.id.token_alt)
        tokenBatt   = findToken(R.id.token_batt)

        // View-only toolbar renderer. Color resolution and battery-polling lifecycle stay here.
        toolbarRenderer = GnssToolbarRenderer(
            source = tokenSource, fix = tokenFix, sats = tokenSats, acc = tokenAcc, age = tokenAge,
            coord = tokenCoord, alt = tokenAlt, batt = tokenBatt,
            colorFor = ::levelColor,
            onBatteryVisibilityChanged = ::updateBatteryVisibility
        )

        // Static labels
        tokenSource.label.setText(R.string.status_token_src)
        tokenFix.label.text = ""   // dynamic: "GPS" / "RTK Float" / etc.
        tokenSats.label.setText(R.string.status_token_sats)
        tokenAcc.label.setText(R.string.status_token_hacc)
        tokenAge.label.setText(R.string.status_token_age)
        tokenAge.root.isVisible = false
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
                navController.navigate(destId)
                true
            } catch (e: IllegalArgumentException) {
                android.util.Log.w("MainActivity", "Drawer destination not found: $destId", e)
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
                app.surrealar.gnss.source.SourceSettings.ProviderChoice.INTERNAL 
            }
            
            val isInternal = initialProvider == app.surrealar.gnss.source.SourceSettings.ProviderChoice.INTERNAL
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
        // The switchboard is started once by DI (GnssModule.provideFixSwitchboard). Calling start()
        // again used to cancel and recreate its provider watcher, racing a startup provider switch;
        // start() is now a no-op when already running, so this is only a safety net.
        try {
            switchboard.start()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error starting GNSS switchboard: ", e)
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

        // Recover the internal GNSS provider if its startup was blocked waiting for location
        // permission (cold-launch race) or for location services. Resuming is the moment the user
        // returns after granting permission / enabling location in system settings. No-op when the
        // active source is already streaming or not waiting, so it never double-registers.
        runCatching { switchboard.retryActiveSourceIfStalled("app-resume") }

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
                val inputs = combine(gnss, toolbarLinkFlow(), toolbarTickFlow(), receiverKeyFlow()) { g, link, tick, key ->
                    g.copy(link = link.copy(locationEnabled = tick.second), receiverKey = key, tick = tick.first)
                }
                ToolbarPipeline.frames(inputs, coordinateSystemService::convertFix,
                    nowMs = { android.os.SystemClock.elapsedRealtime() })
                    .onEach { frame -> renderToolbarFrame(frame) }
                    .retryForever { e, attempt ->
                        android.util.Log.e("MainActivity", "GNSS toolbar pipeline error (retry #${attempt + 1})", e)
                        app.surrealar.util.DiagnosticsLogger.w("Toolbar", "toolbar pipeline error, retrying: ${e.javaClass.simpleName}")
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
                        app.surrealar.util.DiagnosticsLogger.i(
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
                        app.surrealar.util.DiagnosticsLogger.i("Toolbar", "External selected, waiting for current fix")
                    }
                    latestSkySnapshot = null
                } else {
                    latestSkySnapshot = sky
                    if (source == LocationSourceType.EXTERNAL && !loggedFirstExternalToolbarFix) {
                        loggedFirstExternalToolbarFix = true
                        loggedExternalWaiting = false
                        app.surrealar.util.DiagnosticsLogger.i("Toolbar", "First current external fix displayed after startup")
                    }
                }
                lastDataUpdateTime = System.currentTimeMillis()
                toolbarRenderer.render(state)
                tokenSource.value.alpha = 1.0f
            }
        }
    }

    /**
     * Link state for the toolbar, read-only: the external adapter's connection phase and the monotonic
     * time of the last raw NMEA line (sampled; at most twice a second).
     */
    private fun toolbarLinkFlow(): Flow<app.surrealar.ui.toolbar.ToolbarLinkInputs> = combine(
        externalAdapter.connectionState.map {
            if (it == ExternalAdapter.ConnectionState.CONNECTED) app.surrealar.ui.toolbar.ExternalLinkPhase.CONNECTED
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
        lastDataUpdateTime = System.currentTimeMillis()
        // Reset the one-shot transition logs so each new source session (after an INTERNAL ↔ EXTERNAL
        // switch) re-logs its waiting → first-fix flow accurately instead of staying silent.
        loggedExternalWaiting = false
        loggedFirstExternalToolbarFix = false
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
            app.surrealar.util.DiagnosticsLogger.w("Toolbar", "Ignored $reason fix for live display")
        }
    }

    private fun updateBatteryVisibility(requested: Boolean) {
        batteryRequested = requested
        val shouldShow = requested && receiverDevices.capabilities.value.hasBattery
        batteryShouldShow = shouldShow
        if (shouldShow) {
            // External source: render the repository's latest reading. The token stays hidden
            // until real battery data arrives — updateBatteryIcon() reveals it (and its separator)
            // once there is a percentage, and hides it again if the data is lost.
            val b = receiverDevices.battery.value
            updateBatteryIcon(b?.percent, b?.isCharging)
        } else {
            // Internal source (or no receiver): hide the token + its separator.
            tokenAlt.separator?.isVisible = false
            tokenBatt.root.isVisible = false
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

        // Charging turns the fill green, except below 20 %: a critical level keeps its warning
        // color and only gains the bolt, so plugging in never hides a nearly-dead battery.
        val fillColor = when {
            charging == true && percentage >= 20 -> 0xFF4CAF50.toInt()
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
            providerBeforeReplay?.let { gnssSourceCoordinator.endReplay(it, "user stopped replay") }
            providerBeforeReplay = null

            // Reset status display
            lifecycleScope.launch {
                val prov = try { sourceSettings.activeProvider.first() } catch (_: Exception) { app.surrealar.gnss.source.SourceSettings.ProviderChoice.INTERNAL }
                val srcLabel = if (prov == app.surrealar.gnss.source.SourceSettings.ProviderChoice.INTERNAL) "Internal" else externalReceiverLabel
                tokenSource.value.text = srcLabel
            }

            android.util.Log.i("MainActivity", "Stopped NMEA replay")

        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error stopping NMEA replay", e)
        }
    }
}
