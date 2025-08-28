package com.example.surveyingapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.example.surveyingapp.data.AppDatabase
import com.example.surveyingapp.databinding.ActivityMainBinding
import com.example.surveyingapp.ui.openinar.OpenInARFragment
import com.example.surveyingapp.ui.settings.SettingsFragment
import com.google.android.material.navigation.NavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    // Use applicationContext to avoid leaking the Activity
    private val database by lazy { AppDatabase.getDatabase(applicationContext) }

    private lateinit var prefs: SharedPreferences
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SettingsFragment.PREF_DEV_TOOLS) {
            updateDevToolsVisibility()
        }
        if (key == SettingsFragment.PREF_DARK_MODE) {
            applyDarkModePreference()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Init preferences early so we can apply dark mode before view inflation (prevents flicker)
        prefs = getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val initialDark = prefs.getBoolean(SettingsFragment.PREF_DARK_MODE, false)
        AppCompatDelegate.setDefaultNightMode(
            if (initialDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBarMain.toolbar)

        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView

        val devEnabled = prefs.getBoolean(SettingsFragment.PREF_DEV_TOOLS, false)

        // Nav setup
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

        // Apply initial visibility state for dev tools (after menu inflated by setupWithNavController)
        updateDevToolsVisibility()

        // Ensure we hook into fragments CREATED inside the NavHost
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment

        // 1) Attach when the OpenInAR fragment's view is created
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
            /* recursive = */ true
        )

        // 2) Also try attaching on destination change (covers immediate nav cases)
        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id == R.id.nav_open_in_ar) {
                // Execute pending transactions so the fragment exists
                navHostFragment.childFragmentManager.executePendingTransactions()
                // Prefer the primary navigation fragment; fall back to first
                val current = navHostFragment.childFragmentManager.primaryNavigationFragment
                    ?: navHostFragment.childFragmentManager.fragments.firstOrNull()
                if (current is OpenInARFragment) {
                    current.attachCoordinateDao(database.coordinateDao())
                }
            }
        }
    }

    private fun updateDevToolsVisibility() {
        val enabled = prefs.getBoolean(SettingsFragment.PREF_DEV_TOOLS, false)
        val menu = binding.navView.menu
        val devItem = menu.findItem(R.id.nav_development)
        devItem?.isVisible = enabled
        // Rebuild top-level destinations to keep proper Up behavior
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
        if (!enabled && navController.currentDestination?.id == R.id.nav_development) {
            navController.navigate(R.id.nav_home)
        }
    }

    private fun applyDarkModePreference() {
        val enabled = prefs.getBoolean(SettingsFragment.PREF_DARK_MODE, false)
        val mode = if (enabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                findNavController(R.id.nav_host_fragment_content_main).navigate(R.id.nav_settings)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }
}
