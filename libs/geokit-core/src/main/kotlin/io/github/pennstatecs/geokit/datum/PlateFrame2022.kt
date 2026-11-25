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
 * [status] is [ProductStatus.UNAVAILABLE] for all four (2026-11-25): NGS has published no reference
 * coordinates to verify an implementation against — beta NCAT does not offer ITRF2020 as input or
 * output, NGS's developer test dataset is announced but not released, and EPSG/PROJ 9.8.1 have only a
 * ballpark no-op for NATRF2022 — and the beta page's equations are sign-ambiguous (see
 * [EulerPoleRotation]). [rotation] is provided for analysis and tests only; the default
 * [FrameTransformationRegistry] has no edge to any of these frames.
 *
 * [id] values are reserved stable tokens (same rules as [ReferenceFrame.id]). Only NATRF2022 is a
 * [ReferenceFrame] entry so far ([referenceFrame]); the others become entries when they become usable.
 *
 * @property id stable persistable token.
 * @property displayName NGS name.
 * @property plate the tectonic plate the frame follows.
 * @property epp NGS beta EPP2022 parameters relative to ITRF2020.
 * @property epsg3d EPSG geographic 3D CRS code, null if EPSG has none (EPSG v12.029 has NATRF2022 only).
 * @property referenceFrame the matching [ReferenceFrame], null if not yet modelled as one.
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
    PATRF2022("patrf2022", "PATRF2022", TectonicPlate.PACIFIC, EulerPoleParameters(-0.409, 1.063, -2.188), null, null),
    CATRF2022("catrf2022", "CATRF2022", TectonicPlate.CARIBBEAN, EulerPoleParameters(-0.056, -0.957, 0.589), null, null),
    MATRF2022("matrf2022", "MATRF2022", TectonicPlate.MARIANA, EulerPoleParameters(-8.089, 5.937, 2.159), null, null);

    /** NGS reference epoch at which the frame equals ITRF2020 (decimal year). */
    public val referenceEpoch: Double get() = REFERENCE_EPOCH

    /** ITRF2020 → this frame at a common coordinate epoch (unverified; see the class documentation). */
    public val rotation: EulerPoleRotation by lazy { EulerPoleRotation(epp, REFERENCE_EPOCH) }

    /** Verification status of the ITRF2020 ⇄ frame transformation in GeoKit. */
    public val status: ProductStatus get() = ProductStatus.UNAVAILABLE

    /** Why [status] is not verified, for logs and diagnostics. */
    public val unavailableReason: String get() = "$displayName: $UNAVAILABLE_REASON"

    public companion object {
        /** NGS: all four frames are identical to ITRF2020 at epoch 2020.0. */
        public const val REFERENCE_EPOCH: Double = 2020.0

        /** Dataset label of [epp]. */
        public const val DATASET_VERSION: String =
            "EPP2022 beta (beta.ngs.noaa.gov/NATRF2022/epp2022-beta-values.csv, Last-Modified 2025-06-16, retrieved 2026-11-25)"

        /** Common reason none of the frames is verified (2026-11-25). */
        public const val UNAVAILABLE_REASON: String =
            "the NGS beta EPP2022 transformation from ITRF2020 is known, but no NGS reference coordinates exist " +
                "to verify it (beta NCAT has no ITRF2020 option, the NGS developer test dataset is not yet " +
                "released) and the published beta equations are sign-ambiguous; PROJ 9.8.1 offers only ballpark " +
                "no-op operations, which are not used."

        /** The frame with this stable [id], or null. */
        public fun fromId(id: String?): PlateFrame2022? = entries.firstOrNull { it.id == id }

        /** The 2022 frame following [plate]. */
        public fun forPlate(plate: TectonicPlate): PlateFrame2022 = entries.first { it.plate == plate }
    }
}
