package app.surrealar.coords

import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.VerticalReference

/** The three groups Settings → Coordinates lists products in (beta preview ON). */
enum class CoordinateSystemGroup(val title: String, val subtitle: String) {
    VERIFIED("Verified systems", "Established workflows, chosen in the fields above."),
    BETA("Verified NGS beta", "Preliminary NGS products: opt-in, always labelled beta, not for authoritative results."),
    UNSUPPORTED("Unsupported / not yet verified", "Listed for information only, not selectable."),
}

/**
 * One line of a [CoordinateSystemGroup].
 * @property name product or family, e.g. "Heights", "GEOID2022 heights (NAPGD2022)", "NATRF2022".
 * @property value what is offered (verified group: the options) or the product state label.
 * @property selectable true for something the user can choose (never in [CoordinateSystemGroup.UNSUPPORTED]).
 * @property shortReason concise one-line "why" for an unsupported item.
 * @property fullReason the registry's full wording (shown on demand: info dialog).
 * @property beta always labelled with a beta badge.
 */
data class CoordinateSystemGroupItem(
    val name: String,
    val group: CoordinateSystemGroup,
    val value: String,
    val selectable: Boolean,
    val shortReason: String? = null,
    val fullReason: String? = null,
    val beta: Boolean = false,
) {
    init {
        require(!(selectable && group == CoordinateSystemGroup.UNSUPPORTED)) { "$name: unsupported items are never selectable" }
    }
}

/**
 * Builds the grouped product list for Settings → Coordinates from the app's verified options and the
 * [Nsrs2022Capabilities] model (geokit status, never hard-coded as available). Pure; the capability list is
 * computed off the main thread by the caller (it parses the SPCS2022 catalog).
 */
object CoordinateSystemGroups {

    /** The verified (legacy) options the app offers, from the settings enums (beta heights excluded). */
    fun verified(): List<CoordinateSystemGroupItem> = listOf(
        CoordinateSystemGroupItem("Systems", CoordinateSystemGroup.VERIFIED,
            HorizontalSystem.entries.joinToString(", ") { it.label }, selectable = true),
        CoordinateSystemGroupItem("Datums", CoordinateSystemGroup.VERIFIED,
            OutputDatum.entries.joinToString(", ") { it.label }, selectable = true),
        CoordinateSystemGroupItem("Heights", CoordinateSystemGroup.VERIFIED,
            VerticalReference.entries.filter { !it.isBeta }.joinToString(", ") { it.shortLabel }, selectable = true),
    )

    /**
     * The groups in display order, empty groups left out. [CoordinateSystemGroup.BETA] only when [preview] is ON;
     * a capability is BETA when it is selectable, UNSUPPORTED otherwise (unavailable, or verified but not offered).
     */
    fun build(preview: Boolean, capabilities: List<Nsrs2022Capability>): List<Pair<CoordinateSystemGroup, List<CoordinateSystemGroupItem>>> {
        val beta = if (!preview) emptyList() else capabilities.filter { it.selectable }.map { c ->
            CoordinateSystemGroupItem(c.name, CoordinateSystemGroup.BETA, c.state.label, selectable = true,
                fullReason = c.note, beta = c.state == CapabilityUiState.BETA)
        }
        val unsupported = capabilities.filter { !it.selectable }.map { c ->
            CoordinateSystemGroupItem(c.name, CoordinateSystemGroup.UNSUPPORTED,
                if (c.state == CapabilityUiState.UNAVAILABLE) "unavailable" else "not offered",
                selectable = false, shortReason = Nsrs2022Capabilities.shortReason(c), fullReason = c.reason,
                beta = c.state == CapabilityUiState.BETA)
        }
        return listOf(
            CoordinateSystemGroup.VERIFIED to verified(),
            CoordinateSystemGroup.BETA to beta,
            CoordinateSystemGroup.UNSUPPORTED to unsupported,
        ).filter { it.second.isNotEmpty() }
    }
}
