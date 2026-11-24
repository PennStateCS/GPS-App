package io.github.pennstatecs.geokit.crs

import io.github.pennstatecs.geokit.datum.ReferenceFrame
import io.github.pennstatecs.geokit.projection.ProjectionMethod
import io.github.pennstatecs.geokit.projection.SpcsProjection
import io.github.pennstatecs.geokit.projection.SpcsZone

/**
 * The catalogs of the [CrsRegistry]. [id] is a stable token (never renamed).
 * @property id stable token. @property title display name.
 */
public enum class CrsCatalogId(public val id: String, public val title: String) {
    /** One geographic (latitude/longitude) CRS per [ReferenceFrame]. */
    GEOGRAPHIC("geographic", "Geographic"),

    /** WGS 84 / UTM zones 1-60 north and south (EPSG 32601-32660, 32701-32760). */
    UTM("utm", "UTM"),

    /** The 118 official SPCS83 zones on NAD83(2011) (metre CRSs, EPSG codes = persisted settings values). */
    SPCS83("spcs83", "SPCS83"),

    /** State Plane Coordinate System of 2022 (NATRF2022 etc.). No zones are bundled yet: unsupported. */
    SPCS2022("spcs2022", "SPCS2022"),
}

/** Whether a CRS is geographic (degrees) or projected (grid metres). */
public enum class CrsKind { GEOGRAPHIC, PROJECTED }

/** Native axis unit of a CRS definition (display units such as US survey feet are chosen by the caller). */
public enum class CrsUnit(public val token: String) {
    /** Degrees of latitude/longitude. */
    DEGREE("degree"),

    /** Metres of easting/northing. */
    METRE("metre"),
}

/**
 * Area of use as a longitude/latitude box in degrees (EPSG convention). [east] < [west] means the box
 * crosses the antimeridian (e.g. Alaska zone 10, NAD83(2011)).
 */
public data class AreaOfUse(val west: Double, val south: Double, val east: Double, val north: Double) {
    /** True if the point (degrees) lies inside the box (edges included, antimeridian-aware). */
    public fun contains(latDeg: Double, lonDeg: Double): Boolean {
        if (!latDeg.isFinite() || !lonDeg.isFinite()) return false
        if (latDeg < south || latDeg > north) return false
        return if (east >= west) lonDeg in west..east else lonDeg >= west || lonDeg <= east
    }

    public companion object {
        /** The whole Earth. */
        public val WORLD: AreaOfUse = AreaOfUse(-180.0, -90.0, 180.0, 90.0)
    }
}

/**
 * One coordinate reference system in a [CrsRegistry] catalog: identity, frame, projection method and
 * parameters, unit, area of use and provenance.
 *
 * Only [operational] definitions may be used for coordinates: they are verified against reference
 * vectors (PROJ / NGS) by tests in this module. A non-operational definition (e.g. an SPCS2022 catalog
 * entry without verified test vectors) is listed for information only; its [projection] is null and
 * [forward] throws. The registry never returns a non-operational definition as
 * [CrsLookup.Found].
 *
 * @property id stable, persistable identifier: `EPSG:<code>` for UTM and SPCS83 (the SPCS83 EPSG codes
 *   are the values the app persists in settings, e.g. `EPSG:6562`), `geographic:<frame id>` for
 *   geographic CRSs (WGS 84 and "WGS84 (ITRF2014)" share EPSG 4326, so the frame token is the key),
 *   `EPSG:<code>` or `spcs2022:<zone code>` for SPCS2022 entries. Never renamed or reused.
 * @property epsg EPSG code (projected: the CRS; geographic: the frame's 2D CRS), null if none.
 * @property name display name, e.g. "Pennsylvania North", "WGS 84 / UTM zone 18N", "NAD83(2011)".
 * @property zoneCode zone code: NGS code for State Plane ("3701"), "18N" for UTM, null for geographic.
 * @property catalog the catalog the definition belongs to.
 * @property kind geographic or projected.
 * @property frame reference frame of the coordinates (SPCS83: NAD83(2011); UTM: WGS84 (ITRF2014)).
 * @property method projection method, null for geographic CRSs.
 * @property parameters defining parameters keyed by [ProjectionMethod.parameterNames] (empty for geographic).
 * @property unit native axis unit.
 * @property areaOfUse area of use (EPSG).
 * @property dataset where the definition comes from, e.g. "EPSG v12.029 via PROJ 9.8.1 (pyproj 3.8.0)".
 * @property operational true only for verified definitions that may be used.
 * @property statusNote why a definition is not operational (or a caveat), null if none.
 * @property spcsZone the underlying [SpcsZone] of an SPCS83 definition, null otherwise.
 */
public class CrsDefinition internal constructor(
    public val id: String,
    public val epsg: Int?,
    public val name: String,
    public val zoneCode: String?,
    public val catalog: CrsCatalogId,
    public val kind: CrsKind,
    public val frame: ReferenceFrame,
    public val method: ProjectionMethod?,
    public val parameters: Map<String, Double>,
    public val unit: CrsUnit,
    public val areaOfUse: AreaOfUse,
    public val dataset: String,
    public val operational: Boolean,
    public val statusNote: String?,
    public val spcsZone: SpcsZone? = null,
    projectionFactory: (() -> SpcsProjection)? = null,
) {
    /** The same entry, listed but not operational (no projection), with [note] as the reason. */
    internal fun withoutOperation(note: String): CrsDefinition = CrsDefinition(
        id, epsg, name, zoneCode, catalog, kind, frame, method, parameters, unit, areaOfUse, dataset,
        operational = false, statusNote = note, spcsZone = spcsZone, projectionFactory = null,
    )

    private val projectionLazy: Lazy<SpcsProjection>? =
        if (operational && projectionFactory != null) lazy(projectionFactory) else null

    /**
     * The projection object (frame latitude/longitude in degrees → easting/northing metres), created on
     * first use. For SPCS83 it is the zone's own [SpcsZone.projection]; for UTM the very object
     * [io.github.pennstatecs.geokit.projection.Utm] uses. Null for geographic and non-operational definitions.
     */
    public val projection: SpcsProjection? get() = projectionLazy?.value

    /** "Pennsylvania North (3701)" for State Plane, the [name] otherwise. */
    public val label: String
        get() = if (zoneCode != null && (catalog == CrsCatalogId.SPCS83 || catalog == CrsCatalogId.SPCS2022)) "$name ($zoneCode)" else name

    /**
     * Latitude/longitude (degrees, in [frame]) → (easting, northing) metres.
     * @throws IllegalStateException for geographic or non-operational definitions.
     */
    public fun forward(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val p = checkNotNull(projection) {
            if (!operational) "$id is not operational: ${statusNote ?: "unverified"}" else "$id is geographic: no projection"
        }
        return p.forward(latDeg, lonDeg)
    }

    /** Lower-case text the search matches against. */
    internal val searchText: String by lazy {
        listOfNotNull(id, epsg?.toString(), name, zoneCode, catalog.title, frame.displayName).joinToString(" ").lowercase()
    }

    override fun toString(): String = "CrsDefinition($id, $label, ${catalog.id}${if (operational) "" else ", non-operational"})"
}

/** Result of a [CrsRegistry] lookup. */
public sealed interface CrsLookup {
    /** The identifier that was looked up (as given). */
    public val query: String

    /** An operational definition. */
    public data class Found(override val query: String, val definition: CrsDefinition) : CrsLookup

    /**
     * The identifier belongs to a catalog or entry that is not usable: the SPCS2022 catalog (unsupported
     * until verified definitions exist) or a non-operational entry.
     * @property catalog the catalog. @property explanation why it cannot be used.
     * @property definition the non-operational entry, if one exists (information only).
     */
    public data class Unsupported(
        override val query: String,
        val catalog: CrsCatalogId,
        val explanation: String,
        val definition: CrsDefinition?,
    ) : CrsLookup

    /** No catalog knows the identifier (also for malformed identifiers). */
    public data class NotFound(override val query: String) : CrsLookup

    /** The definition if [Found], else null. */
    public val definitionOrNull: CrsDefinition? get() = (this as? Found)?.definition
}
