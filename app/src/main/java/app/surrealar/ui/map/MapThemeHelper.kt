package app.surrealar.ui.map

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import app.surrealar.R
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.MapStyleOptions

object MapThemeHelper {

    private const val TAG = "MapThemeHelper"

    /**
     * Set to true to skip custom JSON styling entirely.
     * Useful when diagnosing a blank or mis-styled map — set this, rebuild, and compare.
     * Must be false for production.
     */
    private const val DISABLE_CUSTOM_MAP_STYLE_FOR_DEBUG = false

    /**
     * Applies a dark map style when the system is in night mode and the map type supports
     * custom styling (Normal and Terrain). Satellite and Hybrid imagery is never styled.
     */
    fun applyTheme(context: Context, map: GoogleMap, mapType: Int = GoogleMap.MAP_TYPE_NORMAL) {
        val isNight = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val supportsStyle = mapType != GoogleMap.MAP_TYPE_SATELLITE &&
                mapType != GoogleMap.MAP_TYPE_HYBRID

        if (isNight && supportsStyle && !DISABLE_CUSTOM_MAP_STYLE_FOR_DEBUG) {
            runCatching {
                map.setMapStyle(MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_dark))
            }.onSuccess {
                Log.d(TAG, "Dark map style applied (mapType=$mapType)")
            }.onFailure {
                Log.w(TAG, "Failed to apply dark map style: ${it.message}")
                // Clear any partially-applied style so the map falls back to default rendering
                runCatching { map.setMapStyle(null) }
            }
        } else {
            val reason = when {
                DISABLE_CUSTOM_MAP_STYLE_FOR_DEBUG -> "debug bypass"
                !isNight -> "light mode"
                else -> "satellite/hybrid type"
            }
            Log.d(TAG, "Applying default map style ($reason, mapType=$mapType)")
            map.setMapStyle(null)
        }
    }
}
