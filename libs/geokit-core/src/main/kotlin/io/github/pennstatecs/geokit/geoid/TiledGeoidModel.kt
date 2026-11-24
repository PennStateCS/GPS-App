package io.github.pennstatecs.geokit.geoid

import kotlin.math.abs
import kotlin.math.floor

/**
 * Global geoid model on a node-registered grid stored in tiles ([GeoidTileSource]), e.g. EGM2008 2.5′
 * or EGM96 15′ (see [GlobalGeoidModels]).
 *
 * - The tile source is opened on the first lookup ([loader]); a failure is remembered and reported as
 *   [GeoidUnavailableReason.LOAD_FAILED] / [GeoidAvailability.FAILED].
 * - Tiles are decoded lazily, one at a time, into a bounded LRU cache ([cacheLimits]); the full grid is
 *   never decoded in memory.
 * - Bilinear interpolation over the four nodes surrounding the point. Tiles repeat their edge nodes, so the
 *   four nodes of every cell are in one tile — including the cell between the last node west of
 *   180 ° and the −180 ° column, and cells on tile edges (both neighbours hold identical edge values).
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

    private val source = LazyLoad {
        loader().also {
            require(it.spec.model == info.id) { "tile source holds ${it.spec.model}, not ${info.id}" }
        }
    }
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
        return src.contains(src.spec.tileOf(latDeg, lonDeg))
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
        val key = spec.tileOf(latDeg, lonDeg)
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
        val fc = ((normalizeLon(lonDeg) - west) * spec.nodesPerDegree).coerceIn(0.0, (s - 1).toDouble())
        val i = floor(fr).toInt().coerceAtMost(s - 2)
        val j = floor(fc).toInt().coerceAtMost(s - 2)
        val t = fr - i
        val u = fc - j
        fun v(ii: Int, jj: Int) = tile[ii * s + jj] / 1000.0
        val sw = v(i, j); val se = v(i, j + 1); val nw = v(i + 1, j); val ne = v(i + 1, j + 1)
        val n = (sw * (1 - u) + se * u) * (1 - t) + (nw * (1 - u) + ne * u) * t
        return GeoidResult.Value(n, id, verticalDatumName(latDeg, lonDeg))
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
