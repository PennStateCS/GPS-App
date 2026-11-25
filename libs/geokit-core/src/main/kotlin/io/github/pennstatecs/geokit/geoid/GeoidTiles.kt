package io.github.pennstatecs.geokit.geoid

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Tile of a global node-registered geoid grid. Tile ([row], [col]) covers latitudes
 * −90 + row·D … +D and longitudes −180 + col·D … +D, D = [GeoidGridSpec.tileDegrees].
 */
public data class GeoidTileKey(public val row: Int, public val col: Int)

/**
 * Layout of a tiled global grid: nodes every 1/[nodesPerDegree] degree aligned on −90 °/−180 °, grouped in
 * [tileDegrees]-degree tiles of [tileSize] × [tileSize] nodes (edge nodes are stored in both neighbours).
 */
public data class GeoidGridSpec(
    public val model: GeoidModelId,
    public val nodesPerDegree: Int,
    public val tileDegrees: Int,
) {
    init {
        require(nodesPerDegree in 1..120) { "nodesPerDegree $nodesPerDegree out of range" }
        require(tileDegrees in 1..90 && 180 % tileDegrees == 0) { "tileDegrees $tileDegrees must divide 180" }
    }

    /** Nodes per tile side (both edges included). */
    public val tileSize: Int get() = tileDegrees * nodesPerDegree + 1

    /** Tile rows (south → north) and columns (west → east) of the globe. */
    public val tileRows: Int get() = 180 / tileDegrees
    public val tileCols: Int get() = 360 / tileDegrees

    /** Tile holding ([latDeg], [lonDeg]); latitude +90 belongs to the northernmost row, longitude wraps. */
    public fun tileOf(latDeg: Double, lonDeg: Double): GeoidTileKey {
        val row = kotlin.math.floor((latDeg + 90.0) / tileDegrees).toInt().coerceIn(0, tileRows - 1)
        val col = kotlin.math.floor((normalizeLon(lonDeg) + 180.0) / tileDegrees).toInt().coerceIn(0, tileCols - 1)
        return GeoidTileKey(row, col)
    }
}

/**
 * Supplies decoded geoid tiles. The library ships classpath packs ([GeoidTilePack]); an app-side
 * downloader or importer can add more by writing SRGP pack files and opening them with
 * [GeoidTilePack.fromFile] / [GeoidTilePack.fromManifest] (SHA-256 verified), or by implementing this
 * interface. Implementations must be thread-safe.
 */
public interface GeoidTileSource {
    public val spec: GeoidGridSpec

    /** Number of tiles this source can supply. */
    public val tileCount: Int

    /** True if [key] can be loaded from this source (no I/O). */
    public fun contains(key: GeoidTileKey): Boolean

    /**
     * Decoded tile: [GeoidGridSpec.tileSize]² values of N in integer millimetres, row-major south → north,
     * west → east; null if this source has no such tile.
     *
     * @throws java.io.IOException or [IllegalStateException] if the stored tile is unreadable or corrupt.
     */
    public fun loadTile(key: GeoidTileKey): IntArray?
}

/** Several sources of the same grid; the first source holding a tile supplies it. */
public class CompositeGeoidTileSource(private val sources: List<GeoidTileSource>) : GeoidTileSource {
    init {
        require(sources.isNotEmpty()) { "no tile sources" }
        require(sources.all { it.spec == sources[0].spec }) { "tile sources of different grids: ${sources.map { it.spec }}" }
    }

    override val spec: GeoidGridSpec = sources[0].spec
    override val tileCount: Int = run {
        var n = 0
        for (r in 0 until spec.tileRows) for (c in 0 until spec.tileCols) {
            if (sources.any { it.contains(GeoidTileKey(r, c)) }) n++
        }
        n
    }

    override fun contains(key: GeoidTileKey): Boolean = sources.any { it.contains(key) }
    override fun loadTile(key: GeoidTileKey): IntArray? = sources.firstOrNull { it.contains(key) }?.loadTile(key)
}

/**
 * Reader of an "SRGP" tile pack (generator and byte layout: libs/geokit-core/tools/geoid/pack_geoid_tiles.py
 * for v1, pack_geoid2022.py for v2). v2 adds three header fields — [gridRegion], [quantity], [unitsPerValue] —
 * and is otherwise identical; v1 packs read exactly as before (geoid height in integer millimetres).
 * The header and index are validated on open (input is treated as untrusted); tiles are inflated on
 * demand, never all at once. Thread-safe.
 */
public class GeoidTilePack private constructor(
    private val data: RandomData,
    override val spec: GeoidGridSpec,
    /** File name of the original grid, as recorded by the generator. */
    public val sourceFile: String,
    /** SHA-256 of the original grid, as recorded by the generator. */
    public val sourceSha256: String,
    private val index: Map<GeoidTileKey, LongArray>,
    /** SRGP format version (1 or 2). */
    public val formatVersion: Int,
    /** Official grid the tiles were cut from (v2, e.g. GEOID2022 "NA" / "AS" / "GC" / "GL"); "" for v1. */
    public val gridRegion: String,
    /** Stored quantity (v2: GGXF parameter name "geoidHeight" or "geoidVelocity"); v1: "geoidHeight". */
    public val quantity: String,
    /** A stored integer is value × [unitsPerValue] (1000: millimetres; 1 000 000: micrometres/yr); v1: 1000. */
    public val unitsPerValue: Int,
) : GeoidTileSource, java.io.Closeable {

    private val maxAbsStored: Int = if (formatVersion == 1) MAX_ABS_N_MM else MAX_ABS_VALUE * unitsPerValue

    override val tileCount: Int get() = index.size

    /** Releases the file handle of a pack opened with [fromFile] (no-op for in-memory packs). */
    override fun close() {
        (data as? FileData)?.close()
    }

    /** Keys of every tile in the pack. */
    public val keys: Set<GeoidTileKey> get() = index.keys

    override fun contains(key: GeoidTileKey): Boolean = key in index

    override fun loadTile(key: GeoidTileKey): IntArray? {
        val entry = index[key] ?: return null
        val blob = data.read(entry[0], entry[1].toInt())
        val s = spec.tileSize
        val raw = ByteArray(4 + 2 * s * s)
        val inflater = Inflater()
        try {
            inflater.setInput(blob)
            var n = 0
            while (n < raw.size) {
                val k = try { inflater.inflate(raw, n, raw.size - n) } catch (e: DataFormatException) {
                    throw IllegalStateException("geoid tile $key: corrupt data", e)
                }
                if (k == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                n += k
            }
            check(n == raw.size && inflater.finished()) { "geoid tile $key: wrong size ($n of ${raw.size} bytes)" }
        } finally {
            inflater.end()
        }
        val b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        val base = b.int
        val v = IntArray(s * s)
        for (i in 0 until s) for (j in 0 until s) {
            val pred = when {
                i == 0 && j == 0 -> base
                i == 0 -> v[j - 1]
                j == 0 -> v[(i - 1) * s]
                else -> v[i * s + j - 1] + v[(i - 1) * s + j] - v[(i - 1) * s + j - 1]
            }
            val value = pred + b.short
            check(value in -maxAbsStored..maxAbsStored) {
                if (formatVersion == 1) "geoid tile $key: implausible N $value mm" else "geoid tile $key: implausible $quantity $value/$unitsPerValue"
            }
            v[i * s + j] = value
        }
        return v
    }

    /** Byte access to the pack: in memory, or a random-access file. */
    private interface RandomData {
        val size: Long
        fun read(offset: Long, length: Int): ByteArray
    }

    private class MemoryData(private val bytes: ByteArray) : RandomData {
        override val size: Long get() = bytes.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray =
            bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    private class FileData(file: File) : RandomData {
        private val raf = RandomAccessFile(file, "r")
        override val size: Long = raf.length()
        override fun read(offset: Long, length: Int): ByteArray = synchronized(raf) {
            ByteArray(length).also { raf.seek(offset); raf.readFully(it) }
        }

        fun close() = synchronized(raf) { raf.close() }
    }

    public companion object {
        /** |N| on Earth is below ~110 m; anything beyond 200 m is a corrupt tile. */
        private const val MAX_ABS_N_MM = 200_000
        /** v2: |value| above 200 units (m, m/yr) is a corrupt tile. */
        private const val MAX_ABS_VALUE = 200
        private const val MAX_TILE_BYTES = 1 shl 22
        /** v2 [unitsPerValue] limit (keeps 200 × units inside Int). */
        private const val MAX_UNITS_PER_VALUE = 1_000_000

        /**
         * Opens a pack held in memory (e.g. a classpath resource). If [expectedSha256] is given, the bytes
         * must match it.
         *
         * @throws IllegalArgumentException if the digest does not match or the pack is malformed.
         */
        public fun fromBytes(bytes: ByteArray, expectedSha256: String? = null): GeoidTilePack {
            if (expectedSha256 != null) {
                val actual = sha256Hex(bytes)
                require(actual.equals(expectedSha256, ignoreCase = true)) {
                    "geoid pack SHA-256 mismatch: expected $expectedSha256, got $actual"
                }
            }
            return parse(MemoryData(bytes), bytes)
        }

        /**
         * Opens a pack file (an installed/downloaded tile pack) after checking its SHA-256 against
         * [expectedSha256]. Only the header and index are read into memory; tiles are read on demand.
         *
         * @throws IllegalArgumentException if the digest does not match or the pack is malformed.
         */
        public fun fromFile(file: File, expectedSha256: String): GeoidTilePack {
            val actual = file.inputStream().use { input ->
                val md = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(1 shl 16)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
                md.digest().joinToString("") { "%02x".format(it) }
            }
            require(actual.equals(expectedSha256, ignoreCase = true)) {
                "geoid pack ${file.name}: SHA-256 mismatch: expected $expectedSha256, got $actual"
            }
            val data = FileData(file)
            try {
                val headLen = minOf(data.size, MAX_HEADER_BYTES.toLong()).toInt()
                return parse(data, data.read(0, headLen))
            } catch (e: Exception) {
                data.close()
                throw e
            }
        }

        /**
         * Opens every pack listed in a `sha256sum`-style [manifest] ("<64 hex>  <file name>" per line,
         * `#` comments) from [directory]. Names must be plain file names (no path separators or `..`).
         * Every file must exist and match its digest.
         *
         * @throws IllegalArgumentException on a malformed line, unsafe name, digest mismatch or bad pack.
         */
        public fun fromManifest(directory: File, manifest: String): List<GeoidTilePack> =
            manifest.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
                val m = MANIFEST_LINE.matchEntire(line) ?: throw IllegalArgumentException("bad manifest line: $line")
                val (sha, name) = m.destructured
                require(name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == ':' }) {
                    "unsafe file name in manifest: $name"
                }
                fromFile(File(directory, name), sha)
            }.toList()

        private const val MAX_HEADER_BYTES = 16 + 5 * (2 + 65535) + 4 + 4 + 12 * 64800
        private val MANIFEST_LINE = Regex("([0-9a-fA-F]{64})\\s+\\*?(\\S+)")

        private fun parse(data: RandomData, head: ByteArray): GeoidTilePack {
            val b = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN)
            try {
                val magic = ByteArray(4).also { b.get(it) }
                require(String(magic, Charsets.US_ASCII) == "SRGP") { "not an SRGP geoid tile pack" }
                val version = b.int
                require(version == 1 || version == 2) { "unsupported SRGP version $version" }
                val nodesPerDegree = b.int
                val tileDegrees = b.int
                fun utf(): String { val n = b.short.toInt() and 0xFFFF; return String(ByteArray(n).also { b.get(it) }, Charsets.UTF_8) }
                val modelToken = utf()
                val model = requireNotNull(GeoidModelId.fromToken(modelToken)) { "unknown geoid model '$modelToken'" }
                val sourceFile = utf()
                val sourceSha = utf()
                var gridRegion = ""
                var quantity = "geoidHeight"
                var units = 1000
                if (version == 2) {
                    gridRegion = utf()
                    quantity = utf()
                    units = b.int
                    require(gridRegion.isNotEmpty() && quantity.isNotEmpty()) { "SRGP v2 without grid region / quantity" }
                    require(units in 1..MAX_UNITS_PER_VALUE) { "bad SRGP units per value $units" }
                }
                val spec = GeoidGridSpec(model, nodesPerDegree, tileDegrees)
                val count = b.int
                require(count in 0..spec.tileRows * spec.tileCols) { "bad tile count $count" }
                val index = HashMap<GeoidTileKey, LongArray>(count * 2)
                repeat(count) {
                    val key = GeoidTileKey(b.short.toInt(), b.short.toInt())
                    val offset = b.int.toLong() and 0xFFFFFFFFL
                    val length = b.int
                    require(key.row in 0 until spec.tileRows && key.col in 0 until spec.tileCols) { "bad tile $key" }
                    require(length in 1..MAX_TILE_BYTES && offset + length <= data.size) { "tile $key outside the pack" }
                    require(index.put(key, longArrayOf(offset, length.toLong())) == null) { "duplicate tile $key" }
                }
                return GeoidTilePack(data, spec, sourceFile, sourceSha, index, version, gridRegion, quantity, units)
            } catch (e: java.nio.BufferUnderflowException) {
                throw IllegalArgumentException("truncated SRGP geoid tile pack", e)
            }
        }

        internal fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
