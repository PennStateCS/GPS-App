package app.surrealar.domain.model

/**
 * Domain model for a captured coordinate point — the app's primary survey record.
 *
 * Position semantics:
 * - latitude/longitude in EPSG:4326 (degrees)
 * - altitude = ellipsoidal height (meters)
 * - altitudeMsl (meters) and geoidSeparationM (meters) are optional extras from GGA
 *
 * **Survey data vs. visual data.** Most fields are *survey data* (the measured position and its
 * quality: lat/lon/alt, [rtkStatus], accuracies, DOPs, corrections, UTM, std-devs) and must be
 * preserved exactly. The model-link and placement fields are *visual data*:
 * - [modelId] links a 3D model (the explicit column; supersedes the legacy `icon = "model:<id>"`
 *   convention, which is still read via [CoordinateModelLink]).
 * - [iconKey] is the built-in/simple icon used when no model is linked.
 * - [modelScale], [modelYawDeg]/[modelPitchDeg]/[modelRollDeg], [modelVerticalOffsetM] and the
 *   `modelOriginOffset*` fields are AR placement *overrides*; they change how a linked model is
 *   drawn, never the coordinate's measured position.
 *
 * Persisted via [app.surrealar.data.repository.mapper] — keep that mapping in sync.
 */
data class Coordinate(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val timestamp: Long,
    val icon: String,
    // Marker color (ARGB). No longer user-selectable — auto-assigned a default (app primary).
    // Retained because map/list/AR marker rendering still reads it; existing rows keep their value.
    val color: Int,
    val provider: String = "fused",
    val rtkStatus: String? = null,
    val satsUsed: Int? = null,
    val satsVisible: Int? = null,                   // Total satellites in view
    val hdop: Double? = null,
    val vDop: Double? = null,                       // Vertical dilution of precision
    val pDop: Double? = null,                       // Position dilution of precision
    val horizontalAccuracyM: Double? = null,
    val verticalAccuracyM: Double? = null,
    val correctionSource: String? = null,
    val correctionAgeS: Double? = null,
    val correctionStationId: String? = null,        // Correction station ID
    val altitudeMsl: Double? = null,
    val geoidSeparationM: Double? = null,
    val speedMps: Double? = null,                   // Speed over ground (m/s)
    val courseDeg: Double? = null,                  // Course over ground (degrees)
    val timestampSource: String? = null,            // Source of timestamp
    val multipathIndex: Double? = null,             // Multipath index
    val crsEpsg: Int? = 4326,
    val easting: Double? = null,
    val northing: Double? = null,
    val utmZone: String? = null,
    val note: String? = null,
    // How the position was acquired (auto-set): internal_gps | external_gnss | model_embedded | map_tap | manual | imported | averaged
    val captureMethod: String? = null,
    val averagedSamples: Int? = null,
    val averageDurationMs: Long? = null,
    val stdLatM: Double? = null,
    val stdLonM: Double? = null,
    val stdAltM: Double? = null,
    val sourceDevice: String? = null,
    val appVersion: String? = null,

    // v10: explicit model association (replaces icon = "model:<id>"). See CoordinateEntity.
    val modelId: String? = null,
    val iconKey: String? = null,
    val renderEnabled: Boolean = true,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,

    // v10: per-coordinate model placement overrides (null = use model/default).
    val modelScale: Double? = null,
    val modelYawDeg: Double? = null,
    val modelPitchDeg: Double? = null,
    val modelRollDeg: Double? = null,
    val modelVerticalOffsetM: Double? = null,
    val modelOriginOffsetXM: Double? = null,
    val modelOriginOffsetYM: Double? = null,
    val modelOriginOffsetZM: Double? = null
)
