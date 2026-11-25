package app.surrealar.coords

import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.OutputDatum
import io.github.pennstatecs.geokit.crs.CrsCatalogId
import io.github.pennstatecs.geokit.crs.CrsDefinition
import io.github.pennstatecs.geokit.crs.CrsLookup
import io.github.pennstatecs.geokit.crs.CrsRegistry
import io.github.pennstatecs.geokit.datum.FrameTransformationRegistry
import io.github.pennstatecs.geokit.datum.PlateFrame2022
import io.github.pennstatecs.geokit.datum.ReferenceFrame
import java.util.Locale

/**
 * The registries the app uses (Phase 4 WP X): with the NSRS 2022 beta preview OFF exactly geokit's defaults
 * ([FrameTransformationRegistry.DEFAULT], [CrsRegistry.DEFAULT]: no 2022 frame, no SPCS2022 zone), with it ON
 * the opt-in [FrameTransformationRegistry.NSRS2022_BETA] and a [CrsRegistry] built on it (the four 2022 frames
 * and all 953 SPCS2022 zones as VERIFIED_BETA).
 */
object Nsrs2022Registries {
    /** The CRS registry on [FrameTransformationRegistry.NSRS2022_BETA] (SPCS2022 catalog parsed on first use: not on the main thread). */
    val betaCrs: CrsRegistry by lazy { CrsRegistry(frameRegistry = FrameTransformationRegistry.NSRS2022_BETA) }

    fun frames(preview: Boolean): FrameTransformationRegistry =
        if (preview) FrameTransformationRegistry.NSRS2022_BETA else FrameTransformationRegistry.DEFAULT

    fun crs(preview: Boolean): CrsRegistry = if (preview) betaCrs else CrsRegistry.DEFAULT

    /**
     * The operational SPCS2022 zone [id] (`spcs2022:<code>`) in [registry], only on a 2022 frame (never NAD83);
     * null for a blank, unknown or non-operational id.
     */
    fun spcs2022Zone(id: String?, registry: CrsRegistry = betaCrs): CrsDefinition? {
        if (id.isNullOrBlank()) return null
        val d = (registry.byId(id) as? CrsLookup.Found)?.definition ?: return null
        return d.takeIf { it.catalog == CrsCatalogId.SPCS2022 && it.operational && it.frame?.let(::isFrame2022) == true }
    }

    /** True for NATRF2022, PATRF2022, CATRF2022 and MATRF2022. */
    fun isFrame2022(f: ReferenceFrame): Boolean = PlateFrame2022.entries.any { it.referenceFrame == f }
}

/** Result of resolving the saved SPCS2022 zone for a conversion. */
sealed interface Spcs2022ZoneLookup {
    data class Found(val definition: CrsDefinition) : Spcs2022ZoneLookup

    /** The zone catalog is still loading (main thread before the background load finished). */
    data object Loading : Spcs2022ZoneLookup

    /** No operational SPCS2022 zone has this id. */
    data object Unknown : Spcs2022ZoneLookup
}

/** The geokit frame of a beta output datum, null for the legacy datums. */
val OutputDatum.frame2022: ReferenceFrame?
    get() = when (this) {
        OutputDatum.NATRF2022 -> ReferenceFrame.NATRF2022
        OutputDatum.PATRF2022 -> ReferenceFrame.PATRF2022
        OutputDatum.CATRF2022 -> ReferenceFrame.CATRF2022
        OutputDatum.MATRF2022 -> ReferenceFrame.MATRF2022
        OutputDatum.WGS84, OutputDatum.NAD83_2011, OutputDatum.ITRF2020 -> null
    }

/** The beta output datum of a 2022 frame, null for any other frame. */
fun outputDatumOf2022(frame: ReferenceFrame): OutputDatum? = OutputDatum.entries.firstOrNull { it.frame2022 == frame }

/**
 * Labels and caveats of the NSRS 2022 beta frames and SPCS2022 output: always beta, always with the dataset
 * version and the evidence caveat.
 */
object Nsrs2022FrameLabels {
    /** Short dataset version of the frame rotation: the NGS EPP2022 beta table (CSV Last-Modified 2025-06-16). */
    const val DATASET: String = "EPP2022 beta 2025-06-16"

    /** Short dataset version of the SPCS2022 zone definitions. */
    const val SPCS2022_DATASET: String = "NGS beta zones 2026-05-14"

    /** What the frame rotation's verification rests on, and what it does not claim. */
    const val CAVEAT: String = "Verified against NGS beta Geodetic Station Page coordinates; the NGS endpoints are " +
        "undocumented; not authoritative."

    /** "NATRF2022 (beta) epoch 2026.886". */
    fun datumLabel(frame: ReferenceFrame, epochYear: Double): String =
        String.format(Locale.US, "%s (beta) epoch %.3f", frame.displayName, epochYear)

    /** "NATRF2022 epoch 2026.886" (inside an SPCS2022 summary, which already says beta). */
    fun frameEpoch(frame: ReferenceFrame, epochYear: Double): String =
        String.format(Locale.US, "%s epoch %.3f", frame.displayName, epochYear)

    /** "SPCS2022 421001 (beta)": the zone label of an SPCS2022 position. */
    fun zoneLabel(zone: CrsDefinition): String = "SPCS2022 ${zone.zoneCode ?: zone.id} (beta)"

    /** Provenance after the system summary of a 2022-frame position (also in the CSV System column). */
    fun note(spcs2022: Boolean): String = if (spcs2022) "$DATASET · $SPCS2022_DATASET" else DATASET

    /** Area note of a 2022 frame (its plate's usual area). */
    fun areaNote(frame: ReferenceFrame): String? = frame.plate?.let { "${it.displayName}: ${it.areaNote}" }

    /** Unit note: NGS defines SPCS2022 in metres and international feet only. */
    const val SPCS2022_UNIT_NOTE: String =
        "SPCS2022 is in metres or international feet (NGS does not support the US survey foot): US survey feet show as international feet."
}

/** Notes under the System and Datum fields (Settings → Coordinates) for the 2022 beta outputs. Pure. */
object Nsrs2022OutputNotes {
    /** Note under the System field: SPCS2022 in use, or saved but not used; null otherwise. */
    fun horizontalNote(s: CoordinateSystemSettings, zone: CrsDefinition? = null): String? = when {
        s.horizontal != HorizontalSystem.SPCS2022 -> null
        !s.nsrs2022Preview ->
            "${HorizontalSystem.SPCS2022.label} is saved, but the NSRS 2022 beta preview is off: showing " +
                "${HorizontalSystem.GEOGRAPHIC.label}. Turn the preview on to use it again."
        s.spcs2022Zone.isNullOrBlank() ->
            "Choose an SPCS2022 zone below. Until then ${HorizontalSystem.GEOGRAPHIC.label} is shown."
        else -> {
            val frame = zone?.frame
            "Beta: SPCS2022 ${zone?.label ?: s.spcs2022Zone} on ${frame?.displayName ?: "its 2022 frame"} at each point's " +
                "coordinate epoch (${Nsrs2022FrameLabels.DATASET}; ${Nsrs2022FrameLabels.SPCS2022_DATASET}). " +
                Nsrs2022FrameLabels.CAVEAT +
                (if (s.unit == app.surrealar.settings.model.LinearUnit.US_SURVEY_FEET) " " + Nsrs2022FrameLabels.SPCS2022_UNIT_NOTE else "")
        }
    }

    /** Note under the Datum field: a 2022 frame in use, or saved but not used; null otherwise. */
    fun datumNote(s: CoordinateSystemSettings): String? {
        val frame = s.datum.frame2022 ?: return null
        return when {
            !s.nsrs2022Preview ->
                "${s.datum.label} is saved, but the NSRS 2022 beta preview is off: showing ${OutputDatum.WGS84.label}. " +
                    "Turn the preview on to use it again."
            s.effectiveHorizontal == HorizontalSystem.UTM ->
                "${s.datum.label} applies to ${HorizontalSystem.GEOGRAPHIC.label} only (no UTM on a 2022 frame): UTM is " +
                    "shown in ${OutputDatum.WGS84.label}."
            s.betaDatumSuspended -> null
            s.effectiveDatum == s.datum ->
                "Beta: ${frame.displayName} at each point's coordinate epoch (${Nsrs2022FrameLabels.DATASET}). " +
                    (Nsrs2022FrameLabels.areaNote(frame)?.let { "Area: $it. " } ?: "") + Nsrs2022FrameLabels.CAVEAT
            else -> null
        }
    }
}
