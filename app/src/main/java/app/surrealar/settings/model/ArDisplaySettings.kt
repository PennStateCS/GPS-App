package app.surrealar.settings.model

/**
 * Persistent user preferences controlling how saved coordinates and 3D models are
 * displayed in AR.
 *
 * These settings define the initial defaults when the AR screen opens. Toolbar
 * controls in the AR view can override the current session temporarily, but this
 * persisted value defines what the session starts with.
 *
 * These settings do NOT affect:
 * - ARCore anchor creation math
 * - Stored coordinate data
 * - GNSS capture quality rules
 */
data class ArDisplaySettings(
    /** Altitude source used when placing AR anchors: "STORED" uses the recorded ellipsoidal
     *  height; "TERRAIN" requests terrain-relative altitude from the Geospatial API. */
    val altitudeMode: String = "STORED",

    /** Index into the distance-filter step list [null, 50m, 100m, 500m].
     *  0 = show all, 1 = 50 m, 2 = 100 m, 3 = 500 m. */
    val distanceFilterIndex: Int = 1,

    /** Whether the debug overlay panel is shown by default when AR opens. */
    val showDebugOverlay: Boolean = false,

    /** Whether coordinate name labels are rendered above each pin in AR. */
    val showLabels: Boolean = true,

    /** Whether off-screen arrow indicators are shown for pins outside the camera view. */
    val showOffscreenArrows: Boolean = true,

    /** Uniform scale applied to all 3D models. Clamped to 0.1–10.0 at runtime. */
    val modelScale: Float = 2.0f,

    /** Whether the AR Debug Tools section is available on the floating toolbar.
     *  When false, point cloud and plane rendering are suppressed entirely. */
    val showArDebugTools: Boolean = false
)
