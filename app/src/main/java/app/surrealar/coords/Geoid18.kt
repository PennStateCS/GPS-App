package app.surrealar.coords

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * NGS GEOID18 grid (NAVD88 = NAD83(2011) ellipsoid height − N). Reads the NGS ".bin" layout in its
 * big-endian ("unix") form: a 44-byte header — south latitude, west longitude (0–360 °E), latitude
 * spacing, longitude spacing (real*8), rows, columns, kind (int*4) — then rows of real*4 values from
 * south to north, each row west to east.
 *
 * The app bundles a Pennsylvania subgrid (39–43 °N, 81.5–74 °W) cut from NGS g2018u0.bin
 * (Format_unix, unaffected by the 2019 little-endian/ASCII distribution error). Values are
 * bilinearly interpolated; spot checks match NGS's own geoid service to < 1 cm.
 *
 * @return N in metres, or null outside the grid.
 */
class Geoid18Grid(bytes: ByteArray) {
    private val south: Double
    private val westE: Double
    private val dLat: Double
    private val dLon: Double
    private val rows: Int
    private val cols: Int
    private val data: FloatArray

    init {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        south = b.double; westE = b.double; dLat = b.double; dLon = b.double
        rows = b.int; cols = b.int
        val kind = b.int
        require(kind == 1) { "GEOID18: expected real*4 grid (kind=1), got $kind" }
        require(bytes.size == HEADER_BYTES + 4 * rows * cols) { "GEOID18: size ${bytes.size} != header-declared grid" }
        data = FloatArray(rows * cols) { b.float }
    }

    fun undulation(latDeg: Double, lonDeg: Double): Double? {
        val lonE = if (lonDeg < 0) lonDeg + 360 else lonDeg
        val fr = (latDeg - south) / dLat
        val fc = (lonE - westE) / dLon
        if (fr < 0 || fc < 0 || fr > rows - 1 || fc > cols - 1) return null
        val r = floor(fr).toInt().coerceAtMost(rows - 2)
        val c = floor(fc).toInt().coerceAtMost(cols - 2)
        val tr = fr - r; val tc = fc - c
        fun v(rr: Int, cc: Int) = data[rr * cols + cc].toDouble()
        val sw = v(r, c); val se = v(r, c + 1); val nw = v(r + 1, c); val ne = v(r + 1, c + 1)
        if (listOf(sw, se, nw, ne).any { it.isNaN() || it < -999 }) return null   // NGS no-data marker
        return (sw * (1 - tc) + se * tc) * (1 - tr) + (nw * (1 - tc) + ne * tc) * tr
    }

    companion object {
        const val HEADER_BYTES = 44
        const val ASSET_PATH = "geoid/geoid18_pa.bin"
    }
}
