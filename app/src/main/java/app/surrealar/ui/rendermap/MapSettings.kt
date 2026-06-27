package app.surrealar.ui.rendermap

import com.google.android.gms.maps.GoogleMap

/**
 * Durable map *defaults* — applied when the map opens in a fresh app session. These seed the
 * session UI state ([MapUiState]); once the user changes a control in-session, the session state
 * preserves that choice while navigating away and back. Never holds transient state (selected point,
 * camera, open panels). Enum-backed fields persist via stable tokens (see [MapTypeTokens] and each
 * enum's `prefKey`), never `enum.name`.
 */
data class MapSettings(
    val defaultMapType: Int = GoogleMap.MAP_TYPE_NORMAL,
    val defaultGridMode: MapGridMode = MapGridMode.OFF,
    val defaultPointLabelMode: PointLabelMode = PointLabelMode.OFF,
    val showMyLocationByDefault: Boolean = true,
    val keepMapToolsOpenByDefault: Boolean = false,
    val mapPointsDrawerExpandedByDefault: Boolean = true,
)

/** Stable string tokens for the Google map type (never persist the raw int or `MAP_TYPE_NONE`). */
object MapTypeTokens {
    const val NORMAL = "normal"
    const val SATELLITE = "satellite"
    const val HYBRID = "hybrid"
    const val TERRAIN = "terrain"

    fun toToken(mapType: Int): String = when (mapType) {
        GoogleMap.MAP_TYPE_SATELLITE -> SATELLITE
        GoogleMap.MAP_TYPE_HYBRID -> HYBRID
        GoogleMap.MAP_TYPE_TERRAIN -> TERRAIN
        else -> NORMAL
    }

    /** Resolves a token to a Google map type; unknown/null → NORMAL (never NONE). */
    fun fromToken(token: String?): Int = when (token?.lowercase()) {
        SATELLITE -> GoogleMap.MAP_TYPE_SATELLITE
        HYBRID -> GoogleMap.MAP_TYPE_HYBRID
        TERRAIN -> GoogleMap.MAP_TYPE_TERRAIN
        else -> GoogleMap.MAP_TYPE_NORMAL
    }

    fun label(mapType: Int): String = when (mapType) {
        GoogleMap.MAP_TYPE_SATELLITE -> "Satellite"
        GoogleMap.MAP_TYPE_HYBRID -> "Hybrid"
        GoogleMap.MAP_TYPE_TERRAIN -> "Terrain"
        else -> "Normal"
    }
}
