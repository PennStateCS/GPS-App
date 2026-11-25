package io.github.pennstatecs.geokit.datum

import io.github.pennstatecs.geokit.ellipsoid.Ellipsoid

/** Whether a frame follows the whole Earth or one tectonic plate. */
public enum class FrameKind {
    /** Global frame (ITRF, WGS 84): stations on a plate move in it by plate motion (cm/yr). */
    GLOBAL,

    /** Fixed to a tectonic plate (NAD83(2011), NATRF2022): coordinates on the plate are nearly static. */
    PLATE_FIXED,
}

/**
 * A terrestrial reference frame (datum realisation) that latitude/longitude/ellipsoidal height can be
 * expressed in.
 *
 * [id] is a stable, persistable token: it may be stored in databases, backups and settings and is
 * **never renamed or reused**. Look frames up with [fromId]. Unknown ids (e.g. from a newer app version)
 * give null, never a guessed frame.
 *
 * WGS 84 is deliberately **not** interchangeable with an ITRF here. WGS 84 itself is an ensemble of
 * realisations (G730 … G2296) with 2 m accuracy as a whole; the recent realisations G1762 (≈ ITRF2008),
 * G2139 (≈ ITRF2014) and G2296 (≈ ITRF2020) are aligned with ITRF only at the centimetre level, and
 * EPSG/PROJ relate them to ITRF by null (unverified) transformations. Those frames therefore exist for
 * labelling and get no transformation (see [FrameTransformationRegistry]).
 *
 * The legacy app convention "WGS84 (ITRF2014)" — receiver/ARCore "WGS84" coordinates taken to be
 * ITRF2014 coordinates — is the explicit frame [WGS84_AS_ITRF2014]. Its numbers *are* ITRF2014 numbers
 * ([coordinatesFrame] = [ITRF2014]); it only keeps the WGS 84 EPSG codes (4979/4326) the app stores.
 *
 * Reference epochs: for ITRF and WGS 84 realisations they are the EPSG frame reference epochs; EPSG
 * records none for NAD83(2011) and the 2022 frames, so the NGS-defined epochs (2010.0, 2020.0) are given.
 * A frame's reference epoch is metadata only; transformations use the coordinate epoch
 * ([CoordinateEpoch]) of the position.
 *
 * @property id stable persistable token (lower-case).
 * @property displayName short human-readable name.
 * @property ellipsoid the frame's ellipsoid.
 * @property epsgGeocentric EPSG code of the geocentric CRS, null if none.
 * @property epsg3d EPSG code of the geographic 3D CRS (lat, lon, h), null if none.
 * @property epsg2d EPSG code of the geographic 2D CRS (lat, lon), null if none.
 * @property referenceEpoch frame reference epoch (decimal year), null where not applicable (ensemble).
 * @property kind global or plate-fixed.
 */
public enum class ReferenceFrame(
    public val id: String,
    public val displayName: String,
    public val ellipsoid: Ellipsoid,
    public val epsgGeocentric: Int?,
    public val epsg3d: Int?,
    public val epsg2d: Int?,
    public val referenceEpoch: Double?,
    public val kind: FrameKind,
) {
    /** ITRF2014 (EPSG 7789 / 7912 / 9000), reference epoch 2010.0. */
    ITRF2014("itrf2014", "ITRF2014", Ellipsoid.GRS80, 7789, 7912, 9000, 2010.0, FrameKind.GLOBAL),

    /** ITRF2020 (EPSG 9988 / 9989 / 9990), reference epoch 2015.0. */
    ITRF2020("itrf2020", "ITRF2020", Ellipsoid.GRS80, 9988, 9989, 9990, 2015.0, FrameKind.GLOBAL),

    /**
     * The legacy app convention "WGS84 (ITRF2014)": coordinates labelled WGS 84 (stored as EPSG
     * 4979 / 4326) that are ITRF2014 coordinates by definition. Transforms exactly like [ITRF2014].
     */
    WGS84_AS_ITRF2014("wgs84-as-itrf2014", "WGS84 (ITRF2014)", Ellipsoid.WGS84, 4978, 4979, 4326, null, FrameKind.GLOBAL),

    /** WGS 84 ensemble, realisation unspecified (EPSG 4978 / 4979 / 4326; ensemble accuracy 2 m). */
    WGS84("wgs84", "WGS 84", Ellipsoid.WGS84, 4978, 4979, 4326, null, FrameKind.GLOBAL),

    /** WGS 84 (G1762), aligned with ITRF2008 at the cm level (EPSG 7664 / 7665 / 9057). */
    WGS84_G1762("wgs84-g1762", "WGS 84 (G1762)", Ellipsoid.WGS84, 7664, 7665, 9057, 2005.0, FrameKind.GLOBAL),

    /** WGS 84 (G2139), aligned with ITRF2014 at the cm level (EPSG 9753 / 9754 / 9755). */
    WGS84_G2139("wgs84-g2139", "WGS 84 (G2139)", Ellipsoid.WGS84, 9753, 9754, 9755, 2016.0, FrameKind.GLOBAL),

    /** WGS 84 (G2296), aligned with ITRF2020 at the cm level (EPSG 10604 / 10605 / 10606). */
    WGS84_G2296("wgs84-g2296", "WGS 84 (G2296)", Ellipsoid.WGS84, 10604, 10605, 10606, 2024.0, FrameKind.GLOBAL),

    /** NAD83(2011) (EPSG 6317 / 6319 / 6318), North American plate, epoch 2010.0. */
    NAD83_2011("nad83-2011", "NAD83(2011)", Ellipsoid.GRS80, 6317, 6319, 6318, 2010.0, FrameKind.PLATE_FIXED),

    /**
     * NATRF2022 (EPSG 10966 / 10967 / 10968), North American plate, epoch 2020.0. Defined by NGS (beta
     * EPP2022, see [PlateFrame2022]). Its ITRF2020 edge is verified against NGS-computed coordinates but is a
     * beta product: it exists only in the opt-in [FrameTransformationRegistry.NSRS2022_BETA], never in
     * [FrameTransformationRegistry.DEFAULT].
     */
    NATRF2022("natrf2022", "NATRF2022", Ellipsoid.GRS80, 10966, 10967, 10968, 2020.0, FrameKind.PLATE_FIXED),

    /**
     * PATRF2022, Pacific plate, epoch 2020.0 (Phase 4 WP X). EPSG v12.029 has no CRS for it (codes null).
     * Same status rules as [NATRF2022]: beta edge in [FrameTransformationRegistry.NSRS2022_BETA] only.
     */
    PATRF2022("patrf2022", "PATRF2022", Ellipsoid.GRS80, null, null, null, 2020.0, FrameKind.PLATE_FIXED),

    /** CATRF2022, Caribbean plate, epoch 2020.0 (Phase 4 WP X); no EPSG codes; as [PATRF2022]. */
    CATRF2022("catrf2022", "CATRF2022", Ellipsoid.GRS80, null, null, null, 2020.0, FrameKind.PLATE_FIXED),

    /** MATRF2022, Mariana plate, epoch 2020.0 (Phase 4 WP X); no EPSG codes; as [PATRF2022]. */
    MATRF2022("matrf2022", "MATRF2022", Ellipsoid.GRS80, null, null, null, 2020.0, FrameKind.PLATE_FIXED);

    /**
     * The frame whose coordinates this frame's numbers are: [ITRF2014] for [WGS84_AS_ITRF2014] (a
     * labelling alias), the frame itself otherwise.
     */
    public val coordinatesFrame: ReferenceFrame
        get() = if (this == WGS84_AS_ITRF2014) ITRF2014 else this

    /**
     * The tectonic plate a [FrameKind.PLATE_FIXED] frame follows (null for global frames), so callers can
     * warn when a point lies off that plate (see [TectonicPlate.areaNote]). Transformations themselves
     * never refuse a point for its location.
     */
    public val plate: TectonicPlate?
        get() = when (this) {
            NAD83_2011, NATRF2022 -> TectonicPlate.NORTH_AMERICA
            PATRF2022 -> TectonicPlate.PACIFIC
            CATRF2022 -> TectonicPlate.CARIBBEAN
            MATRF2022 -> TectonicPlate.MARIANA
            else -> null
        }

    public companion object {
        /** The frame with this stable [id], or null for an unknown (or null) id. */
        public fun fromId(id: String?): ReferenceFrame? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A coordinate epoch: the decimal year at which coordinates in a [FrameKind.GLOBAL] frame are
 * expressed (points move in such frames). It is distinct from an observation timestamp (an instant;
 * convert with [fromEpochMillis]) and from a frame's [ReferenceFrame.referenceEpoch].
 *
 * @property decimalYear e.g. 2026.886; must be finite.
 */
@JvmInline
public value class CoordinateEpoch(public val decimalYear: Double) {
    init {
        require(decimalYear.isFinite()) { "Coordinate epoch must be finite, was $decimalYear" }
    }

    override fun toString(): String = "epoch $decimalYear"

    public companion object {
        /** The epoch of an instant, as [Itrf2014ToNad83.decimalYear] computes it. */
        public fun fromEpochMillis(epochMillis: Long): CoordinateEpoch =
            CoordinateEpoch(Itrf2014ToNad83.decimalYear(epochMillis))
    }
}
