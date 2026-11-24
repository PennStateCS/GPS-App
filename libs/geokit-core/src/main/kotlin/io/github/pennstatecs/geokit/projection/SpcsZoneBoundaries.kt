package io.github.pennstatecs.geokit.projection

import java.io.ByteArrayInputStream
import java.io.DataInputStream

/**
 * Zone polygons read from a resource written by `libs/geokit-core/tools/coords/gen_spcs_boundaries.py`
 * (format described there): per zone key (the zone's EPSG code for SPCS83), a set of rings in
 * 1/scale degree, combined with the even-odd rule. Coordinates are latitude/longitude in degrees;
 * no ring crosses the antimeridian (Alaska is not written).
 */
internal class SpcsZoneBoundaries private constructor(
    private val scale: Double,
    private val rings: Map<Int, List<Ring>>,
) {
    /** One closed ring: interleaved (lon, lat) in 1/scale degree, plus its bounding box. */
    private class Ring(val xy: IntArray) {
        val minX: Int; val maxX: Int; val minY: Int; val maxY: Int
        init {
            var x0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y0 = Int.MAX_VALUE; var y1 = Int.MIN_VALUE
            for (i in xy.indices step 2) {
                x0 = minOf(x0, xy[i]); x1 = maxOf(x1, xy[i]); y0 = minOf(y0, xy[i + 1]); y1 = maxOf(y1, xy[i + 1])
            }
            minX = x0; maxX = x1; minY = y0; maxY = y1
        }

        /** Number of edges crossed by a ray from (x, y) towards +x (even-odd rule). */
        fun crossings(x: Double, y: Double): Int {
            if (y < minY || y > maxY || x > maxX) return 0
            var c = 0
            val n = xy.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = xy[2 * i].toDouble(); val yi = xy[2 * i + 1].toDouble()
                val xj = xy[2 * j].toDouble(); val yj = xy[2 * j + 1].toDouble()
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c++
                j = i
            }
            return c
        }
    }

    /** Zone keys that have polygons. */
    val keys: Set<Int> get() = rings.keys

    /**
     * True when the point is within [marginM] metres of an edge of zone [key]'s extent. Distances use
     * GRS80 metres per degree at the point's latitude (a local plane: fine at the sub-kilometre margins
     * used). False for an unknown key.
     */
    fun isNearEdge(key: Int, latDeg: Double, lonDeg: Double, marginM: Double): Boolean {
        val zoneRings = rings[key] ?: return false
        val kx = io.github.pennstatecs.geokit.ellipsoid.Ellipsoid.GRS80.metersPerDegLon(latDeg) / scale
        val ky = io.github.pennstatecs.geokit.ellipsoid.Ellipsoid.GRS80.metersPerDegLat(latDeg) / scale
        val px = lonDeg * scale; val py = latDeg * scale
        val padX = marginM / kx; val padY = marginM / ky
        val m2 = marginM * marginM
        for (r in zoneRings) {
            if (px < r.minX - padX || px > r.maxX + padX || py < r.minY - padY || py > r.maxY + padY) continue
            val xy = r.xy
            val n = xy.size / 2
            var j = n - 1
            for (i in 0 until n) {
                // Segment (j -> i) in metres relative to the point.
                val ax = (xy[2 * j] - px) * kx; val ay = (xy[2 * j + 1] - py) * ky
                val bx = (xy[2 * i] - px) * kx; val by = (xy[2 * i + 1] - py) * ky
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                val cx = ax + t * dx; val cy = ay + t * dy
                if (cx * cx + cy * cy <= m2) return true
                j = i
            }
        }
        return false
    }

    /** True if the point (degrees) lies inside the polygons of zone [key]. */
    fun contains(key: Int, latDeg: Double, lonDeg: Double): Boolean {
        val zoneRings = rings[key] ?: return false
        val x = lonDeg * scale; val y = latDeg * scale
        var c = 0
        for (r in zoneRings) c += r.crossings(x, y)
        return c % 2 == 1
    }

    companion object {
        private const val MAGIC = 0x53505A42   // "SPZB"

        fun read(bytes: ByteArray): SpcsZoneBoundaries {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            require(input.readInt() == MAGIC) { "not an SPCS zone-boundary resource" }
            val version = input.readUnsignedByte()
            require(version == 1) { "unsupported SPCS zone-boundary version $version" }
            val scale = input.readInt()
            val zoneCount = input.readUnsignedShort()
            val out = LinkedHashMap<Int, List<Ring>>(zoneCount * 2)
            repeat(zoneCount) {
                val key = input.readInt()
                val ringCount = input.readInt()
                out[key] = List(ringCount) {
                    val n = input.readInt()
                    val xy = IntArray(2 * n)
                    var x = 0; var y = 0
                    for (i in 0 until n) {
                        x += readZigZag(input); y += readZigZag(input)
                        xy[2 * i] = x; xy[2 * i + 1] = y
                    }
                    Ring(xy)
                }
            }
            return SpcsZoneBoundaries(scale.toDouble(), out)
        }

        private fun readZigZag(input: DataInputStream): Int {
            var v = 0L; var shift = 0
            while (true) {
                val b = input.readUnsignedByte()
                v = v or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return ((v ushr 1) xor -(v and 1)).toInt()
        }

        /** Loads [path] from the classpath; throws [IllegalStateException] if it is missing. */
        fun fromClasspath(path: String): SpcsZoneBoundaries {
            val input = SpcsZoneBoundaries::class.java.getResourceAsStream("/$path")
                ?: throw IllegalStateException(
                    "SPCS zone boundaries: classpath resource $path not found; it ships in the geokit-core jar " +
                        "(libs/geokit-core/src/main/resources/$path) - check packaging excludes"
                )
            return input.use { read(it.readBytes()) }
        }
    }
}
