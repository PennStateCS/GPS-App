package com.example.surveyingapp.ui.map

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.example.surveyingapp.R
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.MapStyleOptions

object MapThemeHelper {

    private const val TAG = "MapThemeHelper"

    /**
     * Applies a dark map style when the system is in night mode and the map type supports
     * custom styling (Normal and Terrain). Satellite and Hybrid imagery is never styled.
     */
    fun applyTheme(context: Context, map: GoogleMap, mapType: Int = GoogleMap.MAP_TYPE_NORMAL) {
        val isNight = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val supportsStyle = mapType != GoogleMap.MAP_TYPE_SATELLITE &&
                mapType != GoogleMap.MAP_TYPE_HYBRID

        if (isNight && supportsStyle) {
            runCatching {
                map.setMapStyle(MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_dark))
            }.onFailure {
                Log.w(TAG, "Failed to apply dark map style: ${it.message}")
            }
        } else {
            map.setMapStyle(null)
        }
    }
}
