package io.github.pennstatecs.geokit.geoid

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import kotlin.math.floor

/**
 * NGS GEOID18 (orthometric height = NAD83(2011) ellipsoid height − N): NAVD88 in CONUS, PRVD02 in
 * Puerto Rico, VIVD09 in the US Virgin Islands. Alaska, Hawaii and the Pacific territories are not
 * covered by GEOID18 (they use GEOID12B) and return null.
 *
 * The bundled asset packs the two NGS GEOID18 grids — g2018u0 (CONUS, 24–58 °N, 130–60 °W) and
 * g2018p0 (PR/VI, 15–21 °N, 69–64 °W), both 1′ and taken from the Format_unix (big-endian) files —
 * into 1°×1° tiles of integer millimetres, zlib-compressed (5 MB instead of 34 MB). Tiles are
 * inflated on first use and cached. Rounding to mm adds ≤ 0.5 mm; interpolation is bilinear, as in
 * NGS's own tools. Format and generator: libs/geokit-core/tools/coords/pack_geoid.py ("SRGT" v1).
 *
 * Thread-safe: tile decoding is synchronised; decoded tiles are immutable.
 *
 * Valid range: CONUS 24–58 °N, 130–60 °W and PR/VI 15–21 °N, 69–64 °W; null elsewhere.
 * Accuracy vs the NGS geoid service: < 2 mm (mm rounding ≤ 0.5 mm + bilinear interpolation).
 *
 * @param bytes the packed SRGT file. This library bundles it as the classpath resource
 *   [RESOURCE_PATH]; [fromClasspath] loads it.
 */
public class Geoid18Grid(private val bytes: ByteArray) {

    private class Region(
        val south: Double, val westE: Double, val dLat: Double, val dLon: Double,
        val rows: Int, val cols: Int, val tileCells: Int, val tileRows: Int, val tileCols: Int,
        val offsets: IntArray, val lengths: IntArray,
    ) {
        val tiles = arrayOfNulls<IntArray>(tileRows * tileCols)
    }

    private val regions: List<Region>

    init {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4).also { b.get(it) }
        require(String(magic, Charsets.US_ASCII) == "SRGT") { "GEOID18: not an SRGT tile file" }
        val version = b.int
        require(version == 1) { "GEOID18: unsupported tile format v$version" }
        regions = List(b.int) {
            val south = b.double; val westE = b.double; val dLat = b.double; val dLon = b.double
            val rows = b.int; val cols = b.int; val t = b.int; val tr = b.int; val tc = b.int
            val off = IntArray(tr * tc); val len = IntArray(tr * tc)
            for (i in 0 until tr * tc) { off[i] = b.int; len[i] = b.int }
            Region(south, westE, dLat, dLon, rows, cols, t, tr, tc, off, len)
        }
    }

    /**
     * Geoid undulation N (metres) at a NAD83(2011) latitude/longitude (degrees), or null outside
     * GEOID18 coverage. Orthometric height = ellipsoidal height − N.
     */
    public fun undulation(latDeg: Double, lonDeg: Double): Double? {
        val lonE = if (lonDeg < 0) lonDeg + 360 else lonDeg
        for (r in regions) {
            val fr = (latDeg - r.south) / r.dLat
            val fc = (lonE - r.westE) / r.dLon
            if (fr < 0 || fc < 0 || fr > r.rows - 1 || fc > r.cols - 1) continue
            val row = floor(fr).toInt().coerceAtMost(r.rows - 2)
            val col = floor(fc).toInt().coerceAtMost(r.cols - 2)
            val tr = row / r.tileCells; val tc = col / r.tileCells
            val tile = tile(r, tr * r.tileCols + tc)
            val i = row - tr * r.tileCells; val j = col - tc * r.tileCells
            val w = r.tileCells + 1
            fun v(ii: Int, jj: Int) = tile[ii * w + jj] / 1000.0
            val t = fr - row; val u = fc - col
            val sw = v(i, j); val se = v(i, j + 1); val nw = v(i + 1, j); val ne = v(i + 1, j + 1)
            return (sw * (1 - u) + se * u) * (1 - t) + (nw * (1 - u) + ne * u) * t
        }
        return null
    }

    @Synchronized
    private fun tile(r: Region, index: Int): IntArray {
        r.tiles[index]?.let { return it }
        val w = r.tileCells + 1
        val raw = ByteArray(4 + 2 * w * w)
        val inflater = Inflater()
        try {
            inflater.setInput(bytes, r.offsets[index], r.lengths[index])
            var n = 0
            while (n < raw.size) {
                val k = inflater.inflate(raw, n, raw.size - n)
                if (k == 0 && (inflater.finished() || inflater.needsInput())) break
                n += k
            }
            check(n == raw.size) { "GEOID18: tile $index truncated ($n of ${raw.size} bytes)" }
        } finally { inflater.end() }
        val b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        val base = b.int
        val v = IntArray(w * w)
        for (i in 0 until w) for (j in 0 until w) {
            val pred = when {
                i == 0 && j == 0 -> base
                i == 0 -> v[j - 1]
                j == 0 -> v[(i - 1) * w]
                else -> v[i * w + j - 1] + v[(i - 1) * w + j] - v[(i - 1) * w + j - 1]
            }
            v[i * w + j] = pred + b.short
        }
        r.tiles[index] = v
        return v
    }

    public companion object {
        /**
         * Relative path of the packed grid. It ships inside this library as the classpath resource
         * [RESOURCE_PATH] (the same path); the name is kept from when the app bundled it as an asset.
         */
        public const val ASSET_PATH: String = "geoid/geoid18.srgt"

        /** Classpath location of the packed grid bundled in geokit-core's jar (see [fromClasspath]). */
        public const val RESOURCE_PATH: String = ASSET_PATH

        /** Reads a packed SRGT grid from [input] (the stream is read fully, not closed). */
        public fun fromInputStream(input: java.io.InputStream): Geoid18Grid = Geoid18Grid(input.readBytes())

        /**
         * Loads the GEOID18 grid bundled with this library (classpath resource [RESOURCE_PATH],
         * about 5.2 MB). Blocking I/O: call it off the main/UI thread. Works on the JVM and on Android,
         * which packages Java resources from library jars into the APK.
         *
         * @throws IllegalStateException if the resource is not on the classpath (e.g. stripped by a
         *   packaging rule).
         */
        public fun fromClasspath(): Geoid18Grid {
            val input = Geoid18Grid::class.java.getResourceAsStream("/$RESOURCE_PATH")
                ?: throw IllegalStateException(
                    "GEOID18: classpath resource $RESOURCE_PATH not found; it ships in the geokit-core jar " +
                        "(libs/geokit-core/src/main/resources/$RESOURCE_PATH) - check packaging excludes"
                )
            return input.use { fromInputStream(it) }
        }

        /** Orthometric datum GEOID18 yields at a point (degrees), for labels: NAVD88, PRVD02 or VIVD09. */
        public fun verticalDatumName(latDeg: Double, lonDeg: Double): String = when {
            latDeg >= 24.0 -> "NAVD88"
            lonDeg >= -65.15 -> "VIVD09"   // St Thomas / St John / St Croix lie east of Culebra
            else -> "PRVD02"
        }
    }
}
