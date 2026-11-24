package io.github.pennstatecs.geokit.datum

import io.github.pennstatecs.geokit.ellipsoid.Ellipsoid
import io.github.pennstatecs.geokit.ellipsoid.GeodeticPosition

/**
 * ITRF2014 ⇄ ITRF2020: EPSG "ITRF2014 to ITRF2020 (1)", a 14-parameter time-dependent Helmert in the
 * position-vector convention with parameter reference epoch 2015.0. Values as in PROJ 9.8.1 for
 * EPSG:7912 → EPSG:9989 (`+proj=helmert +x=0.0014 +y=0.0009 +z=-0.0014 +s=0.00042 +dy=0.0001
 * +dz=-0.0002 +t_epoch=2015 +convention=position_vector`, geocentric on GRS80), verified 2026-11-24:
 * T = (+1.4, +0.9, −1.4) mm, D = +0.42 ppb, Ṫ = (0, +0.1, −0.2) mm/yr, R = Ṙ = Ḋ = 0. EPSG accuracy 1 mm.
 *
 * With zero rotations the transformation is a translation plus scale, so [inverse] is exact. Evaluate
 * at the coordinate epoch (decimal year) of the position. Effect: a few millimetres.
 */
public object Itrf2014ToItrf2020 {
    private val GRS80 = Ellipsoid.GRS80
    private const val T0 = 2015.0

    private const val TX = 0.0014;  private const val TY = 0.0009;  private const val TZ = -0.0014   // m
    private const val S = 0.42                                                                     // ppb
    private const val DTX = 0.0;    private const val DTY = 0.0001; private const val DTZ = -0.0002   // m/yr

    /** ITRF2014 at [epoch] (decimal year) → ITRF2020. Degrees and ellipsoidal metres. */
    public fun forward(p: GeodeticPosition, epoch: Double): GeodeticPosition {
        val (x, y, z) = GRS80.toEcef(p)
        val dt = epoch - T0
        val k = 1 + S * 1e-9
        return GRS80.fromEcef(TX + DTX * dt + k * x, TY + DTY * dt + k * y, TZ + DTZ * dt + k * z)
    }

    /** ITRF2020 at [epoch] (decimal year) → ITRF2014 (exact inverse of [forward]). */
    public fun inverse(p: GeodeticPosition, epoch: Double): GeodeticPosition {
        val (x, y, z) = GRS80.toEcef(p)
        val dt = epoch - T0
        val k = 1 + S * 1e-9
        return GRS80.fromEcef((x - TX - DTX * dt) / k, (y - TY - DTY * dt) / k, (z - TZ - DTZ * dt) / k)
    }
}
