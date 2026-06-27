package app.surrealar.util

import android.Manifest
import android.os.Build

/**
 * Lightweight provider of runtime-permission arrays (location, notifications) for callers that just
 * need the list to request. [PermissionManager] is the fuller helper with check/request logic; keep
 * the permission sets here consistent with it.
 */
object PermissionsGuard {
    // Location permission required for accessing device location (fine accuracy)
    val locationPerms: Array<String> =
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    // Notification permission required for posting notifications (Android 13+)
    val notifyPerms: Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else emptyArray()

    /**
     * All runtime permissions needed for internal location and notifications.
     * Combines locationPerms and notifyPerms, removing duplicates.
     * Use this array when requesting all relevant permissions at once.
     */
    val allRuntimePerms: Array<String> =
        (locationPerms + notifyPerms)
            .distinct()
            .toTypedArray()
}
