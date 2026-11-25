package io.github.pennstatecs.geokit.projection

import io.github.pennstatecs.geokit.ellipsoid.Ellipsoid

/**
 * The map-projection methods GeoKit implements, with their EPSG method codes and the parameter keys a
 * [SpcsProjection] reports in [SpcsProjection.parameters] (PROJ-style keys; angles in degrees, lengths
 * in metres).
 *
 * [create] builds the existing projection class ([Tm], [Lcc2], [HomA]) from named parameters, so a
 * data-driven catalog (e.g. a future SPCS2022 table) reuses exactly the same maths as the generated
 * SPCS83 table. Parameter keys are part of the documented catalog format: never rename them.
 *
 * @property epsgCode EPSG coordinate-operation method code.
 * @property displayName EPSG method name.
 * @property token short stable token used in catalog files.
 * @property parameterNames required parameter keys, in definition order.
 */
public enum class ProjectionMethod(
    public val epsgCode: Int,
    public val displayName: String,
    public val token: String,
    public val parameterNames: List<String>,
) {
    /** Transverse Mercator (EPSG 9807): `lat_0`, `lon_0` (central meridian), `k_0`, `x_0`, `y_0`. */
    TRANSVERSE_MERCATOR(9807, "Transverse Mercator", "tm", listOf("lat_0", "lon_0", "k_0", "x_0", "y_0")),

    /**
     * Lambert Conic Conformal (2SP) (EPSG 9802): `lat_0`, `lon_0` (false origin), `lat_1`, `lat_2`
     * (standard parallels), `x_0`, `y_0`. GRS80 only.
     */
    LAMBERT_CONIC_CONFORMAL_2SP(9802, "Lambert Conic Conformal (2SP)", "lcc2sp",
        listOf("lat_0", "lon_0", "lat_1", "lat_2", "x_0", "y_0")),

    /**
     * Hotine Oblique Mercator (variant A) (EPSG 9812): `lat_0`, `lonc` (projection centre), `alpha`
     * (azimuth of the initial line), `gamma` (rectified to skew), `k_0`, `x_0`, `y_0`. GRS80 only,
     * forward only (see [HomA]).
     */
    HOTINE_OBLIQUE_MERCATOR_A(9812, "Hotine Oblique Mercator (variant A)", "omerc_a",
        listOf("lat_0", "lonc", "alpha", "gamma", "k_0", "x_0", "y_0")),

    /**
     * Lambert Conic Conformal (1SP) (EPSG 9801), SPCS2022 "LC1": `lat_0` (natural origin = the standard
     * parallel), `lon_0`, `k_0`, `x_0`, `y_0`. GRS80 only, forward and inverse (see [Lcc1]).
     */
    LAMBERT_CONIC_CONFORMAL_1SP(9801, "Lambert Conic Conformal (1SP)", "lcc1sp",
        listOf("lat_0", "lon_0", "k_0", "x_0", "y_0")),

    /**
     * Hotine Oblique Mercator (variant B) (EPSG 9815), SPCS2022 "OMC": `lat_0`, `lonc` (projection
     * centre), `alpha` (azimuth of the initial line at the centre), `gamma` (rectified to skew), `k_0`,
     * `x_0`, `y_0` (at the centre). GRS80 only, forward and inverse (see [HomB]).
     */
    HOTINE_OBLIQUE_MERCATOR_B(9815, "Hotine Oblique Mercator (variant B)", "omerc_b",
        listOf("lat_0", "lonc", "alpha", "gamma", "k_0", "x_0", "y_0"));

    /**
     * Builds the projection from [parameters] (keys from [parameterNames]) on [ellipsoid].
     * @throws IllegalArgumentException if a parameter is missing or not finite, or the method does not
     *   support [ellipsoid] (every method except [TRANSVERSE_MERCATOR] is GRS80 only).
     */
    public fun create(parameters: Map<String, Double>, ellipsoid: Ellipsoid = Ellipsoid.GRS80): SpcsProjection {
        val p = parameterNames.map { key ->
            val v = requireNotNull(parameters[key]) { "$displayName: missing parameter $key" }
            require(v.isFinite()) { "$displayName: parameter $key is not finite ($v)" }
            v
        }
        if (this != TRANSVERSE_MERCATOR) {
            require(ellipsoid == Ellipsoid.GRS80) { "$displayName is implemented on GRS80 only" }
        }
        return when (this) {
            TRANSVERSE_MERCATOR -> Tm(p[0], p[1], p[2], p[3], p[4], ellipsoid)
            LAMBERT_CONIC_CONFORMAL_2SP -> Lcc2(p[0], p[1], p[2], p[3], p[4], p[5])
            HOTINE_OBLIQUE_MERCATOR_A -> HomA(p[0], p[1], p[2], p[3], p[4], p[5], p[6])
            LAMBERT_CONIC_CONFORMAL_1SP -> Lcc1(p[0], p[1], p[2], p[3], p[4])
            HOTINE_OBLIQUE_MERCATOR_B -> HomB(p[0], p[1], p[2], p[3], p[4], p[5], p[6])
        }
    }

    public companion object {
        /**
         * Inverse of [projection] ((easting, northing) metres → (latitude, longitude) degrees), or null
         * when the method has no inverse in GeoKit ([HOTINE_OBLIQUE_MERCATOR_A], see [HomA]).
         */
        public fun inverse(projection: SpcsProjection, eastingM: Double, northingM: Double): Pair<Double, Double>? =
            when (projection) {
                is Tm -> projection.inverse(eastingM, northingM)
                is Lcc2 -> projection.inverse(eastingM, northingM)
                is Lcc1 -> projection.inverse(eastingM, northingM)
                is HomB -> projection.inverse(eastingM, northingM)
                is HomA -> null
            }

        /** The method with this [token] or EPSG code (as text), or null. Case-insensitive. */
        public fun fromToken(token: String): ProjectionMethod? {
            val t = token.trim().lowercase()
            return entries.firstOrNull { it.token == t || it.epsgCode.toString() == t }
        }
    }
}
