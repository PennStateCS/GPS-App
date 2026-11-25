package app.surrealar.coords

import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.VerticalReference
import io.github.pennstatecs.geokit.geoid.GeoidModelInfo
import java.util.Locale

/**
 * The parts of a displayed height ([CoordinateConverter.elevation]): h, H, N, the geoid model and its data
 * version, the vertical reference, the coordinate epoch and the beta status. Display only, never stored.
 * Lengths are in [unit].
 *
 * @property ellipsoidalHeight h in [ellipsoidalFrame] (the frame the height model works in), null if unknown.
 * @property orthometricHeight H for the chosen reference, null when unavailable (see [unavailableReason],
 *   [loading]) and for the ellipsoidal choice (no H then).
 * @property undulation N = h − H of the model, null when the model gives none (receiver, ellipsoidal, unavailable).
 * @property geoidModel "GEOID18", "EGM2008", "GEOID2022 (beta)", "receiver geoid (model unknown)"; null for h.
 * @property geoidVersion short data version: "g2018u0+g2018p0", "2.5' us_nga_egm08_25", "beta_v0a".
 * @property verticalReference "NAVD88" (or PRVD02 / VIVD09), "EGM2008", "EGM96", "NAPGD2022", [ELLIPSOID],
 *   [RECEIVER_MODEL]. GEOID2022 heights are always NAPGD2022, never NAVD88 or MSL.
 * @property epochYear coordinate epoch of the height (GEOID2022: of h(ITRF2020) and N), or of an ITRF2020 output.
 * @property beta NGS beta product (GEOID2022): always labelled.
 * @property loading H is missing only because the model data is loading.
 */
data class ElevationDetail(
    val unit: LinearUnit,
    val vertical: VerticalReference,
    val ellipsoidalHeight: Double?,
    val ellipsoidalFrame: String?,
    val orthometricHeight: Double?,
    val undulation: Double?,
    val geoidModel: String?,
    val geoidVersion: String?,
    val verticalReference: String,
    val epochYear: Double?,
    val beta: Boolean,
    val loading: Boolean,
    val unavailableReason: String?,
) {
    companion object {
        const val ELLIPSOID = "ellipsoid (no geoid)"
        const val RECEIVER_MODEL = "receiver geoid (model unknown)"

        /** "GEOID18 g2018u0+g2018p0 (SRGT v1)" → "g2018u0+g2018p0"; "GEOID2022 beta_v0a (…)" → "beta_v0a". */
        fun shortVersion(info: GeoidModelInfo): String =
            info.dataVersion.substringBefore(" (").removePrefix(info.id.token + " ").trim()
    }
}

/** Display strings for [ElevationDetail] (Details rows, Settings preview). Pure. */
object ElevationFormat {
    private fun len(v: Double, unit: LinearUnit): String =
        String.format(Locale.US, "%,.${CoordinateFormat.linearDecimals(unit)}f %s", v, unit.suffix)

    /** "Loading GEOID2022 (beta) data…" style text for a height whose model is still loading. */
    fun loadingText(e: ElevationDetail): String = "Loading ${e.geoidModel ?: "height"} data…"

    /** H, or the loading text, or why it is unavailable. */
    fun orthometricText(e: ElevationDetail): String = e.orthometricHeight?.let { len(it, e.unit) }
        ?: if (e.loading) loadingText(e) else (e.unavailableReason ?: NOT_AVAILABLE)

    /** "NAPGD2022 (beta)" for a beta reference, the reference otherwise. */
    fun verticalReferenceText(e: ElevationDetail): String = if (e.beta) "${e.verticalReference} (beta)" else e.verticalReference

    /** "GEOID2022 (beta) · beta_v0a", or the model alone. */
    fun modelText(e: ElevationDetail): String? = e.geoidModel?.let { m -> e.geoidVersion?.let { "$m · $it" } ?: m }

    const val NOT_AVAILABLE = "Not available"
    const val STATUS_BETA = "Beta: preliminary NGS product, not for authoritative survey results"
    const val STATUS_VERIFIED = "Verified"

    /** One (label, value) row. */
    data class Row(val label: String, val value: String)

    /**
     * Rows for the Details "Elevation" section: h (with its frame), H (with its reference; not for the
     * ellipsoidal choice), N (when the model gives one), model + version, vertical reference, epoch (when the
     * height has one) and status. A loading model reads "Loading … data…" in place of H and N, never a value.
     */
    fun rows(e: ElevationDetail): List<Row> = buildList {
        add(Row("h (ellipsoidal)" + (e.ellipsoidalFrame?.let { " · $it" } ?: ""),
            e.ellipsoidalHeight?.let { len(it, e.unit) } ?: NOT_AVAILABLE))
        if (e.vertical != VerticalReference.ELLIPSOIDAL) {
            add(Row("H (orthometric) · ${verticalReferenceText(e)}", orthometricText(e)))
        }
        if (e.geoidModel != null && e.vertical != VerticalReference.RECEIVER_MSL) {
            add(Row("N (geoid undulation)", e.undulation?.let { len(it, e.unit) }
                ?: if (e.loading) loadingText(e) else NOT_AVAILABLE))
        }
        modelText(e)?.let { add(Row("Geoid model", it)) }
        add(Row("Vertical reference", verticalReferenceText(e)))
        e.epochYear?.let { add(Row("Height epoch", String.format(Locale.US, "%.3f", it))) }
        add(Row("Status", if (e.beta) STATUS_BETA else STATUS_VERIFIED))
    }

    /**
     * One line for the Settings preview: "h 123.456 m (ITRF2020 epoch 2026.886) · N −33.120 m (GEOID2022 (beta)
     * beta_v0a) · H 156.576 m NAPGD2022 (beta)"; parts that do not apply are left out.
     */
    fun previewLine(e: ElevationDetail): String = listOfNotNull(
        e.ellipsoidalHeight?.let { "h ${len(it, e.unit)}" + (e.ellipsoidalFrame?.let { f -> " ($f)" } ?: "") },
        e.undulation?.let { n -> "N ${len(n, e.unit)}" + (modelText(e)?.let { " (${it.replace(" · ", " ")})" } ?: "") },
        when {
            e.vertical == VerticalReference.ELLIPSOIDAL -> null
            e.orthometricHeight != null -> "H ${orthometricText(e)} ${verticalReferenceText(e)}"
            else -> "H ${verticalReferenceText(e)}: ${orthometricText(e)}"
        },
    ).joinToString(" · ")
}
