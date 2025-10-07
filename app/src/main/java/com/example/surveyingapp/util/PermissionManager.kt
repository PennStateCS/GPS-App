package com.example.surveyingapp.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Manages all app permissions in one place
 */
object PermissionManager {

    // Essential permissions needed for core app functionality (location, connectivity, camera for AR, multicast for discovery)
    val ESSENTIAL_PERMISSIONS = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.INTERNET)
        add(Manifest.permission.ACCESS_NETWORK_STATE)
        add(Manifest.permission.ACCESS_WIFI_STATE)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.CHANGE_WIFI_MULTICAST_STATE)
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS) // Android 13+
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }.toTypedArray()

    // Background location (requires special handling)
    val BACKGROUND_LOCATION_PERMISSION = Manifest.permission.ACCESS_BACKGROUND_LOCATION

    /**
     * Check if all essential permissions are granted
     */
    fun hasEssentialPermissions(context: Context): Boolean {
        return ESSENTIAL_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Get list of missing essential permissions
     */
    fun getMissingEssentialPermissions(context: Context): List<String> {
        return ESSENTIAL_PERMISSIONS.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if a specific permission is granted
     */
    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if location permissions are granted
     */
    fun hasLocationPermissions(context: Context): Boolean {
        val fineLocation = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseLocation = hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fineLocation || coarseLocation
    }

    /**
     * Check if background location permission is granted
     */
    fun hasBackgroundLocationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hasPermission(context, BACKGROUND_LOCATION_PERMISSION)
        } else {
            true // Not needed on older versions
        }
    }

    /**
     * Check if notification permission is granted (Android 13+)
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true // Not needed on older versions
        }
    }

    /**
     * Request essential permissions
     */
    fun requestEssentialPermissions(activity: Activity, requestCode: Int) {
        val missingPermissions = getMissingEssentialPermissions(activity)
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, missingPermissions.toTypedArray(), requestCode)
        }
    }

    /**
     * Request background location permission (must be requested separately after foreground permissions)
     */
    fun requestBackgroundLocationPermission(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            hasLocationPermissions(activity) &&
            !hasBackgroundLocationPermission(activity)) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(BACKGROUND_LOCATION_PERMISSION),
                requestCode
            )
        }
    }

    /**
     * Get user-friendly permission descriptions for rationale dialogs
     */
    fun getPermissionDescription(permission: String): String {
        return when (permission) {
            Manifest.permission.ACCESS_FINE_LOCATION -> "Access precise location for accurate surveying measurements"
            Manifest.permission.ACCESS_COARSE_LOCATION -> "Access approximate location for basic positioning"
            Manifest.permission.ACCESS_BACKGROUND_LOCATION -> "Access location in background for continuous tracking"
            Manifest.permission.CAMERA -> "Access camera for AR (Augmented Reality) features"
            Manifest.permission.READ_EXTERNAL_STORAGE -> "Read files for importing coordinate data"
            Manifest.permission.WRITE_EXTERNAL_STORAGE -> "Save files for exporting coordinate data"
            Manifest.permission.POST_NOTIFICATIONS -> "Show notifications for location service status"
            Manifest.permission.INTERNET -> "Access internet for map tiles and network features"
            Manifest.permission.ACCESS_NETWORK_STATE -> "Check network status for connectivity features"
            Manifest.permission.ACCESS_WIFI_STATE -> "Access WiFi information for device discovery"
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE -> "Enable device discovery on local network"
            else -> "Required for app functionality"
        }
    }

    // Request codes for different permission types
    const val REQUEST_CODE_ESSENTIAL = 100
    const val REQUEST_CODE_BACKGROUND_LOCATION = 102
    const val REQUEST_CODE_NOTIFICATION = 104
}
