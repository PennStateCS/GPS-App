package com.google.android.filament

/**
 * Reaches Filament's per-channel depth clear from app code.
 *
 * [View.setChannelDepthClearEnabled] is public in Filament's C++ API but package-private in the
 * Java bindings (filament-android 1.68.3, View.java), so it cannot be called from
 * `app.surrealar.*` directly. Declaring this helper in Filament's own package is the compile-time
 * checked alternative to reflection; app classes and the library's classes share a single
 * classloader inside the APK, so they are in the same runtime package and the access is legal.
 *
 * Used by the model viewer to draw its overlay geometry (axis gizmo, origin marker, bounding box,
 * grid) on top of the model: the overlays live in their own render channel, and clearing depth
 * before that channel means nothing in the model can occlude them while they still depth-sort
 * correctly against each other.
 */

object FilamentChannelDepth {

    /** Clears the depth buffer before [channel] (0..7) is rendered in [view]. */
    fun setChannelDepthClearEnabled(view: View, channel: Int, enabled: Boolean) {
        view.setChannelDepthClearEnabled(channel, enabled)
    }
}
