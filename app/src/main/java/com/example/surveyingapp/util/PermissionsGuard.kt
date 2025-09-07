package com.example.surveyingapp.util

import android.Manifest
import android.os.Build

object PermissionsGuard {
    val locationPerms: Array<String> =
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    val btPerms: Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            emptyArray()
        }

    val notifyPerms: Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else emptyArray()

    /** All runtime perms you may need for internal + RS2+ + foreground notifications. */
    val allRuntimePerms: Array<String> =
        (locationPerms + btPerms + notifyPerms)
            .distinct()
            .toTypedArray()
}
