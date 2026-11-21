package app.surrealar.stakeout

import app.surrealar.gnss.capture.math.Geodesy

/**
 * Stakeout distance, bearing and north/east offsets on the WGS84 ellipsoid: both points go to ECEF
 * and the difference is rotated into the local East/North/Up frame at the observer. Replaces a
 * 6,371,000 m sphere that was ~0.13 % long north–south and ~0.26 % short east–west at 41° N
 * (13–26 cm at 100 m). Heights are ignored (horizontal stakeout).
 */
object StakeoutGeometry {

    /** (north, east) metres from the observer to the target in the observer's local tangent plane. */
    fun northEast(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Pair<Double, Double> {
        val (x1, y1, z1) = Geodesy.llaToEcef(fromLat, fromLon, 0.0)
        val (x2, y2, z2) = Geodesy.llaToEcef(toLat, toLon, 0.0)
        val (e, n, _) = Geodesy.ecefDeltaToEnu(x2 - x1, y2 - y1, z2 - z1, fromLat, fromLon)
        return n to e
    }

    fun distanceM(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val (n, e) = northEast(fromLat, fromLon, toLat, toLon)
        return Math.hypot(n, e)
    }

    /** True bearing, degrees clockwise from north in [0, 360). */
    fun bearingDeg(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val (n, e) = northEast(fromLat, fromLon, toLat, toLon)
        val b = Math.toDegrees(Math.atan2(e, n))
        return if (b < 0) b + 360.0 else b
    }
}
