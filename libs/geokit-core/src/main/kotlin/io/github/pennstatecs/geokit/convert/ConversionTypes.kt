package io.github.pennstatecs.geokit.convert

import io.github.pennstatecs.geokit.datum.ReferenceFrame
import io.github.pennstatecs.geokit.projection.SpcsZone
import io.github.pennstatecs.geokit.projection.SpcsZones

/**
 * A unit for grid coordinates and heights. Conversions divide or multiply by [metresPerUnit] (no
 * rounding), so a value converted with [fromMetres] is exactly `metres / metresPerUnit`.
 *
 * @property metresPerUnit length of one unit in metres.
 * @property symbol short symbol for display: "m", "ftUS", "ft".
 */
public enum class LengthUnit(public val metresPerUnit: Double, public val symbol: String) {
    /** The metre. */
    METRE(1.0, "m"),

    /** US survey foot, 1200/3937 m: the legal SPCS83 unit in Pennsylvania and many other states. */
    US_SURVEY_FOOT(1200.0 / 3937.0, "ftUS"),

    /** International foot, exactly 0.3048 m. */
    INTERNATIONAL_FOOT(0.3048, "ft");

    /** A length in metres expressed in this unit. */
    public fun fromMetres(metres: Double): Double = metres / metresPerUnit

    /** A length in this unit expressed in metres. */
    public fun toMetres(value: Double): Double = value * metresPerUnit
}

/**
 * The geodetic datum of a latitude/longitude/ellipsoidal height, as selectable for conversion. Each
 * maps to a [ReferenceFrame] ([frame]); conversions between them go through
 * [io.github.pennstatecs.geokit.datum.FrameTransformationRegistry] at the observation epoch.
 * [WGS84_ITRF2014] is the legacy convention "WGS84 (ITRF2014)": coordinates labelled WGS84 that are
 * ITRF2014 coordinates ([ReferenceFrame.WGS84_AS_ITRF2014]). Constant names and EPSG values are stable.
 */
public enum class GeodeticDatum(
    /** EPSG code of the geographic 3D CRS (lat, lon, ellipsoidal height). */
    public val epsg3d: Int,
    /** EPSG code of the geographic 2D CRS (lat, lon). */
    public val epsg2d: Int,
) {
    /** WGS84 as ITRF2014 (EPSG 4979 / 4326). */
    WGS84_ITRF2014(Epsg.WGS84_3D, Epsg.WGS84_2D),

    /** NAD83(2011) (EPSG 6319 / 6318). */
    NAD83_2011(Epsg.NAD83_2011_3D, Epsg.NAD83_2011_2D),

    /** ITRF2020 (EPSG 9989 / 9990), from/to the others via ITRF2014 at the observation epoch. */
    ITRF2020(Epsg.ITRF2020_3D, Epsg.ITRF2020_2D);

    /** The reference frame of this datum's coordinates. */
    public val frame: ReferenceFrame
        get() = when (this) {
            WGS84_ITRF2014 -> ReferenceFrame.WGS84_AS_ITRF2014
            NAD83_2011 -> ReferenceFrame.NAD83_2011
            ITRF2020 -> ReferenceFrame.ITRF2020
        }

    public companion object {
        /**
         * The datum of a geographic CRS EPSG code: 4979/4326 → WGS84, 6319/6318 → NAD83(2011),
         * 9989/9990 → ITRF2020, anything else (including null) → null (unknown).
         */
        public fun fromEpsg(epsg: Int?): GeodeticDatum? = when (epsg) {
            Epsg.WGS84_3D, Epsg.WGS84_2D -> WGS84_ITRF2014
            Epsg.NAD83_2011_3D, Epsg.NAD83_2011_2D -> NAD83_2011
            Epsg.ITRF2020_3D, Epsg.ITRF2020_2D -> ITRF2020
            else -> null
        }
    }
}

/** EPSG codes of the geographic CRSs used for stored positions. */
public object Epsg {
    /** WGS84 geographic 3D (lat, lon, ellipsoidal height). */
    public const val WGS84_3D: Int = 4979

    /** WGS84 geographic 2D. */
    public const val WGS84_2D: Int = 4326

    /** NAD83(2011) geographic 3D. */
    public const val NAD83_2011_3D: Int = 6319

    /** NAD83(2011) geographic 2D. */
    public const val NAD83_2011_2D: Int = 6318

    /** ITRF2020 geographic 3D. */
    public const val ITRF2020_3D: Int = 9989

    /** ITRF2020 geographic 2D. */
    public const val ITRF2020_2D: Int = 9990

    /** True for the NAD83(2011) geographic codes (2D or 3D). */
    public fun isNad83_2011(epsg: Int?): Boolean = epsg == NAD83_2011_3D || epsg == NAD83_2011_2D

    /** True for a 2D geographic code (no height axis). */
    public fun is2d(epsg: Int?): Boolean = epsg == WGS84_2D || epsg == NAD83_2011_2D || epsg == ITRF2020_2D
}

/**
 * A measured position as stored or received, plus what is needed to convert it correctly.
 *
 * @property latDeg latitude in [datum], degrees.
 * @property lonDeg longitude in [datum], degrees.
 * @property ellipsoidalHeightM ellipsoidal height in [datum], metres; null = unknown (0.0 is a real
 *   height). Unknown heights are never replaced by 0 in a result; the datum shift of lat/lon is then
 *   computed at a height of 0 m, which moves lat/lon by far less than a millimetre.
 * @property receiverMslHeightM height above mean sea level as the receiver reported it (its own geoid
 *   model), metres; null = not reported. Used only for [VerticalReference.RECEIVER_MSL], unchanged.
 * @property datum datum of lat/lon/height.
 * @property epochYear observation epoch as a decimal year, used as the coordinate epoch of the datum
 *   transformations (they are time-dependent; see
 *   [io.github.pennstatecs.geokit.datum.Itrf2014ToNad83.decimalYear]).
 */
public data class SourcePosition(
    val latDeg: Double,
    val lonDeg: Double,
    val ellipsoidalHeightM: Double?,
    val receiverMslHeightM: Double?,
    val datum: GeodeticDatum,
    val epochYear: Double,
)

/** How the horizontal position is expressed. */
public sealed interface TargetSystem {
    /** Latitude/longitude in the output datum (no grid coordinates). */
    public data object Geographic : TargetSystem

    /** UTM in the zone containing the longitude (6° zones, no Norway/Svalbard exceptions). */
    public data object UtmAuto : TargetSystem

    /**
     * SPCS83 State Plane on NAD83(2011); the output datum is always NAD83(2011).
     * @property zoneEpsg EPSG code of the zone's NAD83(2011) metre CRS (see [SpcsZones]), e.g. 6562
     *   for Pennsylvania North. Must be a known zone.
     */
    public data class StatePlane(val zoneEpsg: Int) : TargetSystem {
        /** The zone. */
        public val zone: SpcsZone = requireNotNull(SpcsZones.byEpsg(zoneEpsg)) { "Unknown SPCS83 zone EPSG $zoneEpsg" }
    }
}

/** Which height a result carries. */
public enum class VerticalReference {
    /** Ellipsoidal height in the output datum. */
    ELLIPSOIDAL,

    /**
     * Orthometric height from GEOID18: NAD83(2011) ellipsoidal height minus the GEOID18 undulation
     * looked up at the NAD83(2011) position (NAVD88 in CONUS, PRVD02 / VIVD09 in Puerto Rico and the
     * Virgin Islands).
     */
    GEOID18_ORTHOMETRIC,

    /** The receiver-reported MSL height, unchanged (only converted to the output unit). */
    RECEIVER_MSL,
}

/** Why a result has no height. [description] is a short, stable, human-readable phrase. */
public enum class HeightUnavailableReason(public val description: String) {
    /** The source has no ellipsoidal height (needed for ellipsoidal and GEOID18 heights). */
    NO_ELLIPSOIDAL_HEIGHT("no ellipsoidal height"),

    /** The geoid lookup returned no value (outside GEOID18 coverage, or no geoid model available). */
    OUTSIDE_GEOID18_COVERAGE("outside GEOID18 coverage"),

    /** The receiver reported no orthometric (geoid-based, NMEA "MSL") height. */
    NO_RECEIVER_MSL("receiver reported no orthometric height"),
}

/**
 * The coordinate system to express positions in.
 *
 * @property horizontal grid or geographic system.
 * @property datum requested datum for geographic and UTM output; State Plane always uses NAD83(2011)
 *   (see [effectiveDatum]).
 * @property unit unit of grid coordinates and heights.
 * @property vertical which height to report.
 */
public data class OutputSystem(
    val horizontal: TargetSystem = TargetSystem.Geographic,
    val datum: GeodeticDatum = GeodeticDatum.WGS84_ITRF2014,
    val unit: LengthUnit = LengthUnit.METRE,
    val vertical: VerticalReference = VerticalReference.ELLIPSOIDAL,
) {
    /** The datum the output is actually expressed in: NAD83(2011) for State Plane, else [datum]. */
    public val effectiveDatum: GeodeticDatum
        get() = if (horizontal is TargetSystem.StatePlane) GeodeticDatum.NAD83_2011 else datum
}

/**
 * A position expressed in an [OutputSystem].
 *
 * @property system the output system that was requested.
 * @property datum datum actually used ([OutputSystem.effectiveDatum]).
 * @property latDeg latitude in [datum], degrees (filled for grid systems too, for reference).
 * @property lonDeg longitude in [datum], degrees.
 * @property eastingM grid easting, metres; null for [TargetSystem.Geographic].
 * @property northingM grid northing, metres; null for [TargetSystem.Geographic].
 * @property zoneLabel "UTM 18N" or "SPCS 3701"; null for [TargetSystem.Geographic].
 * @property utmZone UTM zone number (1..60) for [TargetSystem.UtmAuto], else null.
 * @property utmHemisphere 'N' or 'S' for [TargetSystem.UtmAuto], else null.
 * @property spcsZone the State Plane zone for [TargetSystem.StatePlane], else null.
 * @property outsideZone State Plane only: the NAD83(2011) position is certainly outside the zone
 *   ([io.github.pennstatecs.geokit.projection.SpcsZoneLocator.check] = OUTSIDE: outside its area-of-use
 *   box, or clearly inside another zone's county-defined extent). False near a zone line, offshore, and
 *   where only the box is known (no claim). Always false for other systems.
 * @property heightM height for [OutputSystem.vertical], metres; null when unavailable (see
 *   [heightUnavailableReason]). Never a substituted 0.
 * @property heightUnavailableReason why [heightM] is null; null when a height is present.
 * @property verticalDatumName for [VerticalReference.GEOID18_ORTHOMETRIC], the orthometric datum
 *   GEOID18 yields at the position ("NAVD88", "PRVD02" or "VIVD09"), named even when the height is
 *   unavailable; null for the other vertical references.
 */
public data class ConvertedCoordinate(
    val system: OutputSystem,
    val datum: GeodeticDatum,
    val latDeg: Double,
    val lonDeg: Double,
    val eastingM: Double?,
    val northingM: Double?,
    val zoneLabel: String?,
    val utmZone: Int?,
    val utmHemisphere: Char?,
    val spcsZone: SpcsZone?,
    val outsideZone: Boolean,
    val heightM: Double?,
    val heightUnavailableReason: HeightUnavailableReason?,
    val verticalDatumName: String?,
) {
    /** Unit of [easting], [northing] and [height] (the requested [OutputSystem.unit]). */
    public val unit: LengthUnit get() = system.unit

    /** Vertical reference of [heightM] (the requested [OutputSystem.vertical]). */
    public val vertical: VerticalReference get() = system.vertical

    /** [eastingM] in [unit]; null for geographic output. */
    public val easting: Double? get() = eastingM?.let { unit.fromMetres(it) }

    /** [northingM] in [unit]; null for geographic output. */
    public val northing: Double? get() = northingM?.let { unit.fromMetres(it) }

    /** [heightM] in [unit]; null when unavailable. */
    public val height: Double? get() = heightM?.let { unit.fromMetres(it) }
}

/**
 * Geoid undulation N (metres, geoid above the ellipsoid) at a NAD83(2011) latitude/longitude in
 * degrees, or null where the model has no value. Wrap a loaded
 * [io.github.pennstatecs.geokit.geoid.Geoid18Grid] with [GeoidLookup.of].
 */
public fun interface GeoidLookup {
    /** Undulation in metres at the position, or null if unavailable. */
    public fun undulationM(latDeg: Double, lonDeg: Double): Double?

    public companion object {
        /** No geoid: GEOID18 heights are always unavailable. */
        public val NONE: GeoidLookup = GeoidLookup { _, _ -> null }

        /** Lookups in a loaded GEOID18 grid. */
        public fun of(grid: io.github.pennstatecs.geokit.geoid.Geoid18Grid): GeoidLookup =
            GeoidLookup { lat, lon -> grid.undulation(lat, lon) }
    }
}
