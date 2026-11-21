package io.github.pennstatecs.geokit.ellipsoid

import net.sf.geographiclib.GeodesicMask
import kotlin.math.cos
import kotlin.math.sin
import net.sf.geographiclib.Geodesic as KarneyGeodesic

/**
 * Distance and bearing between two points on the WGS84 ellipsoid (Karney's geodesic algorithm via
 * GeographicLib — accurate to nanometres at any range). The one distance/bearing API for the app;
 * replaces the haversine/sphere helpers. Inputs: WGS84 latitude/longitude in degrees.
 */
public object Geodesic {

    private val wgs84 = KarneyGeodesic.WGS84

    /** North/east components (metres) of the geodesic from one point to another. */
    public data class NorthEast(val northM: Double, val eastM: Double)

    /** Geodesic (shortest) distance on the ellipsoid, metres. */
    public fun distanceM(fromLatDeg: Double, fromLonDeg: Double, toLatDeg: Double, toLonDeg: Double): Double =
        wgs84.Inverse(fromLatDeg, fromLonDeg, toLatDeg, toLonDeg, GeodesicMask.DISTANCE).s12

    /** Initial true bearing (forward azimuth) at the start point, degrees clockwise from north in [0, 360). */
    public fun initialBearingDeg(fromLatDeg: Double, fromLonDeg: Double, toLatDeg: Double, toLonDeg: Double): Double {
        val azi = wgs84.Inverse(fromLatDeg, fromLonDeg, toLatDeg, toLonDeg, GeodesicMask.AZIMUTH).azi1
        val b = azi % 360.0
        return if (b < 0) b + 360.0 else b
    }

    /**
     * North/east offsets to the target: geodesic distance resolved along the initial bearing
     * (s·cos α, s·sin α). At stakeout ranges this agrees with a local-ENU projection to well under 1 mm.
     */
    public fun northEastDeltaM(fromLatDeg: Double, fromLonDeg: Double, toLatDeg: Double, toLonDeg: Double): NorthEast {
        val r = wgs84.Inverse(fromLatDeg, fromLonDeg, toLatDeg, toLonDeg,
            GeodesicMask.DISTANCE or GeodesicMask.AZIMUTH)
        val a = Math.toRadians(r.azi1)
        return NorthEast(r.s12 * cos(a), r.s12 * sin(a))
    }
}
