package io.github.pennstatecs.geokit.validation

import kotlin.math.abs

/** Plausibility checks for geographic positions. */
public object LatLonValidation {

    /** Positions closer than this to 0,0 (degrees) are treated as the invalid "null island" default. */
    public const val NULL_ISLAND_EPSILON_DEG: Double = 1e-7

    /**
     * True when [latDeg]/[lonDeg] are finite, within geographic bounds, and not the 0,0 null-island
     * default (a common "no fix" / emulator placeholder). Use it as the gate before accepting a
     * captured or imported position.
     */
    public fun isValidLatLon(latDeg: Double, lonDeg: Double): Boolean =
        latDeg.isFinite() && lonDeg.isFinite() &&
            latDeg in -90.0..90.0 && lonDeg in -180.0..180.0 &&
            !(abs(latDeg) < NULL_ISLAND_EPSILON_DEG && abs(lonDeg) < NULL_ISLAND_EPSILON_DEG)
}
