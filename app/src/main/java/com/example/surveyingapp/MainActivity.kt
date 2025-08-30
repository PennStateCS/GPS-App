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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.surveyingapp.data.AppDatabase
import com.example.surveyingapp.data.location.Fix
import com.example.surveyingapp.data.location.LocationStatus
import com.example.surveyingapp.data.location.RtkStatus
import com.example.surveyingapp.service.LocationService
import com.example.surveyingapp.databinding.ActivityMainBinding
import com.example.surveyingapp.ui.openinar.OpenInARFragment
import com.example.surveyingapp.ui.settings.SettingsFragment
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
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

    // Permission launcher for Android 13+ notification permission (needed for foreground service visibility)
    private val notifPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) LocationService.start(this) else {
            // Foreground notification suppressed; service may still start later when permission granted.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Preferences early to reduce potential UI flicker (e.g. theming decisions before inflate)
        prefs = getSharedPreferences(SettingsFragment.PREFS_NAME, MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.appBarMain.toolbar)

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView

        val devEnabled = prefs.getBoolean(SettingsFragment.PREF_DEV_TOOLS, false)

        // Initial Nav setup (top-level destinations influence drawer + Up button behavior)
        navController = findNavController(R.id.nav_host_fragment_content_main)
        val initialTopLevel = mutableSetOf(
            R.id.nav_home,
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
        // Ensure location service running early (starts GNSS streaming + notification)
        ensureLocationServiceStarted()
    }

    private fun ensureLocationServiceStarted() {
        if (Build.VERSION.SDK_INT >= 33) { // POST_NOTIFICATIONS required only on T+ for foreground service notification
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        LocationService.start(this)
    }

    private fun startStatusBarObservers() {
        val sourceTv: TextView = findViewById(R.id.text_source)
        val statusTv: TextView = findViewById(R.id.text_status)
        statusCollectJob?.cancel() // Avoid duplicate collectors after recreation
        val manager = SurveyingApp.locationManager
        val settingsRepo = SurveyingApp.settingsRepo

        statusCollectJob = lifecycleScope.launch {
            // Local state cache (prefer combine() in future for cleaner reactive style)
            var latestFix: Fix? = null
            var latestStatus: LocationStatus = LocationStatus.Idle
            var externalConnType: String = settingsRepo.externalConnType.first() // initial snapshot
            var locationSource: String = settingsRepo.locationSource.first()

            // Update fields reactively; using separate coroutines avoids blocking one another
            launch { settingsRepo.externalConnType.collectLatest { externalConnType = it } }
            launch { settingsRepo.locationSource.collectLatest { locationSource = it } }

            fun mapFixType(fix: Fix?, external: Boolean, streaming: Boolean): String =
                if (!external) {
                    if (fix != null) "Fused" else if (streaming) "Fused (pending)" else "Fused"
                } else when (fix?.rtkStatus) {
                    RtkStatus.FIX -> "Fixed RTK"
                    RtkStatus.FLOAT -> "Float"
                    RtkStatus.DGPS -> "DGPS"
                    RtkStatus.SINGLE -> "Single"
                    RtkStatus.INVALID, null -> if (streaming) "No Fix" else "--"
                }

            fun mapConnection(status: LocationStatus, external: Boolean): String = when (status) {
                is LocationStatus.Connecting -> "Connecting"
                is LocationStatus.Error -> "Error"
                LocationStatus.Idle -> if (external) "Disconnected" else "Idle"
                is LocationStatus.Streaming -> "Connected"
            }
            fun mapSourceLabel(source: String, connType: String): String =
                if (source == "external") "RS2+ ${connType.uppercase()}" else "Internal GPS"

            fun satsPart(f: Fix?): String {
                val used = f?.satsUsed; val vis = f?.satsVisible
                return when {
                    used != null && vis != null -> "${used}/${vis} sats"
                    used != null -> "${used} sats"
                    else -> "-- sats"
                }
            }
            fun dopPart(f: Fix?): String {
                val pdop = f?.pdop?.let { String.format(java.util.Locale.US, "%.1f", it) }
                val hdop = f?.hdop?.let { String.format(java.util.Locale.US, "%.1f", it) }
                return when {
                    pdop != null && hdop != null -> "PDOP $pdop / HDOP $hdop"
                    pdop != null -> "PDOP $pdop"
                    hdop != null -> "HDOP $hdop"
                    else -> "PDOP -- / HDOP --"
                }
            }
            fun updateUi() {
                val external = locationSource == "external"
                val streaming = latestStatus is LocationStatus.Streaming
                val sourceLine = mapSourceLabel(locationSource, externalConnType) + " • " + mapConnection(latestStatus, external)
                val fixType = mapFixType(latestFix, external, streaming)
                val detailLine = listOf(fixType, satsPart(latestFix), dopPart(latestFix)).joinToString(" • ")
                sourceTv.text = sourceLine
                statusTv.text = detailLine
            }

            // Collect location fixes & status concurrently and update when either changes
            launch { manager.fixes.collectLatest { latestFix = it; updateUi() } }
            launch { manager.status.collectLatest { latestStatus = it; updateUi() } }
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

    // onStart/onStop overrides currently no-op – safe to remove unless future hooks needed
    override fun onStart() { super.onStart() }
    override fun onStop() { super.onStop() }
}
