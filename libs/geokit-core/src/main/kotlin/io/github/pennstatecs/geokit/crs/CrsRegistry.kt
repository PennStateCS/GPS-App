package io.github.pennstatecs.geokit.crs

import io.github.pennstatecs.geokit.datum.CoordinateEpoch
import io.github.pennstatecs.geokit.datum.FrameTransformationLookup
import io.github.pennstatecs.geokit.datum.FrameTransformationRegistry
import io.github.pennstatecs.geokit.datum.ReferenceFrame
import io.github.pennstatecs.geokit.projection.SpcsZoneLocator
import io.github.pennstatecs.geokit.projection.SpcsZones
import io.github.pennstatecs.geokit.projection.Utm

/**
 * Registry of the coordinate reference systems GeoKit can express positions in, organised in
 * catalogs ([CrsCatalogId]): geographic (one per frame), UTM, SPCS83 and SPCS2022.
 *
 * It **wraps** the existing maths and tables, it does not re-implement them: SPCS83 definitions carry
 * the [io.github.pennstatecs.geokit.projection.SpcsZone] objects of [SpcsZones] (same projection objects,
 * same EPSG codes, which are the persisted settings values), UTM definitions the cached transverse Mercator
 * objects of [Utm]. Results through the registry are therefore bit-identical to the direct paths.
 *
 * SPCS2022 is read from the classpath catalog [SPCS2022_RESOURCE] in the [CrsCatalogCsv] format. The
 * shipped catalog has no zones: SPCS2022 is not in the EPSG dataset or PROJ 9.8.1 (2026-11), so every
 * SPCS2022 lookup is [CrsLookup.Unsupported] with [SPCS2022_UNSUPPORTED].
 *
 * Thread-safe; every catalog is built lazily on first use.
 *
 * @param frameRegistry decides which geographic CRSs are operational (frames reachable from
 *   "WGS84 (ITRF2014)" by a verified transformation).
 * @param spcs2022Source loads the SPCS2022 catalog (tests inject fixtures).
 * @param spcs83Locator locates SPCS83 zones for [atPosition].
 */
public class CrsRegistry(
    private val frameRegistry: FrameTransformationRegistry = FrameTransformationRegistry.DEFAULT,
    private val spcs2022Source: () -> CrsCatalogLoadResult = { CrsCatalogCsv.fromClasspath(SPCS2022_RESOURCE, CrsCatalogId.SPCS2022) },
    private val spcs83Locator: SpcsZoneLocator = SpcsZoneLocator.SPCS83,
) {
    private val geographicCatalog by lazy { buildGeographic() }
    private val utmCatalog by lazy { buildUtm() }
    private val spcs83Catalog by lazy { buildSpcs83() }
    private val spcs2022Catalog by lazy {
        val load = spcs2022Source()
        // Ids already used by the built-in catalogs win: a catalog file can never shadow them.
        val builtIn = (utmCatalog.entries + spcs83Catalog.entries).mapTo(HashSet()) { it.id }
        val (clash, ok) = load.entries.partition { it.id in builtIn }
        CrsCatalog(
            CrsCatalogId.SPCS2022, SPCS2022_DATASET, ok,
            load.problems + clash.map { "id ${it.id} is already used by a built-in catalog; entry ignored" },
            SPCS2022_UNSUPPORTED,
        )
    }
    private val byIdIndex: Map<String, CrsDefinition> by lazy {
        val m = LinkedHashMap<String, CrsDefinition>()
        for (c in catalogs) for (d in c.entries) m.putIfAbsent(d.id.lowercase(), d)
        m
    }

    /** All catalogs, in [CrsCatalogId] order. */
    public val catalogs: List<CrsCatalog> by lazy { CrsCatalogId.entries.map { catalog(it) } }

    /** The catalog [id]. */
    public fun catalog(id: CrsCatalogId): CrsCatalog = when (id) {
        CrsCatalogId.GEOGRAPHIC -> geographicCatalog
        CrsCatalogId.UTM -> utmCatalog
        CrsCatalogId.SPCS83 -> spcs83Catalog
        CrsCatalogId.SPCS2022 -> spcs2022Catalog
    }

    /**
     * Looks up [id]: `EPSG:<code>` (or a bare code, e.g. `6562`), `geographic:<frame id>`,
     * `spcs2022:<zone code>`; case-insensitive. Operational definitions are [CrsLookup.Found];
     * non-operational ones and every SPCS2022 identifier without an operational entry are
     * [CrsLookup.Unsupported]; anything else is [CrsLookup.NotFound].
     */
    public fun byId(id: String): CrsLookup {
        val key = id.trim().lowercase().let { if (it.toIntOrNull() != null) "epsg:$it" else it }
        val d = byIdIndex[key]
        if (d != null) {
            return if (d.operational) CrsLookup.Found(id, d)
            else CrsLookup.Unsupported(id, d.catalog, unsupportedExplanation(d), d)
        }
        if (key.startsWith(CrsCatalogId.SPCS2022.id + ":")) {
            return CrsLookup.Unsupported(id, CrsCatalogId.SPCS2022, SPCS2022_UNSUPPORTED, null)
        }
        return CrsLookup.NotFound(id)
    }

    /**
     * Projected CRS by EPSG code (UTM, SPCS83, SPCS2022), e.g. a persisted SPCS83 zone setting `6562`.
     * Geographic CRSs are looked up by frame ([geographic]); EPSG 4326 is ambiguous there.
     */
    public fun byEpsg(epsg: Int): CrsLookup = byId("EPSG:$epsg")

    /** The geographic CRS of [frame] (operational only if the frame has a verified transformation). */
    public fun geographic(frame: ReferenceFrame): CrsDefinition =
        geographicCatalog.entries.first { it.frame == frame }

    /** The UTM definition of [zone] (1..60) and [hemisphere] ('N' or 'S'). */
    public fun utm(zone: Int, hemisphere: Char): CrsDefinition {
        require(zone in 1..60) { "UTM zone must be between 1 and 60" }
        require(hemisphere == 'N' || hemisphere == 'S') { "Hemisphere must be 'N' or 'S'" }
        return utmCatalog.entries[(if (hemisphere == 'N') 0 else 60) + zone - 1]
    }

    /**
     * Text search for a zone picker: every whitespace-separated term of [query] must occur
     * (case-insensitive) in the id, EPSG code, name, zone code, catalog or frame name. Exact id / EPSG /
     * zone-code matches come first, then names starting with the query, then catalog order.
     * A blank query returns the catalog contents.
     * @param catalog restrict to one catalog (null = all).
     * @param includeNonOperational also return non-operational entries (for "coming soon" lists only).
     * @param limit maximum number of results.
     */
    public fun search(
        query: String,
        catalog: CrsCatalogId? = null,
        includeNonOperational: Boolean = false,
        limit: Int = 50,
    ): List<CrsDefinition> {
        val q = query.trim().lowercase()
        val terms = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val pool = (if (catalog != null) listOf(catalog(catalog)) else catalogs)
            .flatMap { if (includeNonOperational) it.entries else it.operational }
        val hits = pool.filter { d -> terms.all { d.searchText.contains(it) } }
        fun rank(d: CrsDefinition): Int = when {
            q.isEmpty() -> 2
            d.id.lowercase() == q || d.epsg?.toString() == q || d.zoneCode?.lowercase() == q -> 0
            d.name.lowercase().startsWith(q) -> 1
            else -> 2
        }
        return hits.sortedBy { rank(it) }.take(limit.coerceAtLeast(0))
    }

    /**
     * Operational definitions of [catalog] usable at the point (degrees; WGS84 input is fine).
     * - [CrsCatalogId.SPCS83]: [SpcsZoneLocator] candidates, suggested zone first (county-defined
     *   extents, area-of-use boxes as fallback). Use the locator directly for the basis and
     *   near-boundary flag.
     * - [CrsCatalogId.UTM]: the zone containing the longitude, hemisphere from the latitude
     *   (80°S..84°N, the EPSG areas of use).
     * - Geographic and SPCS2022: operational entries whose area of use contains the point.
     */
    public fun atPosition(latDeg: Double, lonDeg: Double, catalog: CrsCatalogId): List<CrsDefinition> {
        if (!latDeg.isFinite() || !lonDeg.isFinite() || latDeg !in -90.0..90.0 || lonDeg !in -180.0..180.0) {
            return emptyList()
        }
        return when (catalog) {
            CrsCatalogId.SPCS83 -> spcs83Locator.locate(latDeg, lonDeg).candidates
                .mapNotNull { z -> byIdIndex["epsg:${z.epsg}"]?.takeIf { it.operational && it.catalog == CrsCatalogId.SPCS83 } }
            CrsCatalogId.UTM -> if (latDeg < -80.0 || latDeg > 84.0) emptyList()
                else listOf(utm(Utm.zoneFor(lonDeg), if (latDeg >= 0) 'N' else 'S'))
            CrsCatalogId.GEOGRAPHIC, CrsCatalogId.SPCS2022 ->
                catalog(catalog).operational.filter { it.areaOfUse.contains(latDeg, lonDeg) }
        }
    }

    private fun unsupportedExplanation(d: CrsDefinition): String = when (d.catalog) {
        CrsCatalogId.SPCS2022 -> "$SPCS2022_UNSUPPORTED Entry: ${d.statusNote ?: "not verified"}."
        else -> d.statusNote ?: "not operational"
    }

    private fun buildGeographic(): CrsCatalog {
        val probe = CoordinateEpoch(2020.0)
        val entries = ReferenceFrame.entries.map { frame ->
            val lookup = frameRegistry.find(ReferenceFrame.WGS84_AS_ITRF2014, frame, probe)
            val operational = lookup is FrameTransformationLookup.Available
            CrsDefinition(
                id = "geographic:${frame.id}", epsg = frame.epsg2d, name = frame.displayName, zoneCode = null,
                catalog = CrsCatalogId.GEOGRAPHIC, kind = CrsKind.GEOGRAPHIC, frame = frame, method = null,
                parameters = emptyMap(), unit = CrsUnit.DEGREE, areaOfUse = geographicArea(frame),
                dataset = EPSG_DATASET, operational = operational,
                statusNote = (lookup as? FrameTransformationLookup.Unavailable)?.let { "no verified transformation from WGS84 (ITRF2014): ${it.explanation}" },
            )
        }
        return CrsCatalog(CrsCatalogId.GEOGRAPHIC, EPSG_DATASET, entries, emptyList(), "no geographic CRS is operational")
    }

    private fun buildUtm(): CrsCatalog {
        val entries = listOf('N', 'S').flatMap { h ->
            (1..60).map { zone ->
                val west = (zone - 1) * 6.0 - 180.0
                val epsg = (if (h == 'N') 32600 else 32700) + zone
                val tm = Utm.projection(zone, h)   // cached in Utm: the same object forward() uses
                CrsDefinition(
                    id = "EPSG:$epsg", epsg = epsg, name = "WGS 84 / UTM zone $zone$h", zoneCode = "$zone$h",
                    catalog = CrsCatalogId.UTM, kind = CrsKind.PROJECTED, frame = ReferenceFrame.WGS84_AS_ITRF2014,
                    method = tm.method, parameters = tm.parameters, unit = CrsUnit.METRE,
                    areaOfUse = if (h == 'N') AreaOfUse(west, 0.0, west + 6.0, 84.0) else AreaOfUse(west, -80.0, west + 6.0, 0.0),
                    dataset = "$EPSG_DATASET; tools/utm/gen_utm_vectors.py", operational = true,
                    statusNote = UTM_NOTE, projectionFactory = { tm },
                )
            }
        }
        return CrsCatalog(CrsCatalogId.UTM, EPSG_DATASET, entries, emptyList(), "no UTM zone is operational")
    }

    private fun buildSpcs83(): CrsCatalog {
        val entries = SpcsZones.all.map { z ->
            val a = z.areaOfUse
            CrsDefinition(
                id = "EPSG:${z.epsg}", epsg = z.epsg, name = z.name, zoneCode = z.zoneCode,
                catalog = CrsCatalogId.SPCS83, kind = CrsKind.PROJECTED, frame = ReferenceFrame.NAD83_2011,
                method = z.projection.method, parameters = z.projection.parameters, unit = CrsUnit.METRE,
                areaOfUse = AreaOfUse(a[0], a[1], a[2], a[3]),
                dataset = "$EPSG_DATASET; tools/coords/gen_spcs.py", operational = true,
                statusNote = null, spcsZone = z, projectionFactory = { z.projection },
            )
        }
        return CrsCatalog(CrsCatalogId.SPCS83, "SPCS83, $EPSG_DATASET", entries, emptyList(), "no SPCS83 zone is operational")
    }

    public companion object {
        /** Provenance of the EPSG-derived definitions (the EPSG database shipped with PROJ 9.8.1). */
        public const val EPSG_DATASET: String = "EPSG v12.029 via PROJ 9.8.1 (pyproj 3.8.0)"

        /** Classpath location of the SPCS2022 catalog (format: [CrsCatalogCsv]). Ships with no zones. */
        public const val SPCS2022_RESOURCE: String = "crs/spcs2022_catalog.csv"

        /** Provenance label of the SPCS2022 catalog. */
        public const val SPCS2022_DATASET: String = "SPCS2022 (NGS); no verified catalog bundled"

        /** Why SPCS2022 cannot be used. */
        public const val SPCS2022_UNSUPPORTED: String =
            "SPCS2022 is not supported: as of 2026-11 its zones are not in the EPSG dataset or PROJ 9.8.1 " +
                "(only the offshore \"NATRF2022 / Gulf\" CRSs), NATRF2022 has no verified transformation in " +
                "GeoKit, and no SPCS2022 zone has verified test vectors."

        private const val UTM_NOTE: String =
            "EPSG WGS 84 / UTM definition; GeoKit treats the coordinates as WGS84 (ITRF2014) like the rest of the app."

        /** EPSG area of use of each frame's geographic CRS (EPSG v12.029). */
        private fun geographicArea(frame: ReferenceFrame): AreaOfUse = when (frame) {
            ReferenceFrame.NAD83_2011 -> AreaOfUse(167.65, 14.92, -63.88, 74.71)
            ReferenceFrame.NATRF2022 -> AreaOfUse(167.65, 23.81, -40.73, 86.46)
            ReferenceFrame.ITRF2014, ReferenceFrame.ITRF2020, ReferenceFrame.WGS84_AS_ITRF2014, ReferenceFrame.WGS84,
            ReferenceFrame.WGS84_G1762, ReferenceFrame.WGS84_G2139, ReferenceFrame.WGS84_G2296 -> AreaOfUse.WORLD
        }

        /** The registry with the bundled catalogs. */
        public val DEFAULT: CrsRegistry by lazy { CrsRegistry() }
    }
}
