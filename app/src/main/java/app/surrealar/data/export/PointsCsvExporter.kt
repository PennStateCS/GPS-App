package app.surrealar.data.export

import app.surrealar.coords.ConvertedPosition
import app.surrealar.coords.CoordinateFormat
import app.surrealar.domain.model.Coordinate
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
 * This is an interchange export; the JSON backup remains the lossless format (raw measurements).
 */
object PointsCsvExporter {

    /** The original six-column export (option OFF). */
    fun build(points: List<Coordinate>, convert: (Coordinate) -> ConvertedPosition): String =
        build(points, includePositionReference = false, convert = convert)

    /** [includePositionReference]: append the "Position reference" column to every row (the export option). */
    fun build(points: List<Coordinate>, includePositionReference: Boolean, convert: (Coordinate) -> ConvertedPosition): String {
        val sb = StringBuilder()
        val converted = points.map { it to convert(it) }
        val first = converted.firstOrNull()?.second
        val grid = first?.easting != null
        val unit = first?.unit?.suffix ?: "m"
        val heightRef = first?.vertical?.shortLabel ?: ""
        val withReference = includePositionReference
        val refs = if (withReference) points.map { positionReferenceCell(it) } else emptyList()
        val refHeader = if (withReference) ",$POSITION_REFERENCE_HEADER" else ""
        if (grid) {
            sb.append("Point,Northing ($unit),Easting ($unit),Elevation ($unit $heightRef),Description,System$refHeader\n")
        } else {
            sb.append("Point,Latitude,Longitude,Elevation ($unit $heightRef),Description,System$refHeader\n")
        }
        for ((i, cp) in converted.withIndex()) {
            val (c, p) = cp
            val d = CoordinateFormat.linearDecimals(p.unit)
            val elev = p.height?.let { String.format(Locale.US, "%.${d}f", it) } ?: ""
            val xy = if (p.easting != null && p.northing != null)
                String.format(Locale.US, "%.${d}f,%.${d}f", p.northing, p.easting)
            else
                String.format(Locale.US, "%.9f,%.9f", p.latDeg, p.lonDeg)
            // Per row: UTM picks each point's own zone, and "outside zone" is per point, so a job that
            // crosses a zone line must not carry the first point's zone onto every row.
            val system = CoordinateFormat.systemSummary(p)
            sb.append(csv(c.name)).append(',').append(xy).append(',').append(elev).append(',')
                .append(csv(c.note.orEmpty())).append(',').append(csv(system))
            if (withReference) sb.append(',').append(csv(refs[i]))
            sb.append('\n')
        }
        return sb.toString()
    }

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
