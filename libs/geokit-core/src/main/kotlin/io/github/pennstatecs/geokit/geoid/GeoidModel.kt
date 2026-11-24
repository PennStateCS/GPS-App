package io.github.pennstatecs.geokit.geoid

/**
 * A geoid model: undulation N (metres) such that orthometric height H = h − N, where h is the
 * ellipsoidal height in the model's [GeoidModelInfo.horizontalFrame].
 *
 * Implementations never substitute another model: a lookup either returns N from *this* model
 * ([GeoidResult.Value.model] = [GeoidModelInfo.id]) or a typed [GeoidResult.Unavailable] reason. Picking a
 * fallback (e.g. EGM96 when an EGM2008 tile is missing) is the caller's explicit decision, and the
 * resulting height must then be labelled with the model that actually produced it.
 *
 * Implementations are thread-safe. The first lookup may block on I/O (loading the bundled data), so
 * call from a background thread.
 */
public interface GeoidModel {

    /** Identification, labels, frames, resolution and provenance. */
    public val info: GeoidModelInfo

    /** Current data state (does not trigger loading). */
    public val availability: GeoidAvailability

    /**
     * True if ([latDeg], [lonDeg]) lies in the area the model is defined for (GEOID18: CONUS and PR/VI;
     * the global models: everywhere). It says nothing about whether a tile is installed — a covered point
     * can still return [GeoidUnavailableReason.TILE_NOT_AVAILABLE]. Never loads data.
     */
    public fun covers(latDeg: Double, lonDeg: Double): Boolean

    /** Undulation N at ([latDeg], [lonDeg]) in degrees, in the model's horizontal frame. */
    public fun undulation(latDeg: Double, lonDeg: Double): GeoidResult

    /**
     * Name of the vertical datum an orthometric height from this model belongs to, for labels:
     * GEOID18 → "NAVD88" / "PRVD02" / "VIVD09" (by location); EGM2008 → "EGM2008"; EGM96 → "EGM96".
     * A global model's heights are never NAVD88.
     */
    public fun verticalDatumName(latDeg: Double, lonDeg: Double): String
}

/**
 * Stable, persistable model identifier. [token] is what gets stored (database, backups, CSV): it never
 * changes spelling. Use [fromToken] to read it back.
 */
public enum class GeoidModelId(public val token: String) {
    /** NGS GEOID18 hybrid geoid (NAVD88 / PRVD02 / VIVD09), on NAD83(2011). */
    GEOID18("GEOID18"),

    /** NGA EGM2008 (gravimetric, tide-free), on WGS84. Preferred global model. */
    EGM2008("EGM2008"),

    /** NGA EGM96, on WGS84. Coarse fallback only; always labelled as EGM96. */
    EGM96("EGM96");

    public companion object {
        /** The id stored as [token], or null for an unknown token. */
        public fun fromToken(token: String?): GeoidModelId? = entries.firstOrNull { it.token == token }
    }
}

/** How N is interpolated between grid nodes. */
public enum class GeoidInterpolation {
    /** Bilinear over the four surrounding nodes (as NGS's tools and PROJ `vgridshift`). */
    BILINEAR,
}

/** Data state of a model. */
public enum class GeoidAvailability {
    /** Data not loaded yet; the first lookup loads it. */
    NOT_LOADED,

    /** Loaded; every covered point has data. */
    AVAILABLE,

    /** Loaded; only some tiles are present — other covered points return TILE_NOT_AVAILABLE. */
    PARTIAL,

    /** Loading or verifying the data failed; lookups return LOAD_FAILED. */
    FAILED,
}

/** Why a lookup produced no undulation. */
public enum class GeoidUnavailableReason {
    /** Latitude/longitude not finite or latitude outside ±90°. */
    INVALID_POSITION,

    /** Outside the model's area of definition (e.g. GEOID18 in Alaska or Europe). */
    OUTSIDE_COVERAGE,

    /** Inside the model's area, but the tile holding the point is neither bundled nor installed. */
    TILE_NOT_AVAILABLE,

    /** The model data (or the tile) could not be loaded, verified or decoded. */
    LOAD_FAILED,
}

/** Result of a geoid lookup; [model] is always the model that was asked (no silent substitution). */
public sealed interface GeoidResult {
    public val model: GeoidModelId

    /**
     * N = [undulationM] metres from [model]; [verticalDatum] names the datum of H = h − N at this point
     * (see [GeoidModel.verticalDatumName]).
     */
    public data class Value(
        public val undulationM: Double,
        override val model: GeoidModelId,
        public val verticalDatum: String,
    ) : GeoidResult {
        /** Orthometric height H = [ellipsoidalHeightM] − N. */
        public fun orthometricHeightM(ellipsoidalHeightM: Double): Double = ellipsoidalHeightM - undulationM
    }

    /** No N from [model]: [reason], with a human-readable [detail] for logs. */
    public data class Unavailable(
        public val reason: GeoidUnavailableReason,
        override val model: GeoidModelId,
        public val detail: String,
    ) : GeoidResult
}

/**
 * Where a model's data came from.
 *
 * @property sourceUrl the original distribution (e.g. the PROJ-data CDN file).
 * @property sourceFile file name of the original grid(s).
 * @property sourceSha256 SHA-256 of the original grid file, when known.
 * @property licence licence/terms as stated by the distributor.
 * @property bundledSha256 SHA-256 of each packed resource bundled in this library (resource path → hex).
 */
public data class GeoidProvenance(
    public val sourceUrl: String,
    public val sourceFile: String,
    public val sourceSha256: String?,
    public val licence: String,
    public val bundledSha256: Map<String, String>,
)

/**
 * Metadata of a [GeoidModel].
 *
 * @property id stable persistable id ([GeoidModelId.token]).
 * @property dataVersion version of the data and packing (stored next to heights for provenance).
 * @property label display label: "GEOID18", "EGM2008", "EGM96". Heights read "H (label)".
 * @property horizontalFrame the frame the grid expects positions in: "NAD83(2011)" for GEOID18, "WGS84"
 *   for the EGM models (look the undulation up at a position in that frame; for EGM2008/EGM96 any
 *   modern WGS84/ITRF realisation is fine — a metre-level frame difference changes N by well under 1 mm).
 * @property verticalDatum the vertical datum(s) of H, e.g. "NAVD88 / PRVD02 / VIVD09" or "EGM2008".
 * @property verticalCrsEpsg EPSG code of the vertical CRS when it is a single one (EGM2008 height 3855,
 *   EGM96 height 5773); null for GEOID18, whose datum depends on the location.
 * @property resolutionArcMinutes grid spacing.
 * @property coverage human-readable area of definition and, for tiled models, which tiles are bundled.
 * @property isFallback true for a model used only as an explicitly labelled fallback (EGM96).
 */
public data class GeoidModelInfo(
    public val id: GeoidModelId,
    public val dataVersion: String,
    public val label: String,
    public val horizontalFrame: String,
    public val verticalDatum: String,
    public val verticalCrsEpsg: Int?,
    public val resolutionArcMinutes: Double,
    public val interpolation: GeoidInterpolation,
    public val coverage: String,
    public val provenance: GeoidProvenance,
    public val isFallback: Boolean,
)

/** Thread-safe lazily loaded value that remembers a load failure. */
internal class LazyLoad<T : Any>(private val loader: () -> T) {
    @Volatile private var value: T? = null
    @Volatile private var failure: Throwable? = null

    val isLoaded: Boolean get() = value != null
    val isFailed: Boolean get() = failure != null

    fun preset(v: T): LazyLoad<T> { value = v; return this }

    /** The value, or the (remembered) failure. */
    fun get(): Result<T> {
        value?.let { return Result.success(it) }
        failure?.let { return Result.failure(it) }
        synchronized(this) {
            value?.let { return Result.success(it) }
            failure?.let { return Result.failure(it) }
            return try {
                Result.success(loader().also { value = it })
            } catch (e: Exception) {
                failure = e
                Result.failure(e)
            }
        }
    }
}

internal fun isValidPosition(latDeg: Double, lonDeg: Double): Boolean =
    latDeg.isFinite() && lonDeg.isFinite() && latDeg in -90.0..90.0
