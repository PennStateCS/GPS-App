package app.surrealar.coords

import io.github.pennstatecs.geokit.crs.CrsCatalogId
import io.github.pennstatecs.geokit.crs.CrsRegistry
import io.github.pennstatecs.geokit.datum.CoordinateEpoch
import io.github.pennstatecs.geokit.datum.FrameTransformationLookup
import io.github.pennstatecs.geokit.datum.FrameTransformationRegistry
import io.github.pennstatecs.geokit.datum.PlateFrame2022
import io.github.pennstatecs.geokit.datum.ReferenceFrame
import io.github.pennstatecs.geokit.geoid.Geoid2022Model
import io.github.pennstatecs.geokit.status.ProductStatus

/** How the app presents a coordinate product (from geokit [ProductStatus]). */
enum class CapabilityUiState(val label: String) {
    /** Established workflow: offered as before. */
    LEGACY("available"),

    /** NGS beta, verified against NGS beta values: offered only with the beta preview ON, always labelled beta. */
    BETA("beta"),

    /** Not verified or no coverage: listed for information only, never selectable. */
    UNAVAILABLE("unavailable — awaiting NGS verification"),
}

/** Product family of a [Nsrs2022Capability]. */
enum class CapabilityKind { GEOID, FRAME, SPCS, OTHER }

/** The UI state of a geokit [ProductStatus]. */
fun ProductStatus.toUiState(): CapabilityUiState = when (this) {
    ProductStatus.VERIFIED_LEGACY -> CapabilityUiState.LEGACY
    ProductStatus.VERIFIED_BETA -> CapabilityUiState.BETA
    ProductStatus.UNAVAILABLE -> CapabilityUiState.UNAVAILABLE
}

/**
 * One modernized-NSRS product as the beta preview lists it.
 * @property name e.g. "GEOID2022 heights (NAPGD2022)", "NATRF2022", "SPCS2022".
 * @property state from the product's geokit status.
 * @property selectable true only for a BETA product the app offers as an option (never for UNAVAILABLE).
 * @property reason why it is unavailable, as geokit's registries state it (null when usable).
 * @property note short usage note (dataset version, where to choose it), null if none.
 * @property kind which product family it is (for the concise one-line reason).
 */
data class Nsrs2022Capability(
    val name: String,
    val state: CapabilityUiState,
    val selectable: Boolean,
    val reason: String?,
    val note: String? = null,
    val kind: CapabilityKind = CapabilityKind.OTHER,
) {
    init { require(!(selectable && state == CapabilityUiState.UNAVAILABLE)) { "$name: unavailable products are never selectable" } }
}

/**
 * The Phase 3 "Modernized NSRS 2022 (beta preview)" capability model: which NGS 2022 products the app can
 * offer, read from geokit's status and registries (never hard-coded as available):
 * - GEOID2022 heights: [Geoid2022Model.INFO] status (VERIFIED_BETA, dataset beta_v0a) → the height option
 *   "Orthometric height H (NAPGD2022, GEOID2022 beta)" with the preview ON;
 * - NATRF2022 / PATRF2022 / CATRF2022 / MATRF2022: the verified frame registry's lookup from the app's
 *   stored frame (UNAVAILABLE today, NGS reference coordinates missing) → listed, not selectable;
 * - SPCS2022: the CRS registry's catalog (953 NGS beta zones, 0 operational while their frames are
 *   unreachable) → listed, not selectable.
 */
object Nsrs2022Capabilities {
    const val PREVIEW_TITLE: String = "Modernized NSRS 2022 (beta preview)"
    const val PREVIEW_WARNING: String = "Preliminary NGS beta products, not for authoritative survey results."

    /** Frame ids of the four NSRS 2022 plate-fixed frames, in NGS order. */
    val FRAME_IDS: List<String> = listOf("natrf2022", "patrf2022", "catrf2022", "matrf2022")

    fun all(
        frames: FrameTransformationRegistry = FrameTransformationRegistry.DEFAULT,
        crs: CrsRegistry = CrsRegistry.DEFAULT,
    ): List<Nsrs2022Capability> = listOf(geoid2022()) + frames(frames) + spcs2022(crs)

    fun geoid2022(): Nsrs2022Capability {
        val state = Geoid2022Model.INFO.status.toUiState()
        return Nsrs2022Capability(
            name = "GEOID2022 heights (NAPGD2022)", state = state,
            selectable = state == CapabilityUiState.BETA,
            reason = if (state == CapabilityUiState.UNAVAILABLE) "GEOID2022 is not verified in GeoKit" else null,
            kind = CapabilityKind.GEOID,
            note = "NGS ${Geoid2022Model.NGS_VERSION}; choose “${app.surrealar.settings.model.VerticalReference.GEOID2022.label}” " +
                "under Height. Data bundled for the lower 48, Alaska, Hawaii, Puerto Rico / Virgin Islands, Guam / CNMI and American Samoa.",
        )
    }

    /** The four 2022 frames as reachable (or not) from the app's stored frame, WGS84 (ITRF2014). */
    fun frames(registry: FrameTransformationRegistry = FrameTransformationRegistry.DEFAULT): List<Nsrs2022Capability> =
        FRAME_IDS.map { id ->
            val lookup = registry.find(ReferenceFrame.WGS84_AS_ITRF2014.id, id, CoordinateEpoch(PlateFrame2022.REFERENCE_EPOCH))
            val name = PlateFrame2022.fromId(id)?.displayName ?: id.uppercase()
            Nsrs2022Capability(
                name = name,
                state = lookup.status.toUiState(),
                // The app has no 2022-frame output yet: never selectable, even once a frame becomes verified.
                selectable = false,
                reason = (lookup as? FrameTransformationLookup.Unavailable)?.explanation
                    ?: "verified in GeoKit, but not offered as an output frame in this app version",
                kind = CapabilityKind.FRAME,
            )
        }

    /** SPCS2022 from the CRS registry: operational zones only once their frames are reachable. */
    fun spcs2022(registry: CrsRegistry = CrsRegistry.DEFAULT): Nsrs2022Capability {
        val c = registry.catalog(CrsCatalogId.SPCS2022)
        val state = if (c.operational.isEmpty()) CapabilityUiState.UNAVAILABLE else CapabilityUiState.BETA
        return Nsrs2022Capability(
            name = "SPCS2022 (${c.entries.size} NGS beta zones)",
            state = state,
            // Not offered as a State Plane option in this app version (the zone picker stays SPCS83).
            selectable = false,
            reason = c.explanation ?: "${c.operational.size} zones verified in GeoKit, not offered in this app version",
            kind = CapabilityKind.SPCS,
        )
    }

    /** Longest concise reason (one line on the 280–600 dp settings card). */
    const val MAX_SHORT_REASON = 60

    /**
     * Concise one-line reason why [c] is not selectable (the full registry wording stays in [Nsrs2022Capability.reason],
     * shown on demand); null for a selectable product.
     */
    fun shortReason(c: Nsrs2022Capability): String? {
        if (c.selectable) return null
        val unavailable = c.state == CapabilityUiState.UNAVAILABLE
        return when (c.kind) {
            CapabilityKind.FRAME -> if (unavailable) "No NGS-verified transformation yet" else "Verified, not yet an output frame here"
            CapabilityKind.SPCS -> if (unavailable) "Needs a verified 2022 frame; not in EPSG/PROJ" else "Verified zones, not yet offered here"
            CapabilityKind.GEOID -> if (unavailable) "Not verified in GeoKit" else "Not offered in this app version"
            CapabilityKind.OTHER -> c.reason?.let { firstClause(it) } ?: "Not offered in this app version"
        }
    }

    /** First sentence of [text], cut to [MAX_SHORT_REASON] characters with an ellipsis. */
    internal fun firstClause(text: String): String {
        val first = text.substringBefore(". ").substringBefore(": ").trim().removeSuffix(".")
        return if (first.length <= MAX_SHORT_REASON) first else first.take(MAX_SHORT_REASON - 1).trimEnd() + "…"
    }

    /**
     * Note under the Height field: the beta height in use (with dataset version and caution), or a beta
     * height that is saved but not used because the preview is OFF (the stored choice is kept, never
     * rewritten, and the existing default is shown). Null otherwise (no change from before).
     */
    fun verticalNote(s: app.surrealar.settings.model.CoordinateSystemSettings): String? = when {
        s.betaVerticalSuspended ->
            "${s.vertical.label} is saved, but the NSRS 2022 beta preview is off: showing the default height " +
                "(${s.effectiveVertical.label}). Turn the preview on to use it again."
        s.effectiveVertical == app.surrealar.settings.model.VerticalReference.GEOID2022 ->
            "Beta: ${Geoid2022Labels.MODEL}, NAPGD2022. Position (ITRF2020) and N at each point's coordinate epoch. " +
                Geoid2022Labels.CAUTION
        else -> null
    }

    /**
     * Text for the preview section on Settings → Coordinates: one line per product, then the distinct
     * reasons (registry wording, frame name prefix removed so a shared reason shows once).
     */
    fun statusText(items: List<Nsrs2022Capability> = all()): String {
        val lines = items.map { c ->
            "• ${c.name}: ${c.state.label}" + (c.note?.takeIf { c.selectable }?.let { ". $it" } ?: "")
        }
        val reasons = items.filter { !it.selectable }.mapNotNull { c ->
            c.reason?.removePrefix("${c.name}: ")?.replaceFirstChar { it.uppercase() }
        }.distinct()
        return (lines + reasons.map { "Why: $it" }).joinToString("\n")
    }
}
