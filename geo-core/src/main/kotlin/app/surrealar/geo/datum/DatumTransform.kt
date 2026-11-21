package app.surrealar.geo.datum

import app.surrealar.geo.ellipsoid.Ellipsoid
import app.surrealar.geo.ellipsoid.GeodeticPosition

/**
 * Geocentric conversions for the Helmert transform use GRS80 (NAD83). WGS84 differs only in the 6th
 * significant digit of the flattening (< 0.1 mm in position), so GRS80 is used for both sides, as
 * before the ellipsoid code was merged into [Ellipsoid].
 */
private val GRS80 = Ellipsoid.GRS80

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
public object Itrf2014ToNad83 {
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

    /**
     * ITRF2014 (≈ WGS84) at observation [epoch] (decimal year) → NAD83(2011). Positions in degrees and
     * ellipsoidal metres. Matches PROJ to < 1 nm (geocentric).
     */
    public fun forward(p: GeodeticPosition, epoch: Double): GeodeticPosition {
        val (x, y, z) = GRS80.toEcef(p)
        val q = at(epoch)
        val k = 1 + q.s
        // Coordinate-frame convention (EPSG 1056 / PROJ convention=coordinate_frame).
        val x2 = q.tx + k * (x + q.rz * y - q.ry * z)
        val y2 = q.ty + k * (-q.rz * x + y + q.rx * z)
        val z2 = q.tz + k * (q.ry * x - q.rx * y + z)
        return GRS80.fromEcef(x2, y2, z2)
    }

    /** NAD83(2011) → ITRF2014 (≈ WGS84) at [epoch]: exact inverse to first order (error ≪ 0.1 mm). */
    public fun inverse(p: GeodeticPosition, epoch: Double): GeodeticPosition {
        val (x2, y2, z2) = GRS80.toEcef(p)
        val q = at(epoch)
        val k = 1 + q.s
        val vx = (x2 - q.tx) / k; val vy = (y2 - q.ty) / k; val vz = (z2 - q.tz) / k
        // Transpose of the (orthogonal to first order) rotation matrix.
        val x = vx - q.rz * vy + q.ry * vz
        val y = q.rz * vx + vy - q.rx * vz
        val z = -q.ry * vx + q.rx * vy + vz
        return GRS80.fromEcef(x, y, z)
    }

    /** Decimal year for an instant (e.g. 2026-11-20T00:00Z ≈ 2026.886), the epoch argument above. */
    public fun decimalYear(epochMillis: Long): Double {
        val dt = java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneOffset.UTC)
        val start = dt.withDayOfYear(1).toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC)
        val end = start.plusYears(1)
        val frac = (epochMillis - start.toInstant().toEpochMilli()).toDouble() /
            (end.toInstant().toEpochMilli() - start.toInstant().toEpochMilli())
        return dt.year + frac
    }
}
