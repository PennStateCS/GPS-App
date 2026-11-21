package app.surrealar.geo.datum

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A geodetic position: latitude/longitude in degrees, ellipsoidal height in metres. */
data class GeodeticPosition(val latDeg: Double, val lonDeg: Double, val heightM: Double)

/**
 * GRS80 ellipsoid (NAD83). WGS84 differs only in the 6th significant digit of the flattening —
 * < 0.1 mm in position — so this is also used for WGS84/ITRF geocentric conversions.
 */
object Grs80 {
    const val A = 6378137.0
    const val INV_F = 298.257222101
    const val F = 1.0 / INV_F
    val E2 = F * (2 - F)

    fun toEcef(p: GeodeticPosition): DoubleArray {
        val lat = Math.toRadians(p.latDeg); val lon = Math.toRadians(p.lonDeg)
        val sLat = sin(lat); val cLat = cos(lat)
        val n = A / sqrt(1 - E2 * sLat * sLat)
        return doubleArrayOf(
            (n + p.heightM) * cLat * cos(lon),
            (n + p.heightM) * cLat * sin(lon),
            (n * (1 - E2) + p.heightM) * sLat,
        )
    }

    /** Iterative inverse; converges to well below 0.1 mm in a few iterations for terrestrial points. */
    fun fromEcef(x: Double, y: Double, z: Double): GeodeticPosition {
        val lon = atan2(y, x)
        val p = sqrt(x * x + y * y)
        var lat = atan2(z, p * (1 - E2))
        var h = 0.0
        repeat(10) {
            val sLat = sin(lat)
            val n = A / sqrt(1 - E2 * sLat * sLat)
            h = p / cos(lat) - n
            lat = atan2(z, p * (1 - E2 * n / (n + h)))
        }
        return GeodeticPosition(Math.toDegrees(lat), Math.toDegrees(lon), h)
    }
}

/**
 * ITRF2014 ⇄ NAD83(2011): the 14-parameter time-dependent Helmert transformation "ITRF2014 to
 * NAD83(2011) (1)" as defined in the EPSG registry and implemented by PROJ (coordinate-frame
 * rotation convention, parameter reference epoch 2010.0). Values copied verbatim from PROJ 9.8.1
 * (`+proj=helmert +x=1.0053 +y=-1.90921 +z=-0.54157 +rx=0.02678138 +ry=-0.00042027 +rz=0.01093206
 * +s=0.00036891 +dx=0.00079 +dy=-0.0006 +dz=-0.00144 +drx=6.667e-05 +dry=-0.00075744 +drz=-5.133e-05
 * +ds=-7.201e-05 +t_epoch=2010 +convention=coordinate_frame`), verified 2026-11-19.
 *
 * Current WGS84 realizations (G2139/G2296) are aligned with ITRF2014/2020 at the centimetre level,
 * so receiver "WGS84" coordinates are treated as ITRF2014. The transformation must be evaluated at
 * the OBSERVATION epoch (decimal year): NAD83(2011) is fixed to the North American plate, ITRF is not.
 */
object Itrf2014ToNad83 {
    private const val T0 = 2010.0
    private const val MAS = Math.PI / 180.0 / 3600.0 / 1000.0   // milliarc-second → radians

    private const val TX = 1.0053;   private const val TY = -1.90921; private const val TZ = -0.54157   // m
    private const val RX = 26.78138; private const val RY = -0.42027; private const val RZ = 10.93206   // mas
    private const val S = 0.36891                                                                        // ppb
    private const val DTX = 0.00079; private const val DTY = -0.0006;  private const val DTZ = -0.00144  // m/yr
    private const val DRX = 0.06667; private const val DRY = -0.75744; private const val DRZ = -0.05133  // mas/yr
    private const val DS = -0.07201                                                                      // ppb/yr

    private class Params(val tx: Double, val ty: Double, val tz: Double,
                         val rx: Double, val ry: Double, val rz: Double, val s: Double)

    private fun at(epoch: Double): Params {
        val dt = epoch - T0
        return Params(
            TX + DTX * dt, TY + DTY * dt, TZ + DTZ * dt,
            (RX + DRX * dt) * MAS, (RY + DRY * dt) * MAS, (RZ + DRZ * dt) * MAS,
            (S + DS * dt) * 1e-9,
        )
    }

    /** ITRF2014 (≈ WGS84) at [epoch] → NAD83(2011). */
    fun forward(p: GeodeticPosition, epoch: Double): GeodeticPosition {
        val (x, y, z) = Grs80.toEcef(p).let { Triple(it[0], it[1], it[2]) }
        val q = at(epoch)
        val k = 1 + q.s
        // Coordinate-frame convention (EPSG 1056 / PROJ convention=coordinate_frame).
        val x2 = q.tx + k * (x + q.rz * y - q.ry * z)
        val y2 = q.ty + k * (-q.rz * x + y + q.rx * z)
        val z2 = q.tz + k * (q.ry * x - q.rx * y + z)
        return Grs80.fromEcef(x2, y2, z2)
    }

    /** NAD83(2011) → ITRF2014 (≈ WGS84) at [epoch]: exact inverse to first order (error ≪ 0.1 mm). */
    fun inverse(p: GeodeticPosition, epoch: Double): GeodeticPosition {
        val (x2, y2, z2) = Grs80.toEcef(p).let { Triple(it[0], it[1], it[2]) }
        val q = at(epoch)
        val k = 1 + q.s
        val vx = (x2 - q.tx) / k; val vy = (y2 - q.ty) / k; val vz = (z2 - q.tz) / k
        // Transpose of the (orthogonal to first order) rotation matrix.
        val x = vx - q.rz * vy + q.ry * vz
        val y = q.rz * vx + vy - q.rx * vz
        val z = -q.ry * vx + q.rx * vy + vz
        return Grs80.fromEcef(x, y, z)
    }

    /** Decimal year for an instant (e.g. 2026-11-20T00:00Z ≈ 2026.886), the epoch argument above. */
    fun decimalYear(epochMillis: Long): Double {
        val dt = java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneOffset.UTC)
        val start = dt.withDayOfYear(1).toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC)
        val end = start.plusYears(1)
        val frac = (epochMillis - start.toInstant().toEpochMilli()).toDouble() /
            (end.toInstant().toEpochMilli() - start.toInstant().toEpochMilli())
        return dt.year + frac
    }
}
