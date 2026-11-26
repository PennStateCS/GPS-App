package app.surrealar.data.export

import app.surrealar.coords.ConvertedPosition
import app.surrealar.coords.CoordinateFormat
import app.surrealar.domain.model.Coordinate
import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.LinearUnit
import java.util.Locale

/**
 * Survey points export in the user's coordinate system (Settings → Coordinates).
 *
 * Grid systems use the standard PNEZD order (Point, Northing, Easting, Elevation, Description);
 * geographic output uses Point, Latitude, Longitude, Elevation, Description. Lengths are in the
 * configured unit, the elevation in the configured height reference (blank when unavailable, e.g.
 * outside GEOID18 coverage). The header row names the system so the file is self-describing.
 * Elevations are at the ground mark (saved points are reduced by the pole height and phase-centre
 * offset at capture). The Elevation header names the height type and geoid model only ("h",
 * "H GEOID18", "H EGM2008", "H EGM96", "H rcvr") — never a vertical datum — because GEOID18 rows can be
 * NAVD88, PRVD02 or VIVD09 depending on the point; each row's System column names that row's frame
 * (with its coordinate epoch for ITRF2020, e.g. "ITRF2020 epoch 2026.886") and vertical datum
 * ("NAVD88", "EGM2008", "EGM96"). GEOID2022 heights (NSRS 2022 beta preview only) read "H GEOID2022 beta"
 * in the header and "NAPGD2022 beta · GEOID2022 (beta beta_v0a) epoch 2026.886" in the System column (model,
 * NGS dataset version and the coordinate epoch of N). The column count and order never change with the settings.
 *
 * Position reference (export option, default OFF; product decision 2026-11-25): with [build]'s
 * `includePositionReference` the column "Position reference" is APPENDED after System in EVERY export, whatever
 * points the file holds, saying whether each external capture is the antenna (pole height applied), the receiver's
 * compensated pole tip, or UNCONFIRMED (antenna reduction applied; height reduced twice if the receiver streamed
 * compensated positions); blank for other points. With the option OFF the file is the original six columns. The
 * column count never depends on the points.
 *
 * One representation per export (stabilization S2): the Settings export takes its columns from the settings
 * ([Layout.of]), never from the first point. A point that has no position in them (SPCS2022 zone data loading or
 * zone missing, a failed conversion) keeps the same columns with blank position and elevation and
 * "unavailable: <reason>" in System; latitude/longitude are never written under Northing/Easting, nor grid values
 * under Latitude/Longitude.
 *
 * This is an interchange export; the JSON backup remains the lossless format (raw measurements).
 */
object PointsCsvExporter {

    /**
     * The columns of one export, decided ONCE before any row is written: grid (Northing/Easting) or geographic
     * (Latitude/Longitude), the length unit and the height label. Every row is written in this representation or
     * left blank with its reason.
     */
    data class Layout(val grid: Boolean, val unit: LinearUnit, val heightLabel: String) {
        companion object {
            /**
             * The layout of [settings] as the converter uses them ([CoordinateSystemSettings.forConversion]), so it
             * never depends on which points the file holds. SPCS2022 is defined in metres and international feet
             * only: US survey feet are shown as international feet there (the converter's rule; a test pins that
             * the two agree).
             */
            fun of(settings: CoordinateSystemSettings): Layout {
                val s = settings.forConversion()
                val unit = if (s.horizontal == HorizontalSystem.SPCS2022 && s.unit == LinearUnit.US_SURVEY_FEET)
                    LinearUnit.INTERNATIONAL_FEET else s.unit
                return Layout(grid = s.horizontal.isGrid, unit = unit, heightLabel = s.vertical.shortLabel)
            }

            /** Without settings (the [build] overloads): the first point shown in its configured system. */
            internal fun fromConverted(converted: List<ConvertedPosition?>): Layout {
                val p = converted.firstOrNull { it != null && it.systemNote == null } ?: converted.firstOrNull { it != null }
                return Layout(grid = p?.easting != null, unit = p?.unit ?: LinearUnit.METERS,
                    heightLabel = p?.vertical?.shortLabel ?: "")
            }
        }
    }

    /** One export: the file text, its point rows, and how many rows have no position in the export's system. */
    data class Result(val text: String, val rowCount: Int, val unavailableCount: Int)

    /** The original six-column export (option OFF). */
    fun build(points: List<Coordinate>, convert: (Coordinate) -> ConvertedPosition): String =
        build(points, includePositionReference = false, convert = convert)

    /** [includePositionReference]: append the "Position reference" column to every row (the export option). */
    fun build(points: List<Coordinate>, includePositionReference: Boolean, convert: (Coordinate) -> ConvertedPosition): String {
        val converted = points.map { convertRow(it, convert) }
        return write(points, converted, Layout.fromConverted(converted.map { it.getOrNull() }), includePositionReference).text
    }

    /**
     * The Settings export: columns from [layout] (normally [Layout.of] the export's settings), so the header is
     * right even for an empty file and no point can change it. Rows without a position in that representation are
     * counted in [Result.unavailableCount] (shown to the user after the export).
     */
    fun export(points: List<Coordinate>, layout: Layout, includePositionReference: Boolean,
               convert: (Coordinate) -> ConvertedPosition): Result =
        write(points, points.map { convertRow(it, convert) }, layout, includePositionReference)

    /** One point converted; a failure is that row's reason, never the whole export's (cancellation propagates). */
    private fun convertRow(c: Coordinate, convert: (Coordinate) -> ConvertedPosition): kotlin.Result<ConvertedPosition> =
        try {
            kotlin.Result.success(convert(c))
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            kotlin.Result.failure(e)
        }

    /** Why [p] cannot be written in [layout], or null when it can. */
    internal fun unavailableReason(p: ConvertedPosition, layout: Layout): String? = when {
        // The configured system was not used (e.g. "SPCS2022 zone data is loading"): its fallback is never exported.
        p.systemNote != null -> p.systemNote
        layout.grid && (p.easting == null || p.northing == null) -> "no grid coordinates for this point"
        !layout.grid && (p.easting != null || p.northing != null) -> "converted to grid coordinates, file is latitude/longitude"
        p.unit != layout.unit -> "converted in ${p.unit.suffix}, file is ${layout.unit.suffix}"
        else -> null
    }

    private fun write(points: List<Coordinate>, converted: List<kotlin.Result<ConvertedPosition>>, layout: Layout,
                      withReference: Boolean): Result {
        val sb = StringBuilder()
        val unit = layout.unit.suffix
        val heightRef = layout.heightLabel
        val refHeader = if (withReference) ",$POSITION_REFERENCE_HEADER" else ""
        if (layout.grid) {
            sb.append("Point,Northing ($unit),Easting ($unit),Elevation ($unit $heightRef),Description,System$refHeader\n")
        } else {
            sb.append("Point,Latitude,Longitude,Elevation ($unit $heightRef),Description,System$refHeader\n")
        }
        var unavailable = 0
        for ((i, c) in points.withIndex()) {
            val p = converted[i].getOrNull()
            val reason = if (p == null) {
                val e = converted[i].exceptionOrNull()
                "conversion failed: ${e?.message ?: e?.javaClass?.simpleName ?: "unknown error"}"
            } else unavailableReason(p, layout)
            val xy: String
            val elev: String
            val system: String
            if (p == null || reason != null) {
                // Same columns, blank position and elevation: never another representation's values.
                unavailable++
                xy = ","; elev = ""; system = UNAVAILABLE_PREFIX + reason
            } else {
                val d = CoordinateFormat.linearDecimals(p.unit)
                elev = p.height?.let { String.format(Locale.US, "%.${d}f", it) } ?: ""
                xy = if (layout.grid) String.format(Locale.US, "%.${d}f,%.${d}f", p.northing, p.easting)
                    else String.format(Locale.US, "%.9f,%.9f", p.latDeg, p.lonDeg)
                // Per row: UTM picks each point's own zone, and "outside zone" is per point, so a job that
                // crosses a zone line must not carry the first point's zone onto every row.
                system = CoordinateFormat.systemSummary(p)
            }
            sb.append(csv(c.name)).append(',').append(xy).append(',').append(elev).append(',')
                .append(csv(c.note.orEmpty())).append(',').append(csv(system))
            if (withReference) sb.append(',').append(csv(positionReferenceCell(c)))
            sb.append('\n')
        }
        return Result(sb.toString(), points.size, unavailable)
    }

    /** Start of the System cell of a row that has no position in the export's representation. */
    const val UNAVAILABLE_PREFIX = "unavailable: "

    /** Header of the appended (last) column: which physical point an external capture is. */
    const val POSITION_REFERENCE_HEADER = "Position reference"

    /**
     * The [POSITION_REFERENCE_HEADER] cell (Phase 4 stabilization WP A): "antenna", "receiver pole tip (compensated)",
     * or the unconfirmed warning for an external capture; blank for internal, manual, imported and model points.
     * No commas, so the column count stays fixed even for naive splitters.
     */
    internal fun positionReferenceCell(c: Coordinate): String =
        when (app.surrealar.domain.model.PositionReferenceKind.of(
            c.captureMethod, app.surrealar.data.local.db.ProviderTokens.isExternal(c.provider))) {
            app.surrealar.domain.model.PositionReferenceKind.ANTENNA -> "antenna (pole height applied)"
            app.surrealar.domain.model.PositionReferenceKind.RECEIVER_POLE_TIP -> "receiver pole tip (compensated; no pole height applied)"
            app.surrealar.domain.model.PositionReferenceKind.UNCONFIRMED -> UNCONFIRMED_CELL
            null -> ""
        }

    /** Cell for an unconfirmed position reference. */
    const val UNCONFIRMED_CELL = "UNCONFIRMED: antenna reduction applied; height reduced twice if the receiver streamed compensated positions"

    /**
     * One CSV text field. A value a spreadsheet would run as a formula (starts with = + - @, tab or CR)
     * gets a leading single quote so it opens as plain text; then the usual quoting applies.
     */
    internal fun csv(raw: String): String {
        val v = if (raw.isNotEmpty() && raw[0] in FORMULA_STARTS) "'$raw" else raw
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    private val FORMULA_STARTS = charArrayOf('=', '+', '-', '@', '\t', '\r')
}
