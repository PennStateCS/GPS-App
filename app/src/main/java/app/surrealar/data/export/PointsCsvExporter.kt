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
        val system = first?.let { CoordinateFormat.systemSummary(it) } ?: ""
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
            sb.append(csv(c.name)).append(',').append(xy).append(',').append(elev).append(',')
                .append(csv(c.note.orEmpty())).append(',').append(csv(system)).append('\n')
        }
        return sb.toString()
    }

    private fun csv(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
