package io.github.pennstatecs.geokit.geoid

import io.github.pennstatecs.geokit.status.ProductStatus
import kotlin.math.floor

/**
 * NGS GEOID2022, the geoid of NAPGD2022 — **BETA** (NGS pre-release `beta_v0a`,
 * [ProductStatus.VERIFIED_BETA]): numerically checked against NGS's published test values, but not
 * authoritative; label heights "NAPGD2022 (beta)" with [GeoidModelInfo.dataVersion], never use it as a default.
 *
 * - **Frame.** NGS: geoid heights "with respect to the reference ellipsoid of GRS80 in the ITRF2020 geometric
 *   reference frame", tide-free; the NGS test cases give their inputs in ITRF2020. Look N up at the ITRF2020
 *   position ([GeoidModelInfo.horizontalFrameId] = "itrf2020") and subtract it from an ITRF2020 ellipsoidal
 *   height. This model never transforms coordinates: an app holding NAD83(2011) / NATRF2022 / … positions must
 *   transform to ITRF2020 first (NGS: "Users should not reproject coordinates prior to interpolation" — i.e.
 *   interpolate in plain latitude/longitude).
 * - **Epoch.** [undulation] is the static SGEOID2022 at 2020.0. [undulationAt] adds DGEOID2022:
 *   N(t) = SGEOID + DGEOID·(mjd − 58849)/365.25, exactly the NGS formula with the integer MJD of a calendar day.
 * - **Grids.** Three official 1′ regional grids — North America–Pacific "NA" (0–90 °N, 170 °E–10 °W),
 *   Guam/CNMI "GC" (5–22 °N, 130–170 °E), American Samoa "AS" (22 °S–0°, 172 °E–167 °W) — and the global
 *   15′ DGEOID2022 "GL" grid. Bundled: [BUNDLED_REGIONS] only (SRGP v2 packs, [BUNDLED_PACKS]); a covered point
 *   elsewhere is [GeoidUnavailableReason.TILE_NOT_AVAILABLE], never another model.
 * - **Interpolation** ([GeoidInterpolation.BICUBIC]), as NGS specifies: local 4×4 bicubic (Numerical
 *   Recipes), evaluated as the tensor product of Catmull-Rom cubics over the nodes i−1…i+2, j−1…j+2 of the
 *   cell; window nodes beyond an official grid edge are padded by linear extrapolation (2·edge − inner).
 *   Window nodes with an exactly zero weight (a point on a node row/column) are not read. If any other window
 *   node lies in a tile that is not bundled/installed, the result is TILE_NOT_AVAILABLE (so points within 1′
 *   inside the outer boundary of a pack are unavailable, while points exactly on that boundary are not).
 * - **Region choice** is purely geometric, in the order NA, GC, AS (official grids only touch along shared
 *   edges: NA/GC at 170 °E, NA/AS at the equator); a missing tile in the chosen grid is never filled from
 *   another grid.
 * - **Storage.** N is stored in integer millimetres (rounding ≤ 0.5 mm per node; the bicubic weights' absolute
 *   sum is at most 1.5625, so ≤ 0.79 mm at a point), dN/dt in integer micrometres per year.
 * - The official grids contain no no-data nodes (checked by the generator, which refuses NaN values).
 *
 * Thread-safe. The first lookup loads the packs (blocking I/O); tiles are decoded lazily into bounded LRU
 * caches ([cacheLimits], one per grid).
 *
 * @param loader supplies every pack to use (bundled and installed); each must be a GEOID2022 SRGP v2 pack
 *   of a known grid region. A failure is reported as LOAD_FAILED and retried after 30 s.
 */
public class Geoid2022Model(
    private val cacheLimits: GeoidTileCacheLimits = GeoidTileCacheLimits(),
    loader: () -> List<GeoidTilePack>,
) : GeoidModel {

    /** One official grid: node (i, j) at (south + i/npd, west + j/npd), i < [ni], j < [nj]. */
    private class Grid(
        val region: String,
        val south: Int,
        val west: Int,
        val nodesPerDegree: Int,
        val ni: Int,
        val nj: Int,
        val quantity: String,
        val unitsPerValue: Int,
    ) {
        /** True when the grid closes in longitude (node nj − 1 is node 0 again, e.g. the global "GL" rate grid). */
        val wrapsInLongitude: Boolean get() = nj - 1 == 360 * nodesPerDegree
        fun y(lat: Double): Double = (lat - south) * nodesPerDegree
        fun x(lon: Double): Double {
            val d = (lon - west) % 360.0
            return (if (d < 0) d + 360.0 else d) * nodesPerDegree
        }

        fun covers(lat: Double, lon: Double): Boolean {
            val y = y(lat)
            val x = x(lon)
            return y >= 0.0 && y <= ni - 1.0 && x >= 0.0 && x <= nj - 1.0
        }
    }

    private class Loaded(val grid: Grid, val source: GeoidTileSource, val cache: GeoidTileCache)

    private class Sources(val static: Map<String, Loaded>, val velocity: Loaded?)

    private class TileMissing : RuntimeException(null, null, false, false)

    private val sources = LazyLoad(loader = { open(loader()) })

    override val info: GeoidModelInfo get() = INFO

    /** NOT_LOADED / FAILED, or PARTIAL once loaded (the bundled packs never hold a whole official grid). */
    override val availability: GeoidAvailability
        get() = when {
            sources.isFailed -> GeoidAvailability.FAILED
            !sources.isLoaded -> GeoidAvailability.NOT_LOADED
            else -> GeoidAvailability.PARTIAL
        }

    /** True inside one of the official SGEOID2022 grids (NA, GC, AS); says nothing about bundled tiles. */
    override fun covers(latDeg: Double, lonDeg: Double): Boolean =
        isValidPosition(latDeg, lonDeg) && gridFor(latDeg, lonDeg) != null

    override fun verticalDatumName(latDeg: Double, lonDeg: Double): String = VERTICAL_DATUM

    /** Static SGEOID2022 undulation at epoch 2020.0, at the ITRF2020 position ([latDeg], [lonDeg]). */
    override fun undulation(latDeg: Double, lonDeg: Double): GeoidResult {
        val id = GeoidModelId.GEOID2022
        if (!isValidPosition(latDeg, lonDeg)) {
            return GeoidResult.Unavailable(GeoidUnavailableReason.INVALID_POSITION, id, "invalid position $latDeg, $lonDeg")
        }
        val grid = gridFor(latDeg, lonDeg) ?: return GeoidResult.Unavailable(GeoidUnavailableReason.OUTSIDE_COVERAGE, id,
            "$latDeg, $lonDeg is outside GEOID2022 (NA, GC and AS grids)")
        val src = sources.get().getOrElse {
            return GeoidResult.Unavailable(GeoidUnavailableReason.LOAD_FAILED, id, "GEOID2022 data could not be loaded: ${it.message}")
        }
        val loaded = src.static[grid.region] ?: return GeoidResult.Unavailable(GeoidUnavailableReason.TILE_NOT_AVAILABLE, id,
            "no GEOID2022 ${grid.region} tiles are bundled or installed")
        return when (val r = interpolate(loaded, latDeg, lonDeg)) {
            is Interp.Value -> GeoidResult.Value(r.value, id, VERTICAL_DATUM)
            is Interp.Missing -> GeoidResult.Unavailable(GeoidUnavailableReason.TILE_NOT_AVAILABLE, id,
                "GEOID2022 ${grid.region} tiles around $latDeg, $lonDeg are not bundled or installed")
            is Interp.Failed -> GeoidResult.Unavailable(GeoidUnavailableReason.LOAD_FAILED, id,
                "GEOID2022 ${grid.region} tile unreadable: ${r.message}")
        }
    }

    /** DGEOID2022 geoid rate dN/dt (m/yr) at ([latDeg], [lonDeg]); same interpolation as [undulation]. */
    public fun velocity(latDeg: Double, lonDeg: Double): Geoid2022VelocityResult {
        if (!isValidPosition(latDeg, lonDeg)) {
            return Geoid2022VelocityResult.Unavailable(GeoidUnavailableReason.INVALID_POSITION, "invalid position $latDeg, $lonDeg")
        }
        val src = sources.get().getOrElse {
            return Geoid2022VelocityResult.Unavailable(GeoidUnavailableReason.LOAD_FAILED, "GEOID2022 data could not be loaded: ${it.message}")
        }
        val loaded = src.velocity ?: return Geoid2022VelocityResult.Unavailable(GeoidUnavailableReason.TILE_NOT_AVAILABLE,
            "no DGEOID2022 tiles are bundled or installed")
        return when (val r = interpolate(loaded, latDeg, lonDeg)) {
            is Interp.Value -> Geoid2022VelocityResult.Value(r.value)
            is Interp.Missing -> Geoid2022VelocityResult.Unavailable(GeoidUnavailableReason.TILE_NOT_AVAILABLE,
                "DGEOID2022 tiles around $latDeg, $lonDeg are not bundled or installed")
            is Interp.Failed -> Geoid2022VelocityResult.Unavailable(GeoidUnavailableReason.LOAD_FAILED, "DGEOID2022 tile unreadable: ${r.message}")
        }
    }

    /**
     * Time-dependent GEOID2022 undulation on the calendar day [year]-[month]-[day]:
     * SGEOID + DGEOID·(mjd − 58849)/365.25 (NGS developer guide). Unavailable if either grid is — the static
     * value is never returned in place of the epoch value.
     */
    public fun undulationAt(latDeg: Double, lonDeg: Double, year: Int, month: Int, day: Int): GeoidResult {
        val static = undulation(latDeg, lonDeg)
        if (static !is GeoidResult.Value) return static
        val dt = (modifiedJulianDay(year, month, day) - REFERENCE_MJD) / 365.25
        return when (val v = velocity(latDeg, lonDeg)) {
            is Geoid2022VelocityResult.Value -> static.copy(undulationM = static.undulationM + v.metresPerYear * dt)
            is Geoid2022VelocityResult.Unavailable -> GeoidResult.Unavailable(v.reason, GeoidModelId.GEOID2022, v.detail)
        }
    }

    /** Decoded-tile cache counters summed over all grids (diagnostics and tests). */
    public fun cacheStats(): GeoidTileCacheStats {
        val src = sources.get().getOrNull() ?: return GeoidTileCacheStats(0, 0, 0, 0, 0)
        val all = (src.static.values + listOfNotNull(src.velocity)).map { it.cache.stats() }
        return GeoidTileCacheStats(all.sumOf { it.tiles }, all.sumOf { it.bytes }, all.sumOf { it.hits },
            all.sumOf { it.misses }, all.sumOf { it.evictions })
    }

    /** Drops every decoded tile; they are decoded again when needed. */
    public fun clearCache() {
        val src = sources.get().getOrNull() ?: return
        (src.static.values + listOfNotNull(src.velocity)).forEach { it.cache.clear() }
    }

    private fun gridFor(lat: Double, lon: Double): Grid? = STATIC_GRIDS.firstOrNull { it.covers(lat, lon) }

    private fun open(packs: List<GeoidTilePack>): Sources {
        val byGrid = LinkedHashMap<String, MutableList<GeoidTilePack>>()
        for (p in packs) {
            require(p.spec.model == GeoidModelId.GEOID2022) { "pack ${p.sourceFile} holds ${p.spec.model}, not GEOID2022" }
            require(p.formatVersion == 2) { "GEOID2022 pack ${p.sourceFile} is SRGP v${p.formatVersion}, v2 required" }
            val grid = requireNotNull(GRIDS[p.gridRegion]) { "unknown GEOID2022 grid region '${p.gridRegion}'" }
            require(p.quantity == grid.quantity && p.unitsPerValue == grid.unitsPerValue) {
                "GEOID2022 ${p.gridRegion} pack holds ${p.quantity} ×${p.unitsPerValue}, expected ${grid.quantity} ×${grid.unitsPerValue}"
            }
            require(p.spec.nodesPerDegree == grid.nodesPerDegree) {
                "GEOID2022 ${p.gridRegion} pack has ${p.spec.nodesPerDegree} nodes/degree, expected ${grid.nodesPerDegree}"
            }
            byGrid.getOrPut(p.gridRegion) { mutableListOf() }.add(p)
        }
        fun loaded(region: String): Loaded? = byGrid[region]?.let {
            Loaded(GRIDS.getValue(region), CompositeGeoidTileSource(it), GeoidTileCache(cacheLimits))
        }
        return Sources(STATIC_GRIDS.mapNotNull { g -> loaded(g.region)?.let { g.region to it } }.toMap(), loaded(VELOCITY_GRID.region))
    }

    private sealed interface Interp {
        class Value(val value: Double) : Interp
        object Missing : Interp
        class Failed(val message: String?) : Interp
    }

    private fun interpolate(l: Loaded, lat: Double, lon: Double): Interp {
        val g = l.grid
        val y = g.y(lat).coerceIn(0.0, g.ni - 1.0)
        val x = g.x(lon).coerceIn(0.0, g.nj - 1.0)
        val i = floor(y).toInt().coerceAtMost(g.ni - 2)
        val j = floor(x).toInt().coerceAtMost(g.nj - 2)
        val wy = catmullRom(y - i)
        val wx = catmullRom(x - j)
        return try {
            var total = 0.0
            for (a in 0..3) {
                if (wy[a] == 0.0) continue
                var row = 0.0
                for (b in 0..3) {
                    if (wx[b] == 0.0) continue
                    row += wx[b] * node(l, i - 1 + a, j - 1 + b)
                }
                total += wy[a] * row
            }
            Interp.Value(total / g.unitsPerValue)
        } catch (e: TileMissing) {
            Interp.Missing
        } catch (e: Exception) {
            Interp.Failed(e.message)
        }
    }

    /** Stored integer value of grid node (i, j); nodes one step beyond a grid edge are linearly extrapolated. */
    private fun node(l: Loaded, i: Int, j: Int): Double {
        val g = l.grid
        return when {
            // A grid closing in longitude wraps across its seam instead of extrapolating (0°/360° for GL).
            g.wrapsInLongitude && (j < 0 || j > g.nj - 1) -> node(l, i, Math.floorMod(j, g.nj - 1))
            j < 0 -> 2 * node(l, i, 0) - node(l, i, 1)
            j > g.nj - 1 -> 2 * node(l, i, g.nj - 1) - node(l, i, g.nj - 2)
            i < 0 -> 2 * node(l, 0, j) - node(l, 1, j)
            i > g.ni - 1 -> 2 * node(l, g.ni - 1, j) - node(l, g.ni - 2, j)
            else -> stored(l, i, j).toDouble()
        }
    }

    /** Reads node (i, j) of [l]'s grid from whichever tile stores it (edge nodes are in two tiles). */
    private fun stored(l: Loaded, i: Int, j: Int): Int {
        val src = l.source
        val spec = src.spec
        val per = spec.tileDegrees * spec.nodesPerDegree
        val k = (l.grid.south + 90) * spec.nodesPerDegree + i
        val m = Math.floorMod((l.grid.west + 180) * spec.nodesPerDegree + j, 360 * spec.nodesPerDegree)
        val row = k / per
        val col = m / per
        val ii = k % per
        val jj = m % per
        val rows = if (ii == 0 && row > 0) intArrayOf(row, row - 1) else intArrayOf(row)
        val cols = if (jj == 0) intArrayOf(col, if (col > 0) col - 1 else spec.tileCols - 1) else intArrayOf(col)
        for (r in rows) for (c in cols) {
            if (r !in 0 until spec.tileRows) continue
            val key = GeoidTileKey(r, c)
            if (!src.contains(key)) continue
            val tile = l.cache.getOrLoad(key) { src.loadTile(key) } ?: continue
            val s = spec.tileSize
            check(tile.size == s * s) { "tile $key has ${tile.size} values, expected ${s * s}" }
            val ti = if (r == row) ii else per
            val tj = if (c == col) jj else per
            return tile[ti * s + tj]
        }
        throw TileMissing()
    }

    public companion object {
        /** Vertical datum of heights H = h − N from this model. */
        public const val VERTICAL_DATUM: String = "NAPGD2022"

        /** NGS dataset version. */
        public const val NGS_VERSION: String = "beta_v0a"

        /** MJD of 2020-01-01, the epoch of the static grid (NGS). */
        public const val REFERENCE_MJD: Int = 58849

        /** Frame id positions must be supplied in. */
        public const val HORIZONTAL_FRAME_ID: String = "itrf2020"

        /** Bundled packs: classpath resource → SHA-256 (verified when loaded). */
        public val BUNDLED_PACKS: Map<String, String> = linkedMapOf(
            "geoid/geoid2022_conus.srgp" to "04604fcd637d6a593bb543462b48f11d3ccf40727c568ad6695a45fa5cbbb833",
            "geoid/geoid2022_alaska.srgp" to "c503fe452c346e4ed72c147e0687ff830232e2dff65bad274088470810ded992",
            "geoid/geoid2022_hawaii.srgp" to "2746b768892a76ecafbce0a976d81991f2bc943f5b5ef9a351ccf8f62154dcc7",
            "geoid/geoid2022_prvi.srgp" to "da11edebdd3510068c438fa994da5a749a49094bea758039adf059a5b2f25b72",
            "geoid/geoid2022_guam_cnmi.srgp" to "7ad45c33b4f4b0102b67d072c632bd2519b030f87aefbcedb34ccbec3bb9ba47",
            "geoid/geoid2022_american_samoa.srgp" to "c555825e1375cb7fd6a63994bb3d4f68117f710a2796adc6f3cfe17a258f52e8",
            "geoid/geoid2022_dgeoid.srgp" to "abc3188e3a10c595ce974ebbb044899ea7ab2522431496bad10e963cdad301ff",
        )

        /** SHA-256 of the official NGS grids the packs were cut from (computed on download; NGS publishes none). */
        public val SOURCE_SHA256: Map<String, String> = linkedMapOf(
            "SGEOID2022.NA.N.beta_v0a.bin" to "7b1d43bc6c65b05a07335272dc11de8231c7b31f1975060d1eae4f078bf72386",
            "SGEOID2022.AS.N.beta_v0a.bin" to "89c5c6878451ef396281718e24ff05d37ae86065a8c4fbe985f10e34fbacb66a",
            "SGEOID2022.GC.N.beta_v0a.bin" to "6b336c3ef76f325dd593b953b498779549ae624fa105819e7c16fdb950743599",
            "DGEOID2022.GL.Ndot.beta_v0a.bin" to "735c2c29b07cacd9308a1c9790a3410d28d837d7ae3ca43c79218dd651f33afb",
        )

        /** Extent of the bundled SGEOID2022 tiles (whole 1° tiles). */
        public const val BUNDLED_REGIONS: String =
            "CONUS 24–50 °N, 125–66 °W; Alaska incl. Aleutians 51–72 °N, 172 °E–129 °W (across 180°); " +
                "Hawaii (main islands) 18–23 °N, 161–154 °W; Puerto Rico / USVI 17–19 °N, 68–64 °W; " +
                "Guam / CNMI 13–21 °N, 144–146 °E; American Samoa 15–11 °S, 172–168 °W"

        /** Official grids (NGS download page): region → layout. */
        private val GRIDS: Map<String, Grid> = listOf(
            Grid("NA", 0, 170, 60, 5401, 10801, "geoidHeight", 1000),
            Grid("GC", 5, 130, 60, 1021, 2401, "geoidHeight", 1000),
            Grid("AS", -22, 172, 60, 1321, 1261, "geoidHeight", 1000),
            Grid("GL", -90, 0, 4, 721, 1441, "geoidVelocity", 1_000_000),
        ).associateBy { it.region }

        private val STATIC_GRIDS: List<Grid> = listOf("NA", "GC", "AS").map { GRIDS.getValue(it) }
        private val VELOCITY_GRID: Grid = GRIDS.getValue("GL")

        /** Metadata of GEOID2022 (beta). */
        public val INFO: GeoidModelInfo = GeoidModelInfo(
            id = GeoidModelId.GEOID2022,
            dataVersion = "GEOID2022 $NGS_VERSION (SGEOID2022 epoch 2020.0 + DGEOID2022; SRGP v2, 1° tiles, mm)",
            label = "GEOID2022 (beta)",
            horizontalFrame = "ITRF2020",
            verticalDatum = VERTICAL_DATUM,
            verticalCrsEpsg = null,
            resolutionArcMinutes = 1.0,
            interpolation = GeoidInterpolation.BICUBIC,
            coverage = "NGS grids: North America–Pacific 0–90 °N, 170 °E–10 °W; Guam/CNMI 5–22 °N, 130–170 °E; " +
                "American Samoa 22 °S–0°, 172 °E–167 °W. Bundled tiles: $BUNDLED_REGIONS",
            provenance = GeoidProvenance(
                sourceUrl = "https://beta.ngs.noaa.gov/NAPGD2022/download.html",
                sourceFile = SOURCE_SHA256.keys.joinToString(", "),
                sourceSha256 = null,
                licence = "NOAA/NGS, US Government work (public domain); NGS beta product, not for geodetic control",
                bundledSha256 = BUNDLED_PACKS,
            ),
            isFallback = false,
            status = ProductStatus.VERIFIED_BETA,
            horizontalFrameId = HORIZONTAL_FRAME_ID,
            referenceEpoch = 2020.0,
        )

        /**
         * GEOID2022 from the bundled packs plus [extraPacks] (GEOID2022 SRGP v2 packs the app installed, e.g.
         * from [GeoidTilePack.fromManifest]). Data loads on the first lookup. Create one per process and share it.
         */
        public fun bundled(
            extraPacks: List<GeoidTilePack> = emptyList(),
            cacheLimits: GeoidTileCacheLimits = GeoidTileCacheLimits(),
        ): Geoid2022Model = Geoid2022Model(cacheLimits) { BUNDLED_PACKS.map { (path, sha) -> classpathPack(path, sha) } + extraPacks }

        /**
         * Integer modified Julian day of a calendar date, NGS's algorithm (developer guide):
         * a = (14 − month) / 12, y = year + 4800 − a, m = month + 12a − 3,
         * jdn = day + (153m + 2) / 5 + 365y + y/4 − y/100 + y/400 − 32045, mjd = jdn − 2400001.
         */
        public fun modifiedJulianDay(year: Int, month: Int, day: Int): Int {
            require(year in 1..9999 && runCatching { java.time.LocalDate.of(year, month, day) }.isSuccess) {
                "bad date $year-$month-$day"
            }
            val a = (14 - month) / 12
            val y = year + 4800 - a
            val m = month + 12 * a - 3
            val jdn = day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045
            return jdn - 2400001
        }

        /** Catmull-Rom weights of nodes −1, 0, 1, 2 at fraction [t] ∈ [0, 1] (exactly 0, 1, 0, 0 at t = 0). */
        internal fun catmullRom(t: Double): DoubleArray = doubleArrayOf(
            ((-t + 2.0) * t - 1.0) * t / 2.0,
            ((3.0 * t - 5.0) * t * t + 2.0) / 2.0,
            ((-3.0 * t + 4.0) * t + 1.0) * t / 2.0,
            (t - 1.0) * t * t / 2.0,
        )

        private fun classpathPack(path: String, sha256: String): GeoidTilePack {
            val bytes = Geoid2022Model::class.java.getResourceAsStream("/$path")?.use { it.readBytes() }
                ?: throw IllegalStateException("geoid resource $path not found on the classpath (check packaging excludes)")
            return GeoidTilePack.fromBytes(bytes, sha256)
        }
    }
}

/** Result of a DGEOID2022 rate lookup ([Geoid2022Model.velocity]). */
public sealed interface Geoid2022VelocityResult {
    /** Geoid rate dN/dt in metres per year. */
    public data class Value(public val metresPerYear: Double) : Geoid2022VelocityResult

    /** No rate: [reason], with [detail] for logs. */
    public data class Unavailable(public val reason: GeoidUnavailableReason, public val detail: String) : Geoid2022VelocityResult
}
