package app.surrealar.ui.settings.categories

import app.surrealar.coords.Nsrs2022Capabilities
import io.github.pennstatecs.geokit.crs.CrsCatalogId
import io.github.pennstatecs.geokit.crs.CrsDefinition
import io.github.pennstatecs.geokit.crs.CrsRegistry
import io.github.pennstatecs.geokit.crs.CrsUnit
import io.github.pennstatecs.geokit.datum.ReferenceFrame
import io.github.pennstatecs.geokit.projection.ProjectionMethod
import io.github.pennstatecs.geokit.projection.SpcsZone
import java.util.Locale

/**
 * One entry of the State Plane zone selector.
 * @property label text in the dropdown (unique): SPCS83 zones keep their historical label ("Pennsylvania North
 *   (3701)"); SPCS2022 zones add frame, epoch, projection, unit and a beta badge.
 * @property spcs83 the SPCS83 zone (stored as its EPSG code), null for SPCS2022.
 * @property definition the SPCS2022 registry entry, null for SPCS83.
 * @property selectable false for an SPCS2022 zone while the app cannot output it (see [ZoneSelectorOptions.SPCS2022_OUTPUT_SUPPORTED]).
 */
data class ZoneOption(
    val label: String,
    val catalog: CrsCatalogId,
    val zoneName: String,
    val frameLabel: String,
    val epochLabel: String?,
    val projection: String,
    val unit: String,
    val beta: Boolean,
    val selectable: Boolean,
    val spcs83: SpcsZone? = null,
    val definition: CrsDefinition? = null,
)

/**
 * The State Plane zone selector's entries, driven by geokit's [CrsRegistry] operational status (pure):
 * - SPCS83: exactly the zones [SpcsZoneHints.searchZones] lists (unchanged labels, NAD83(2011)).
 * - SPCS2022: only with the NSRS 2022 beta preview ON, only entries the registry reports operational (their
 *   2022 frame has a verified transformation: none in the shipped registry, so nothing is listed today), and
 *   only on a 2022 frame (NATRF2022, PATRF2022, CATRF2022, MATRF2022: never NAD83). Each is labelled with its
 *   own frame, the frame's epoch, projection, native unit and "β beta".
 *
 * Phase 4 WP X: the app stores the chosen zone (CoordinateSystemSettings.spcs2022Zone, the registry id) and
 * converts into it ([SPCS2022_OUTPUT_SUPPORTED] is true), so listed SPCS2022 zones are selectable. The app
 * passes the beta CRS registry (Nsrs2022Registries.betaCrs) with the preview ON; with geokit's default registry
 * nothing is operational, so nothing is listed.
 */
internal object ZoneSelectorOptions {
    /** The app can store and output an SPCS2022 zone (Phase 4 WP X). */
    const val SPCS2022_OUTPUT_SUPPORTED = true

    /** Most alternatives named in a suggestion hint. */
    private const val MAX_SUGGESTED = 4

    private const val SEARCH_LIMIT = 2000
    private val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")

    /** The SPCS83 entries (labels as before). */
    fun spcs83(query: String, registry: CrsRegistry = CrsRegistry.DEFAULT): List<ZoneOption> =
        SpcsZoneHints.searchZones(query, registry).map { z ->
            ZoneOption(z.label, CrsCatalogId.SPCS83, z.label, ReferenceFrame.NAD83_2011.displayName, null,
                "SPCS83", "m", beta = false, selectable = true, spcs83 = z)
        }

    /**
     * The SPCS2022 entries for [query] (empty with the preview OFF or while no zone is operational). Touches
     * the SPCS2022 catalog (a 953-row parse on first use): call off the main thread.
     */
    fun spcs2022(
        query: String, preview: Boolean, registry: CrsRegistry = CrsRegistry.DEFAULT,
        outputSupported: Boolean = SPCS2022_OUTPUT_SUPPORTED,
    ): List<ZoneOption> {
        if (!preview) return emptyList()
        val catalog = registry.catalog(CrsCatalogId.SPCS2022)
        if (catalog.operational.isEmpty()) return emptyList()
        val q = query.replace(NON_ALPHANUMERIC, " ").trim()
        val defs = if (q.isEmpty()) catalog.operational.sortedBy { it.label }
            else registry.search(q, CrsCatalogId.SPCS2022, includeNonOperational = false, limit = SEARCH_LIMIT, includeBeta = true)
        return defs.filter { it.operational && it.frameId in Nsrs2022Capabilities.FRAME_IDS }.map { d -> option(d, outputSupported) }
    }

    /** The SPCS2022 entry for a stored zone id ("spcs2022:<code>"), null if the registry has no operational zone with it. */
    fun spcs2022ById(id: String?, registry: CrsRegistry): ZoneOption? =
        app.surrealar.coords.Nsrs2022Registries.spcs2022Zone(id, registry)?.let { option(it, SPCS2022_OUTPUT_SUPPORTED) }

    /**
     * SPCS2022 zones possible at a position (degrees; the live WGS84 fix is fine: 1-2 m frame differences are far
     * below the zone-line generalisation) from [CrsRegistry.locateSpcs2022]: every candidate, smallest first, and
     * whether the answer is ambiguous (several zones, near a zone line, or envelopes only). Never one confident
     * answer from envelopes: the user chooses. Off the main thread (parses the catalog and polygons on first use).
     */
    fun locate(latDeg: Double, lonDeg: Double, registry: CrsRegistry): Pair<List<ZoneOption>, io.github.pennstatecs.geokit.crs.CrsZoneLocation> {
        val loc = registry.locateSpcs2022(latDeg, lonDeg, includeBeta = true)
        val options = loc.candidates.filter { it.operational && it.frameId in Nsrs2022Capabilities.FRAME_IDS }
            .map { option(it, SPCS2022_OUTPUT_SUPPORTED) }
        return options to loc
    }

    /** Hint naming the SPCS2022 zones possible at the live position (or null when there are none). Pure. */
    fun suggestionHint(options: List<ZoneOption>, location: io.github.pennstatecs.geokit.crs.CrsZoneLocation): String? {
        if (options.isEmpty()) return null
        val names = options.take(MAX_SUGGESTED).joinToString(", ") { it.zoneName } + if (options.size > MAX_SUGGESTED) ", …" else ""
        val why = when {
            location.basis == io.github.pennstatecs.geokit.crs.CrsLocationBasis.AREA_OF_USE_BOX -> " Zone limits are approximate here (envelopes)."
            location.nearBoundary -> " You are near a zone line."
            else -> ""
        }
        return if (!location.isAmbiguous) "SPCS2022 zone at your position: $names. Check it before use."
        else "SPCS2022 zones possible at your position: $names.$why Choose the zone your project uses."
    }

    /** SPCS83 entries followed by the SPCS2022 ones. */
    fun options(
        query: String, preview: Boolean, registry: CrsRegistry = CrsRegistry.DEFAULT,
        outputSupported: Boolean = SPCS2022_OUTPUT_SUPPORTED,
    ): List<ZoneOption> = spcs83(query, registry) + spcs2022(query, preview, registry, outputSupported)

    private fun option(d: CrsDefinition, outputSupported: Boolean): ZoneOption {
        val frame = ReferenceFrame.fromId(d.frameId)
        val frameLabel = frame?.displayName ?: d.frameId.uppercase(Locale.US)
        val epoch = frame?.referenceEpoch?.let { String.format(Locale.US, "epoch %.1f", it) }
        val projection = d.method?.shortLabel() ?: "?"
        val unit = if (d.unit == CrsUnit.METRE) "m" else d.unit.token
        val beta = d.isBeta
        val label = listOfNotNull("SPCS2022 ${d.label}", listOfNotNull(frameLabel, epoch).joinToString(" "), projection, unit,
            if (beta) "β beta" else null).joinToString(" · ")
        return ZoneOption(label, CrsCatalogId.SPCS2022, d.label, frameLabel, epoch, projection, unit, beta,
            selectable = outputSupported && d.operational, definition = d)
    }

    private fun ProjectionMethod.shortLabel(): String = when (this) {
        ProjectionMethod.TRANSVERSE_MERCATOR -> "TM"
        ProjectionMethod.LAMBERT_CONIC_CONFORMAL_1SP -> "LCC 1SP"
        ProjectionMethod.LAMBERT_CONIC_CONFORMAL_2SP -> "LCC 2SP"
        ProjectionMethod.HOTINE_OBLIQUE_MERCATOR_A, ProjectionMethod.HOTINE_OBLIQUE_MERCATOR_B -> "Oblique Mercator"
    }
}
