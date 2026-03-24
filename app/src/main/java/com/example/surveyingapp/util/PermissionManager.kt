package com.example.surveyingapp.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat


object PermissionManager {

    // -------------------------------------------------------------------------
    // Permission groups
    // -------------------------------------------------------------------------

    /**
     * Core location permissions — required for all GPS functionality.
     * Fine location implies coarse on modern Android, but we request both for compatibility.
     */
    val LOCATION_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    /**
     * Camera — required for the AR (Augmented Reality) feature.
     */
    val CAMERA_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA
    )

    /**
     * Notification permission — Android 13+ (API 33) only.
     * On older versions this is auto-granted.
     */
    val NOTIFICATION_PERMISSIONS: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }


    val ESSENTIAL_PERMISSIONS: Array<String> = buildList<String> {
        addAll(LOCATION_PERMISSIONS)
        addAll(CAMERA_PERMISSIONS)
        addAll(NOTIFICATION_PERMISSIONS)
    }.toTypedArray()

    // Background location is a separate flow (post-foreground-location grant)
    // ACCESS_BACKGROUND_LOCATION was added in API 29; the string constant itself is safe on all APIs
    // because it is just a string, but Android Lint flags it — suppress for clarity.
    @Suppress("InlinedApi")
    val BACKGROUND_LOCATION_PERMISSION = Manifest.permission.ACCESS_BACKGROUND_LOCATION

    /**
     * BLUETOOTH_CONNECT is required on Android 12+ (API 31) to read paired device
     * names/addresses. Used only by LogZip diagnostic reporting — NOT an essential
     * permission (app works fully without it). The call site already catches
     * SecurityException gracefully. Request this only from the diagnostics screen if desired.
     */
    @Suppress("InlinedApi", "unused")
    val BLUETOOTH_CONNECT_PERMISSION: String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_CONNECT
        else
            null // Auto-granted / not needed below API 31

    // -------------------------------------------------------------------------
    // Check helpers
    // -------------------------------------------------------------------------

    /** Returns true if ALL essential permissions are granted. */
    @Suppress("unused")
    fun hasEssentialPermissions(context: Context): Boolean =
        ESSENTIAL_PERMISSIONS.all { isGranted(context, it) }

    /** Returns the subset of essential permissions that have NOT been granted. */
    fun getMissingEssentialPermissions(context: Context): List<String> =
        ESSENTIAL_PERMISSIONS.filter { !isGranted(context, it) }

    /** Returns true if the given single permission is granted. */
    @Suppress("unused")
    fun hasPermission(context: Context, permission: String): Boolean =
        isGranted(context, permission)

    /** Returns true if at least one of the location permissions is granted. */
    fun hasLocationPermissions(context: Context): Boolean =
        LOCATION_PERMISSIONS.any { isGranted(context, it) }

    /** Returns true if ACCESS_FINE_LOCATION specifically is granted. */
    @Suppress("unused")
    fun hasFineLocation(context: Context): Boolean =
        isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    /** Returns true if the camera permission is granted. */
    @Suppress("unused")
    fun hasCameraPermission(context: Context): Boolean =
        isGranted(context, Manifest.permission.CAMERA)

    /** Returns true if background location is granted (or not required on this API level). */
    fun hasBackgroundLocationPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isGranted(context, BACKGROUND_LOCATION_PERMISSION)
        } else {
            true // Not required below Android 10
        }

    /** Returns true if notification permission is granted (or not required on this API level). */
    @Suppress("unused")
    fun hasNotificationPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true // Auto-granted below Android 13
        }

    // -------------------------------------------------------------------------
    // Rationale helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true if ANY of the given permissions have been permanently denied
     * (i.e., the user selected "Don't ask again") — in this case we should direct
     * them to Settings rather than showing another request dialog.
     */
    fun isPermanentlyDenied(activity: Activity, permissions: List<String>): Boolean =
        permissions.any { permission ->
            !isGranted(activity, permission) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }

    /**
     * Returns true if at least one of the given permissions should show a rationale
     * (user denied once but did not select "Don't ask again").
     */
    @Suppress("unused")
    fun shouldShowRationale(activity: Activity, permissions: List<String>): Boolean =
        permissions.any { permission ->
            !isGranted(activity, permission) &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }

    // -------------------------------------------------------------------------
    // Request helpers (legacy ActivityCompat path — prefer Activity Result API)
    // -------------------------------------------------------------------------

    @Suppress("unused")
    fun requestEssentialPermissions(activity: Activity, requestCode: Int) {
        val missing = getMissingEssentialPermissions(activity)
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, missing.toTypedArray(), requestCode)
        }
    }

    @Suppress("unused")
    fun requestBackgroundLocationPermission(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            hasLocationPermissions(activity) &&
            !hasBackgroundLocationPermission(activity)
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(BACKGROUND_LOCATION_PERMISSION),
                requestCode
            )
        }
    }

    // -------------------------------------------------------------------------
    // Human-readable descriptions (for rationale dialogs)
    // -------------------------------------------------------------------------

    fun getPermissionDescription(permission: String): String = when (permission) {
        Manifest.permission.ACCESS_FINE_LOCATION ->
            "Required for accurate GPS positioning and surveying measurements"
        Manifest.permission.ACCESS_COARSE_LOCATION ->
            "Required for approximate positioning used as a fallback"
        Manifest.permission.ACCESS_BACKGROUND_LOCATION ->
            "Allows continuous location tracking when the app is in the background"
        Manifest.permission.CAMERA ->
            "Required for Augmented Reality (AR) features"
        Manifest.permission.POST_NOTIFICATIONS ->
            "Allows the app to show status notifications for the location service"
        "android.permission.BLUETOOTH_CONNECT" ->
            "Allows reading paired Bluetooth device info in diagnostic reports"
        else -> "Required for app functionality"
    }

    fun getPermissionDisplayName(permission: String): String = when (permission) {
        Manifest.permission.ACCESS_FINE_LOCATION -> "Precise Location"
        Manifest.permission.ACCESS_COARSE_LOCATION -> "Approximate Location"
        Manifest.permission.ACCESS_BACKGROUND_LOCATION -> "Background Location"
        Manifest.permission.CAMERA -> "Camera"
        Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
        "android.permission.BLUETOOTH_CONNECT" -> "Nearby Devices (Bluetooth)"
        else -> permission.substringAfterLast('.')
            .replace('_', ' ')
            .lowercase()
            .replaceFirstChar { it.uppercase() }
    }

    // -------------------------------------------------------------------------
    // Request codes (kept for backward compat; prefer Activity Result API)
    // -------------------------------------------------------------------------
    @Suppress("unused") const val REQUEST_CODE_ESSENTIAL = 100
    @Suppress("unused") const val REQUEST_CODE_BACKGROUND_LOCATION = 102
    @Suppress("unused") const val REQUEST_CODE_NOTIFICATION = 104

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
