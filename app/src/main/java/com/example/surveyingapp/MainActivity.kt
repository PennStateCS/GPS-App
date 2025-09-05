package com.example.surveyingapp

import android.Manifest
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import com.example.surveyingapp.domain.model.Fix
import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.domain.model.RtkStatus
import com.example.surveyingapp.service.LocationService
import com.example.surveyingapp.databinding.ActivityMainBinding
import com.example.surveyingapp.ui.openinar.OpenInARFragment
import com.example.surveyingapp.ui.settings.SettingsFragment
import com.example.surveyingapp.ui.common.updateStatusBar
import com.example.surveyingapp.util.PermissionManager
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    // Single DB instance (Room handles threading); lazy avoids early init cost
    private val database by lazy { AppDatabase.getDatabase(applicationContext) }

    private lateinit var prefs: SharedPreferences
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SettingsFragment.PREF_DEV_TOOLS) {
            updateDevToolsVisibility() // React immediately to developer tools toggle
        }
    }

    private var statusCollectJob: Job? = null // Cancels aggregated collectors when Activity is destroyed

    // Comprehensive permission launchers using the new PermissionManager
    private val essentialPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val deniedPermissions = permissions.filterValues { !it }.keys
        if (deniedPermissions.isEmpty()) {
            // All essential permissions granted - request optional ones
            requestOptionalPermissions()
        } else {
            // Some essential permissions denied - show rationale
            showPermissionRationale(deniedPermissions.toList(), isEssential = true)
        }
    }

    private val optionalPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val deniedPermissions = permissions.filterValues { !it }.keys
        if (deniedPermissions.isNotEmpty()) {
            // Optional permissions denied - continue without them but inform user
            showPermissionRationale(deniedPermissions.toList(), isEssential = false)
        }
        // Start location service regardless of optional permissions
        ensureLocationServiceStarted()
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            // Background location denied - app can still function but with limitations
            showBackgroundLocationRationale()
        }
    }

    private lateinit var statusBarTv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Preferences early to reduce potential UI flicker (e.g. theming decisions before inflate)
        prefs = getSharedPreferences(SettingsFragment.PREFS_NAME, MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Cache status bar TextView (inside included layouts)
        statusBarTv = findViewById(R.id.text_status_bar_compact)
        setSupportActionBar(binding.appBarMain.toolbar)

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView

        val devEnabled = prefs.getBoolean(SettingsFragment.PREF_DEV_TOOLS, false)

        // Initial Nav setup (top-level destinations influence drawer + Up button behavior)
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
        updateDevToolsVisibility() // re-check after menu inflation

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment

        // Attach DAO to OpenInAR fragment the moment its view is created (ensures dependency injection without full DI framework)
        navHostFragment.childFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager,
                    f: Fragment,
                    v: android.view.View,
                    savedInstanceState: Bundle?
                ) {
                    if (f is OpenInARFragment) {
                        f.attachCoordinateDao(database.coordinateDao())
                    }
                }
            },
            true
        )

        // Fallback: on destination change ensure fragment (if AR) has DAO (covers rapid navigation edge cases)
        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id == R.id.nav_open_in_ar) {
                navHostFragment.childFragmentManager.executePendingTransactions()
                val current = navHostFragment.childFragmentManager.primaryNavigationFragment
                    ?: navHostFragment.childFragmentManager.fragments.firstOrNull()
                if (current is OpenInARFragment) current.attachCoordinateDao(database.coordinateDao())
            }
        }

        // Start live status observers (satellite / RTK info banner)
        startStatusBarObservers()

        // Start comprehensive permission request process
        requestAllPermissions()
    }

    /**
     * Start the comprehensive permission request process
     */
    private fun requestAllPermissions() {
        if (!PermissionManager.hasEssentialPermissions(this)) {
            // Request essential permissions first
            requestEssentialPermissions()
        } else {
            // Essential permissions granted, request optional ones
            requestOptionalPermissions()
        }
    }

    /**
     * Request essential permissions required for core functionality
     */
    private fun requestEssentialPermissions() {
        val missingPermissions = PermissionManager.getMissingEssentialPermissions(this)
        if (missingPermissions.isNotEmpty()) {
            essentialPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            requestOptionalPermissions()
        }
    }

    /**
     * Request optional permissions that enhance functionality
     */
    private fun requestOptionalPermissions() {
        val missingPermissions = PermissionManager.getMissingOptionalPermissions(this)
        if (missingPermissions.isNotEmpty()) {
            optionalPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            ensureLocationServiceStarted()
        }
    }

    /**
     * Request background location permission (must be done after foreground permissions)
     */
    private fun requestBackgroundLocationPermission() {
        if (PermissionManager.hasLocationPermissions(this) &&
            !PermissionManager.hasBackgroundLocationPermission(this)) {
            backgroundLocationLauncher.launch(PermissionManager.BACKGROUND_LOCATION_PERMISSION)
        }
    }

    /**
     * Show rationale dialog for denied permissions
     */
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
                if (isEssential) {
                    requestEssentialPermissions()
                } else {
                    ensureLocationServiceStarted()
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                if (isEssential) {
                    // For essential permissions, show warning that app may not work properly
                    showEssentialPermissionWarning()
                } else {
                    ensureLocationServiceStarted()
                }
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Show warning when essential permissions are denied
     */
    private fun showEssentialPermissionWarning() {
        AlertDialog.Builder(this)
            .setTitle("Warning")
            .setMessage("Some core features may not work without the required permissions. You can grant them later in the app settings.")
            .setPositiveButton("OK") { _, _ ->
                ensureLocationServiceStarted()
            }
            .show()
    }

    /**
     * Show rationale for background location permission
     */
    private fun showBackgroundLocationRationale() {
        AlertDialog.Builder(this)
            .setTitle("Background Location")
            .setMessage("Background location access allows continuous tracking when the app is not actively being used. This is useful for extended surveying sessions.\n\nYou can enable this later in Settings > Location if needed.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun ensureLocationServiceStarted() {
        // Check if we have location permissions
        if (!PermissionManager.hasLocationPermissions(this)) {
            // Can't start location service without location permissions
            return
        }

        // Start the location service
        LocationService.start(this)

        // After a short delay, offer to request background location if not already granted
        // This follows Google's best practices of requesting background location separately
        lifecycleScope.launch {
            kotlinx.coroutines.delay(3000) // Wait 3 seconds after app startup
            if (PermissionManager.hasLocationPermissions(this@MainActivity) &&
                !PermissionManager.hasBackgroundLocationPermission(this@MainActivity)) {
                requestBackgroundLocationPermission()
            }
        }
    }

    @OptIn(FlowPreview::class)
    // Option B: lifecycle + combine + debounce with GNSS status flow
    private fun startStatusBarObservers() {
        statusCollectJob?.cancel()
        statusCollectJob = lifecycleScope.launch {
            val settingsRepo = SurveyingApp.settingsRepo
            val initialSource = settingsRepo.locationSource.first()
            val initialLabel = if (initialSource == LocationSourceType.INTERNAL) "Internal" else "RS2+"
            statusBarTv.text = "Status: $initialLabel - No fix"
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val manager = SurveyingApp.locationManager
                combine(
                    manager.fixFlow,
                    settingsRepo.locationSource,
                    manager.gnssStatusFlow
                ) { fix: Fix, source: LocationSourceType, gnss -> Triple(fix, source, gnss) }
                    .debounce(200)
                    .distinctUntilChanged { old, new ->
                        // Distinct based on text produced; quick compare without recomputing full string by key parts
                        val oldKey = "${old.second}|${old.first.rtkStatus}|${old.first.lat}|${old.first.lon}|${old.first.hAccM}|${old.first.vAccM}"
                        val newKey = "${new.second}|${new.first.rtkStatus}|${new.first.lat}|${new.first.lon}|${new.first.hAccM}|${new.first.vAccM}"
                        oldKey == newKey
                    }
                    .collectLatest { (fix, source, gnss) ->
                        val sourceLabel = if (source == LocationSourceType.INTERNAL) "Internal" else "RS2+"
                        val text = updateStatusBar(sourceLabel, fix, gnss)
                        statusBarTv.text = text
                        val colorRes = when {
                            source == LocationSourceType.INTERNAL -> android.R.color.white
                            fix.rtkStatus == RtkStatus.FIX -> R.color.status_fix
                            fix.rtkStatus == RtkStatus.FLOAT -> R.color.status_float
                            fix.rtkStatus == RtkStatus.DGPS -> R.color.status_dgps
                            fix.rtkStatus == RtkStatus.SINGLE -> R.color.status_single
                            else -> R.color.status_no_fix
                        }
                        statusBarTv.setTextColor(ContextCompat.getColor(this@MainActivity, colorRes))
                    }
            }
        }
    }

    private fun updateDevToolsVisibility() {
        val enabled = prefs.getBoolean(SettingsFragment.PREF_DEV_TOOLS, false)
        val menu = binding.navView.menu
        val devItem = menu.findItem(R.id.nav_development)
        devItem?.isVisible = enabled
        // Re-derive top-level destinations so Up button behaves correctly when dev section toggles
        val topLevel = mutableSetOf(
            R.id.nav_home,
            R.id.nav_models,
            R.id.nav_view_coordinates,
            R.id.nav_render_map,
            R.id.nav_open_in_ar,
            R.id.nav_settings
        )
        if (enabled) topLevel.add(R.id.nav_development)
        appBarConfiguration = AppBarConfiguration(topLevel, binding.drawerLayout)
        setupActionBarWithNavController(navController, appBarConfiguration)
        // If dev item hidden while currently on it, navigate home to avoid orphan destination
        if (!enabled && navController.currentDestination?.id == R.id.nav_development) {
            navController.navigate(R.id.nav_home)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> {
            findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_settings)
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    override fun onDestroy() {
        statusCollectJob?.cancel() // Prevent leak of coroutines referencing Views
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    // onStart/onStop overrides currently no-op
    override fun onStart() { super.onStart() }
    override fun onStop() { super.onStop() }
}
