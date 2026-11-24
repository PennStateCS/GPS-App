package io.github.pennstatecs.geokit.geoid

/**
 * Limits of a [TiledGeoidModel]'s decoded-tile cache. The least recently used tile is evicted when
 * either limit is exceeded; the tile just loaded is always kept, so a [maxBytes] below one tile still
 * works (one tile in memory).
 *
 * Defaults: 128 tiles / 1 MiB — an EGM2008 1° tile is 25 × 25 ints = 2.5 KB, so the default holds a
 * ~10° × 10° work area; an EGM96 5° tile is 21 × 21 ints = 1.7 KB.
 */
public data class GeoidTileCacheLimits(
    public val maxTiles: Int = 128,
    public val maxBytes: Long = 1L shl 20,
) {
    init {
        require(maxTiles >= 1) { "maxTiles must be >= 1" }
        require(maxBytes >= 1) { "maxBytes must be >= 1" }
    }
}

/** Snapshot of cache counters. */
public data class GeoidTileCacheStats(
    public val tiles: Int,
    public val bytes: Long,
    public val hits: Long,
    public val misses: Long,
    public val evictions: Long,
)

/** Thread-safe LRU of decoded tiles. Decoding happens outside the lock; a racing duplicate is dropped. */
internal class GeoidTileCache(private val limits: GeoidTileCacheLimits) {
    private val map = LinkedHashMap<GeoidTileKey, IntArray>(16, 0.75f, true)
    private var bytes = 0L
    private var hits = 0L
    private var misses = 0L
    private var evictions = 0L

    fun getOrLoad(key: GeoidTileKey, load: () -> IntArray?): IntArray? {
        synchronized(map) {
            map[key]?.let { hits++; return it }
            misses++
        }
        val tile = load() ?: return null
        synchronized(map) {
            map[key]?.let { return it }
            map[key] = tile
            bytes += 4L * tile.size
            val it = map.entries.iterator()
            while (map.size > 1 && (map.size > limits.maxTiles || bytes > limits.maxBytes)) {
                val eldest = it.next()
                if (eldest.key == key) continue
                bytes -= 4L * eldest.value.size
                it.remove()
                evictions++
            }
            return tile
        }
    }

    fun stats(): GeoidTileCacheStats = synchronized(map) { GeoidTileCacheStats(map.size, bytes, hits, misses, evictions) }

    fun clear(): Unit = synchronized(map) { map.clear(); bytes = 0 }
}
