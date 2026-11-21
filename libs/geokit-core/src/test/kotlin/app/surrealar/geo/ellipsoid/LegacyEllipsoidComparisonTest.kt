package app.surrealar.geo.ellipsoid

import app.surrealar.geo.local.LocalEnu
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The merged [Ellipsoid] / [LocalEnu] must reproduce the four implementations it replaced to within
 * 1e-6 m. The old code is copied here verbatim (Legacy*) as the reference.
 */
class LegacyEllipsoidComparisonTest {

    // ── verbatim copies of the replaced implementations ─────────────────────────────────────────
    private object LegacyGeodesyWgs84 {   // gnss/capture/math/Geodesy.kt (Bowring inverse)
        const val A = 6378137.0; const val F = 1.0 / 298.257223563; const val B = A * (1 - F)
        val E2 = 1 - (B * B) / (A * A)
        fun llaToEcef(latDeg: Double, lonDeg: Double, h: Double): DoubleArray {
            val lat = Math.toRadians(latDeg); val lon = Math.toRadians(lonDeg)
            val n = A / sqrt(1 - E2 * sin(lat) * sin(lat))
            return doubleArrayOf((n + h) * cos(lat) * cos(lon), (n + h) * cos(lat) * sin(lon), (n * (1 - E2) + h) * sin(lat))
        }
        fun ecefToLla(x: Double, y: Double, z: Double): DoubleArray {
            val ep2 = (A * A - B * B) / (B * B); val p = sqrt(x * x + y * y)
            val th = atan2(A * z, B * p); val lon = atan2(y, x)
            val lat = atan2(z + ep2 * B * sin(th).pow(3), p - E2 * A * cos(th).pow(3))
            val n = A / sqrt(1 - E2 * sin(lat) * sin(lat))
            val h = if (abs(lat) < PI / 4.0) p / cos(lat) - n else z / sin(lat) - n * (1.0 - E2)
            return doubleArrayOf(Math.toDegrees(lat), Math.toDegrees(lon), h)
        }
        fun ecefDeltaToEnu(dx: Double, dy: Double, dz: Double, refLat: Double, refLon: Double): DoubleArray {
            val lat = Math.toRadians(refLat); val lon = Math.toRadians(refLon)
            return doubleArrayOf(
                -sin(lon) * dx + cos(lon) * dy,
                -sin(lat) * cos(lon) * dx - sin(lat) * sin(lon) * dy + cos(lat) * dz,
                cos(lat) * cos(lon) * dx + cos(lat) * sin(lon) * dy + sin(lat) * dz)
        }
    }

    private object LegacyGrs80 {   // coords/DatumTransform.kt Grs80 (iterative inverse)
        const val A = 6378137.0; const val F = 1.0 / 298.257222101; val E2 = F * (2 - F)
        fun toEcef(lat: Double, lon: Double, h: Double): DoubleArray {
            val la = Math.toRadians(lat); val lo = Math.toRadians(lon)
            val n = A / sqrt(1 - E2 * sin(la) * sin(la))
            return doubleArrayOf((n + h) * cos(la) * cos(lo), (n + h) * cos(la) * sin(lo), (n * (1 - E2) + h) * sin(la))
        }
        fun fromEcef(x: Double, y: Double, z: Double): DoubleArray {
            val lon = atan2(y, x); val p = sqrt(x * x + y * y)
            var lat = atan2(z, p * (1 - E2)); var h = 0.0
            repeat(10) {
                val n = A / sqrt(1 - E2 * sin(lat) * sin(lat))
                h = p / cos(lat) - n
                lat = atan2(z, p * (1 - E2 * n / (n + h)))
            }
            return doubleArrayOf(Math.toDegrees(lat), Math.toDegrees(lon), h)
        }
    }

    // ── comparisons ───────────────────────────────────────────────────────────────────────────────
    private val lats = listOf(-89.5, -60.0, -33.8688, -0.001, 0.0, 18.4655, 41.3477, 45.0, 64.0, 84.0, 89.5)
    private val lons = listOf(-179.9, -118.2437, -76.0226, 0.0, 11.5, 151.2, 179.9)
    private val heights = listOf(-100.0, 0.0, 330.0, 2_500.0, 9_000.0)

    /** Position difference in metres from (Δlat, Δlon, Δh). */
    private fun diffM(a: DoubleArray, b: GeodeticPosition): Double {
        val dn = Math.toRadians(a[0] - b.latDeg) * 6_371_000.0
        val de = Math.toRadians(a[1] - b.lonDeg) * 6_371_000.0 * cos(Math.toRadians(b.latDeg))
        return maxOf(abs(dn), abs(de), abs(a[2] - b.heightM))
    }

    @Test fun `WGS84 ECEF and inverse match the old Bowring Geodesy within 1e-6 m`() {
        var worst = 0.0
        for (lat in lats) for (lon in lons) for (h in heights) {
            val old = LegacyGeodesyWgs84.llaToEcef(lat, lon, h)
            val new = Ellipsoid.WGS84.toEcef(lat, lon, h)
            worst = maxOf(worst, abs(old[0] - new.xM), abs(old[1] - new.yM), abs(old[2] - new.zM))
            worst = maxOf(worst, diffM(LegacyGeodesyWgs84.ecefToLla(old[0], old[1], old[2]), Ellipsoid.WGS84.fromEcef(new)))
        }
        println("WGS84 vs old Geodesy: worst difference ${"%.3e".format(worst)} m")
        assertTrue("worst $worst m", worst < 1e-6)
    }

    @Test fun `GRS80 ECEF and inverse match the old iterative Grs80 within 1e-6 m`() {
        var worst = 0.0
        for (lat in lats.filter { abs(it) < 89.0 }) for (lon in lons) for (h in heights) {
            val old = LegacyGrs80.toEcef(lat, lon, h)
            val new = Ellipsoid.GRS80.toEcef(lat, lon, h)
            worst = maxOf(worst, abs(old[0] - new.xM), abs(old[1] - new.yM), abs(old[2] - new.zM))
            worst = maxOf(worst, diffM(LegacyGrs80.fromEcef(old[0], old[1], old[2]), Ellipsoid.GRS80.fromEcef(new)))
        }
        println("GRS80 vs old Grs80: worst difference ${"%.3e".format(worst)} m")
        assertTrue("worst $worst m", worst < 1e-6)
    }

    @Test fun `LocalEnu matches the old ENU rotation within 1e-6 m`() {
        var worst = 0.0
        for (lat in lats) for (lon in lons) {
            val enu = LocalEnu(lat, lon, 300.0)
            val o = LegacyGeodesyWgs84.llaToEcef(lat, lon, 300.0)
            val p = LegacyGeodesyWgs84.llaToEcef(lat + 0.001, lon + 0.0013, 310.0)
            val old = LegacyGeodesyWgs84.ecefDeltaToEnu(p[0] - o[0], p[1] - o[1], p[2] - o[2], lat, lon)
            val new = enu.toEnu(lat + 0.001, lon + 0.0013, 310.0)
            for (i in 0..2) worst = maxOf(worst, abs(old[i] - new[i]))
        }
        println("LocalEnu vs old ENU: worst difference ${"%.3e".format(worst)} m")
        assertTrue("worst $worst m", worst < 1e-6)
    }
}
