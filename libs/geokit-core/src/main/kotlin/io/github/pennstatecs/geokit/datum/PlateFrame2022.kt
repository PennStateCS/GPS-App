package io.github.pennstatecs.geokit.datum

import io.github.pennstatecs.geokit.status.ProductStatus

/**
 * Tectonic plates that carry a plate-fixed NSRS frame. [areaNote] is a short English hint for callers
 * that want to warn when a point lies outside the plate's usual area (GeoKit does not decide plate
 * membership: a point near a plate boundary may sit on either plate).
 */
public enum class TectonicPlate(public val displayName: String, public val areaNote: String) {
    NORTH_AMERICA("North American plate", "CONUS (except points west of the San Andreas system), Alaska, Canada, Greenland"),
    PACIFIC("Pacific plate", "Hawaii, American Samoa and other U.S. Pacific islands on the Pacific plate"),
    CARIBBEAN("Caribbean plate", "Puerto Rico and the U.S. Virgin Islands"),
    MARIANA("Mariana plate", "Guam and the Commonwealth of the Northern Mariana Islands (CNMI)"),
}

/**
 * The four plate-fixed terrestrial reference frames of the modernized NSRS, defined by NGS as
 * time-dependent rotations of ITRF2020 with the beta EPP2022 Euler pole parameters ([epp], mas/yr),
 * identical to ITRF2020 at [referenceEpoch] 2020.0 (beta.ngs.noaa.gov/NATRF2022, EPP CSV Last-Modified
 * 2025-06-16, retrieved 2026-11-25; sources and convention: docs/geodesy/nsrs2022-sources-frames.md).
 *
 * [rotation] is verified for all four frames against NGS-computed ITRF2020 → frame coordinates
 * (2026-11-25, [VERIFICATION]; docs/geodesy/phase4-ngs-investigation.md): it reproduces NGS's numbers to
 * 1e-9 m, and the sign is the one NOAA TR NOS NGS 62 (2021) eq. 60 defines. [status] still reports the
 * frame's status in the *default* [FrameTransformationRegistry], which has no 2022-frame edge
 * ([ProductStatus.UNAVAILABLE]); all four frames are available as [ProductStatus.VERIFIED_BETA] through the
 * opt-in [FrameTransformationRegistry.NSRS2022_BETA] (NATRF2022 since WP N, the other three since Phase 4 WP X).
 *
 * [id] values are stable tokens (same rules as [ReferenceFrame.id]). Every entry is a [ReferenceFrame]
 * ([referenceFrame]; PATRF2022 / CATRF2022 / MATRF2022 since Phase 4 WP X).
 *
 * @property id stable persistable token.
 * @property displayName NGS name.
 * @property plate the tectonic plate the frame follows.
 * @property epp NGS beta EPP2022 parameters relative to ITRF2020.
 * @property epsg3d EPSG geographic 3D CRS code, null if EPSG has none (EPSG v12.029 has NATRF2022 only).
 * @property referenceFrame the matching [ReferenceFrame] (null was used before a frame was modelled; none is now).
 */
public enum class PlateFrame2022(
    public val id: String,
    public val displayName: String,
    public val plate: TectonicPlate,
    public val epp: EulerPoleParameters,
    public val epsg3d: Int?,
    public val referenceFrame: ReferenceFrame?,
) {
    NATRF2022("natrf2022", "NATRF2022", TectonicPlate.NORTH_AMERICA, EulerPoleParameters(0.046, -0.704, -0.047), 10967, ReferenceFrame.NATRF2022),
    PATRF2022("patrf2022", "PATRF2022", TectonicPlate.PACIFIC, EulerPoleParameters(-0.409, 1.063, -2.188), null, ReferenceFrame.PATRF2022),
    CATRF2022("catrf2022", "CATRF2022", TectonicPlate.CARIBBEAN, EulerPoleParameters(-0.056, -0.957, 0.589), null, ReferenceFrame.CATRF2022),
    MATRF2022("matrf2022", "MATRF2022", TectonicPlate.MARIANA, EulerPoleParameters(-8.089, 5.937, 2.159), null, ReferenceFrame.MATRF2022);

    /** NGS reference epoch at which the frame equals ITRF2020 (decimal year). */
    public val referenceEpoch: Double get() = REFERENCE_EPOCH

    /** ITRF2020 → this frame at a common coordinate epoch (verified against NGS, see [VERIFICATION]). */
    public val rotation: EulerPoleRotation by lazy { EulerPoleRotation(epp, REFERENCE_EPOCH) }

    /** Status of the ITRF2020 ⇄ frame transformation in GeoKit's default registry (none is registered there). */
    public val status: ProductStatus get() = ProductStatus.UNAVAILABLE

    /** Why [status] is not verified, for logs and diagnostics. */
    public val unavailableReason: String get() = "$displayName: $UNAVAILABLE_REASON"

    public companion object {
        /** NGS: all four frames are identical to ITRF2020 at epoch 2020.0. */
        public const val REFERENCE_EPOCH: Double = 2020.0

        /** Dataset label of [epp]. */
        public const val DATASET_VERSION: String =
            "EPP2022 beta (beta.ngs.noaa.gov/NATRF2022/epp2022-beta-values.csv, Last-Modified 2025-06-16, retrieved 2026-11-25)"

        /**
         * How [rotation] was verified (2026-11-25): NGS-computed pairs from the NGS beta Geodetic Station
         * Pages service, test resources nsrs/ngs-gsp/ (tools/nsrs/fetch_epp2022_gsp.py).
         */
        public const val VERIFICATION: String =
            "NGS beta Geodetic Station Pages service (beta.ngs.noaa.gov/api_m/gsp/coords, retrieved 2026-11-25): " +
                "13 CORS on the four plates x epochs 2014.5/2020.0/2026.9, ITRF2020 -> NATRF/PATRF/CATRF/MATRF2022, " +
                "max |dXYZ| 1e-9 m (nsrs/ngs-gsp/epp2022_gsp_vectors.csv, PlateFrame2022NgsVectorsTest)"

        /** Why the default registry has no edge to the frames (2026-11-25). */
        public const val UNAVAILABLE_REASON: String =
            "the NGS beta EPP2022 transformation from ITRF2020 is verified against NGS-computed coordinates, " +
                "but it is a beta product and is not enabled in GeoKit's default registry (opt in with " +
                "FrameTransformationRegistry.NSRS2022_BETA); PROJ 9.8.1 " +
                "offers only ballpark no-op operations, which are not used."

        /** The frame with this stable [id], or null. */
        public fun fromId(id: String?): PlateFrame2022? = entries.firstOrNull { it.id == id }

        /** The 2022 frame following [plate]. */
        public fun forPlate(plate: TectonicPlate): PlateFrame2022 = entries.first { it.plate == plate }
    }
}
