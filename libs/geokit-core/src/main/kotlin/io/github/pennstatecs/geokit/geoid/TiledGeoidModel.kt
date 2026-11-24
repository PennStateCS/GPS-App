package io.github.pennstatecs.geokit.geoid

import kotlin.math.abs
import kotlin.math.floor

/**
 * Global geoid model on a node-registered grid stored in tiles ([GeoidTileSource]), e.g. EGM2008 2.5′
 * or EGM96 15′ (see [GlobalGeoidModels]).
 *
 * - The tile source is opened on the first lookup ([loader]); a failure is remembered and reported as
 *   [GeoidUnavailableReason.LOAD_FAILED] / [GeoidAvailability.FAILED], and the source is opened again on a
 *   lookup after a 30 s back-off.
 * - Tiles are decoded lazily, one at a time, into a bounded LRU cache ([cacheLimits]); the full grid is
 *   never decoded in memory.
 * - Bilinear interpolation over the four nodes surrounding the point. Tiles repeat their edge nodes, so the
 *   four nodes of every cell are in one tile — including the cell between the last node west of
 *   180 ° and the −180 ° column, and cells on tile edges (both neighbours hold identical edge values).
 * - A point exactly on the south or west edge of a tile that is in no source is read from the neighbour
 *   tile that holds it on its north / east edge (also across ±180 °), so the outer edge nodes of a bundled
 *   region (e.g. 50 °N for CONUS) are available. The value is that tile's edge node, the same number the
 *   missing tile would have stored.
 * - A point whose tile is in no source returns [GeoidUnavailableReason.TILE_NOT_AVAILABLE] — never a
 *   value from another model.
 *
 * Thread-safe.
 */
public class TiledGeoidModel(
    override val info: GeoidModelInfo,
    cacheLimits: GeoidTileCacheLimits = GeoidTileCacheLimits(),
    loader: () -> GeoidTileSource,
) : GeoidModel {

    private val source = LazyLoad(loader = {
        loader().also {
            require(it.spec.model == info.id) { "tile source holds ${it.spec.model}, not ${info.id}" }
        }
    })
    private val cache = GeoidTileCache(cacheLimits)

    override val availability: GeoidAvailability
        get() = when {
            source.isFailed -> GeoidAvailability.FAILED
            !source.isLoaded -> GeoidAvailability.NOT_LOADED
            else -> source.get().getOrThrow().let {
                if (it.tileCount == it.spec.tileRows * it.spec.tileCols) GeoidAvailability.AVAILABLE else GeoidAvailability.PARTIAL
            }
        }

    /** Global models cover every valid position (tile presence is separate: [isTileAvailable]). */
    override fun covers(latDeg: Double, lonDeg: Double): Boolean = isValidPosition(latDeg, lonDeg)

    /** True if the tile holding the point is in the source (opens the source if needed). */
    public fun isTileAvailable(latDeg: Double, lonDeg: Double): Boolean {
        if (!isValidPosition(latDeg, lonDeg)) return false
        val src = source.get().getOrElse { return false }
        return src.contains(tileFor(src, latDeg, lonDeg))
    }

    override fun verticalDatumName(latDeg: Double, lonDeg: Double): String = info.label

    override fun undulation(latDeg: Double, lonDeg: Double): GeoidResult {
        val id = info.id
        if (!isValidPosition(latDeg, lonDeg)) {
            return GeoidResult.Unavailable(GeoidUnavailableReason.INVALID_POSITION, id, "invalid position $latDeg, $lonDeg")
        }
        val src = source.get().getOrElse {
            return GeoidResult.Unavailable(GeoidUnavailableReason.LOAD_FAILED, id, "${info.label} data could not be loaded: ${it.message}")
        }
        val spec = src.spec
        val key = tileFor(src, latDeg, lonDeg)
        val tile = try {
            cache.getOrLoad(key) { src.loadTile(key) }
        } catch (e: Exception) {
            return GeoidResult.Unavailable(GeoidUnavailableReason.LOAD_FAILED, id, "${info.label} tile ${describe(spec, key)} unreadable: ${e.message}")
        } ?: return GeoidResult.Unavailable(GeoidUnavailableReason.TILE_NOT_AVAILABLE, id,
            "${info.label} tile ${describe(spec, key)} is not bundled or installed")
        val s = spec.tileSize
        check(tile.size == s * s) { "tile $key has ${tile.size} values, expected ${s * s}" }

        val south = -90.0 + key.row * spec.tileDegrees
        val west = -180.0 + key.col * spec.tileDegrees
        val fr = ((latDeg - south) * spec.nodesPerDegree).coerceIn(0.0, (s - 1).toDouble())
        // A point on −180 ° read from the tile east of +180 ° (see tileFor) sits 360 ° west of that tile.
        val dLon = (normalizeLon(lonDeg) - west).let { if (it < -180.0) it + 360.0 else it }
        val fc = (dLon * spec.nodesPerDegree).coerceIn(0.0, (s - 1).toDouble())
        val i = floor(fr).toInt().coerceAtMost(s - 2)
        val j = floor(fc).toInt().coerceAtMost(s - 2)
        val t = fr - i
        val u = fc - j
        fun v(ii: Int, jj: Int) = tile[ii * s + jj] / 1000.0
        val sw = v(i, j); val se = v(i, j + 1); val nw = v(i + 1, j); val ne = v(i + 1, j + 1)
        val n = (sw * (1 - u) + se * u) * (1 - t) + (nw * (1 - u) + ne * u) * t
        return GeoidResult.Value(n, id, verticalDatumName(latDeg, lonDeg))
    }

    /**
     * The tile to read ([lat], [lon]) from: [GeoidGridSpec.tileOf], or — when that tile is in no source and
     * the point lies on its south and/or west edge — the neighbour tile that stores the point on its north /
     * east edge (tiles store both edges, so the node values are identical). Westward wraps across ±180 °.
     */
    private fun tileFor(src: GeoidTileSource, lat: Double, lon: Double): GeoidTileKey {
        val spec = src.spec
        val key = spec.tileOf(lat, lon)
        if (src.contains(key)) return key
        val onSouth = key.row > 0 && lat <= -90.0 + key.row * spec.tileDegrees
        val onWest = normalizeLon(lon) <= -180.0 + key.col * spec.tileDegrees
        if (!onSouth && !onWest) return key
        val westCol = if (key.col > 0) key.col - 1 else spec.tileCols - 1
        val candidates = buildList {
            if (onSouth) add(GeoidTileKey(key.row - 1, key.col))
            if (onWest) add(GeoidTileKey(key.row, westCol))
            if (onSouth && onWest) add(GeoidTileKey(key.row - 1, westCol))
        }
        return candidates.firstOrNull { src.contains(it) } ?: key
    }

    /** Decoded-tile cache counters (for diagnostics and tests). */
    public fun cacheStats(): GeoidTileCacheStats = cache.stats()

    /** Drops every decoded tile (e.g. on low memory); they are decoded again when needed. */
    public fun clearCache(): Unit = cache.clear()

    private fun describe(spec: GeoidGridSpec, key: GeoidTileKey): String {
        val s = -90 + key.row * spec.tileDegrees
        val w = -180 + key.col * spec.tileDegrees
        return "${abs(s)}°${if (s < 0) "S" else "N"} ${abs(w)}°${if (w < 0) "W" else "E"} (${spec.tileDegrees}°)"
    }
}
