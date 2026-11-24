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
 * ("NAVD88", "EGM2008", "EGM96"). The column count and order never change.
 *
 * This is an interchange export; the JSON backup remains the lossless format (raw measurements).
 */
object PointsCsvExporter {

    fun build(points: List<Coordinate>, convert: (Coordinate) -> ConvertedPosition): String {
        val sb = StringBuilder()
        val converted = points.map { it to convert(it) }
        val first = converted.firstOrNull()?.second
        val grid = first?.easting != null
        val unit = first?.unit?.suffix ?: "m"
        val heightRef = first?.vertical?.shortLabel ?: ""
        if (grid) {
            sb.append("Point,Northing ($unit),Easting ($unit),Elevation ($unit $heightRef),Description,System\n")
        } else {
            sb.append("Point,Latitude,Longitude,Elevation ($unit $heightRef),Description,System\n")
        }
        for ((c, p) in converted) {
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
                .append(csv(c.note.orEmpty())).append(',').append(csv(system)).append('\n')
        }
        return sb.toString()
    }

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
