package io.github.pennstatecs.geokit.datum

import io.github.pennstatecs.geokit.ellipsoid.EcefPosition
import io.github.pennstatecs.geokit.ellipsoid.Ellipsoid
import io.github.pennstatecs.geokit.ellipsoid.GeodeticPosition

/**
 * Euler pole parameters (EPP): the angular velocity of a tectonic plate relative to a global frame, as
 * Cartesian rotation-rate components about the geocentric X, Y and Z axes, in milliarcseconds per year,
 * positive counter-clockwise (right-hand rule) — the units and sign convention of NGS's EPP2022 table.
 */
public data class EulerPoleParameters(
    val omegaXMasPerYr: Double,
    val omegaYMasPerYr: Double,
    val omegaZMasPerYr: Double,
) {
    init {
        require(omegaXMasPerYr.isFinite() && omegaYMasPerYr.isFinite() && omegaZMasPerYr.isFinite()) { "EPP must be finite" }
    }
}

/**
 * The time-dependent plate rotation that relates a global frame (ITRF2020 for the 2022 frames) to a
 * plate-fixed frame at the same coordinate epoch t. Pure ECEF rotation: no translation, no scale, no
 * intra-frame deformation model (IFDM2022 is a separate NGS epoch-propagation product, see
 * docs/geodesy/nsrs2022-sources-frames.md).
 *
 * With Ω the plate's angular velocity in the global frame, K = π / (648 × 10⁶) (mas → rad) and
 * Δt = t − [referenceEpoch]:
 *
 *     X_plate(t) = X_global(t) − K·Δt·(Ω × X_global(t))
 *
 * i.e. ΔX = K·Δt·(Ω_y·Z − Ω_z·Y), ΔY = K·Δt·(Ω_z·X − Ω_x·Z), ΔZ = K·Δt·(Ω_x·Y − Ω_y·X), X_plate = X − ΔX.
 * This is NGS's official definitional relationship, NOAA TR NOS NGS 62 (Blueprint Part 1, revision of
 * 2021-04-20) eqs. 58–60: X_N(t) = {I + (t − t0)·[[0, ω̇Z, −ω̇Y], [−ω̇Z, 0, ω̇X], [ω̇Y, −ω̇X, 0]]}·X_I(t),
 * with ω̇ the plate's rotation rates about the ITRF axes (eqs. 64–66) — the sign that keeps the
 * plate-fixed coordinates of a point riding the rigid plate constant (Condition 1). NGS-computed
 * coordinates from the beta Geodetic Station Pages service agree to 1e-9 m for all four frames
 * ([PlateFrame2022.VERIFICATION]). The beta NATRF2022 web page's first equation, X_f = X_g − ΔX with the
 * same matrix, has the opposite sign; it disagrees with both TR 62 and NGS's own computed coordinates.
 *
 * The frames coincide at [referenceEpoch]. [inverse] is the exact inverse of [forward] (closed-form
 * inverse of I − [ω]×), not a sign-flipped approximation. Thread-safe and immutable.
 *
 * @property epp the plate's Euler pole parameters relative to the global frame.
 * @property referenceEpoch decimal year at which both frames coincide (2020.0 for the 2022 frames).
 */
public class EulerPoleRotation(
    public val epp: EulerPoleParameters,
    public val referenceEpoch: Double,
) {
    init {
        require(referenceEpoch.isFinite()) { "Reference epoch must be finite" }
    }

    /** Rotation vector K·Δt·Ω in radians at [epoch] (decimal year). */
    private fun w(epoch: Double): DoubleArray {
        require(epoch.isFinite()) { "Epoch must be finite, was $epoch" }
        val k = MAS_TO_RAD * (epoch - referenceEpoch)
        return doubleArrayOf(k * epp.omegaXMasPerYr, k * epp.omegaYMasPerYr, k * epp.omegaZMasPerYr)
    }

    /** Global-frame ECEF at [epoch] → plate-fixed ECEF at the same epoch (metres). */
    public fun forward(x: EcefPosition, epoch: Double): EcefPosition {
        val w = w(epoch)
        val cx = w[1] * x.zM - w[2] * x.yM
        val cy = w[2] * x.xM - w[0] * x.zM
        val cz = w[0] * x.yM - w[1] * x.xM
        return EcefPosition(x.xM - cx, x.yM - cy, x.zM - cz)
    }

    /** Plate-fixed ECEF at [epoch] → global-frame ECEF at the same epoch: exact inverse of [forward]. */
    public fun inverse(x: EcefPosition, epoch: Double): EcefPosition {
        // forward is y = (I − W) x with W v = w × v; (I − W)⁻¹ y = (y + w × y + (w·y) w) / (1 + |w|²).
        val w = w(epoch)
        val cx = w[1] * x.zM - w[2] * x.yM
        val cy = w[2] * x.xM - w[0] * x.zM
        val cz = w[0] * x.yM - w[1] * x.xM
        val dot = w[0] * x.xM + w[1] * x.yM + w[2] * x.zM
        val n = 1.0 + w[0] * w[0] + w[1] * w[1] + w[2] * w[2]
        return EcefPosition((x.xM + cx + dot * w[0]) / n, (x.yM + cy + dot * w[1]) / n, (x.zM + cz + dot * w[2]) / n)
    }

    /** As [forward] for geodetic positions on GRS80 (degrees, ellipsoidal metres). */
    public fun forward(p: GeodeticPosition, epoch: Double): GeodeticPosition =
        GRS80.fromEcef(forward(GRS80.toEcef(p), epoch))

    /** As [inverse] for geodetic positions on GRS80 (degrees, ellipsoidal metres). */
    public fun inverse(p: GeodeticPosition, epoch: Double): GeodeticPosition =
        GRS80.fromEcef(inverse(GRS80.toEcef(p), epoch))

    override fun toString(): String = "EulerPoleRotation($epp, t0=$referenceEpoch)"

    public companion object {
        /** K = π / (648 × 10⁶): milliarcseconds → radians, exactly as NGS writes it. */
        public const val MAS_TO_RAD: Double = Math.PI / 648e6

        private val GRS80 = Ellipsoid.GRS80
    }
}
