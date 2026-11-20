package app.surrealar.coords

import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.VerticalReference
import app.surrealar.util.UtmConverter
import java.util.Locale

/** EPSG codes recorded in Coordinate.crsEpsg for the datum of stored lat/lon/height. */
object StoredDatum {
    const val WGS84_3D = 4979
    const val WGS84_2D = 4326
    const val NAD83_2011_3D = 6319
    const val NAD83_2011_2D = 6318

    fun isNad83(epsg: Int?) = epsg == NAD83_2011_3D || epsg == NAD83_2011_2D
}

/** A measured position as stored/received, plus what is needed to convert it correctly. */
data class SourcePosition(
    val latDeg: Double,
    val lonDeg: Double,
    /** Ellipsoidal height in the source datum, metres (null = unknown). */
    val ellipsoidalHeightM: Double?,
    /** Receiver-reported MSL height, metres (for VerticalReference.RECEIVER_MSL). */
    val receiverMslM: Double?,
    /** True if lat/lon/height are NAD83(2011); false = WGS84/ITRF2014. */
    val isNad83: Boolean,
    /** Observation epoch as a decimal year (ITRF ⇄ NAD83 is time-dependent). */
    val epochYear: Double,
)

/** A position expressed in the user's chosen coordinate system. Lengths are in [unit]. */
data class ConvertedPosition(
    val horizontal: HorizontalSystem,
    val datum: OutputDatum,
    val unit: LinearUnit,
    /** Geographic output (degrees, in [datum]); also filled for grid systems for reference. */
    val latDeg: Double,
    val lonDeg: Double,
    /** Grid coordinates in [unit]; null for GEOGRAPHIC. */
    val easting: Double?,
    val northing: Double?,
    /** e.g. "PA N (3701)", "UTM 18N"; null for GEOGRAPHIC. */
    val zoneLabel: String?,
    val vertical: VerticalReference,
    /** Height in [unit] for [vertical]; null when unavailable (no height, outside GEOID18 grid…). */
    val height: Double?,
    /** Why [height] is null, when it is. */
    val heightUnavailableReason: String? = null,
)

/**
 * Converts stored/received positions into the configured coordinate system. Pure (no Android): the
 * GEOID18 lookup is injected so it can be unit-tested and loaded from assets on device.
 */
class CoordinateConverter(private val geoidN: (latDeg: Double, lonDeg: Double) -> Double?) {

    fun convert(src: SourcePosition, s: CoordinateSystemSettings): ConvertedPosition {
        val h = src.ellipsoidalHeightM ?: 0.0
        val source = GeodeticPosition(src.latDeg, src.lonDeg, h)
        // Each datum computed only when needed.
        val nad by lazy { if (src.isNad83) source else Itrf2014ToNad83.forward(source, src.epochYear) }
        val itrf by lazy { if (src.isNad83) Itrf2014ToNad83.inverse(source, src.epochYear) else source }

        val datum = s.effectiveDatum
        val geo = if (datum == OutputDatum.NAD83_2011) nad else itrf
        val unit = s.unit

        var easting: Double? = null; var northing: Double? = null; var zone: String? = null
        when (s.horizontal) {
            HorizontalSystem.GEOGRAPHIC -> Unit
            HorizontalSystem.UTM -> {
                val u = UtmConverter.latLonToUtm(geo.latDeg, geo.lonDeg)
                easting = unit.fromMeters(u.easting); northing = unit.fromMeters(u.northing)
                zone = "UTM ${u.zone}${if (geo.latDeg >= 0) "N" else "S"}"
            }
            HorizontalSystem.SPCS_PA_NORTH -> {
                val (e, n) = LambertConformalConic.PA_NORTH.forward(nad.latDeg, nad.lonDeg)
                easting = unit.fromMeters(e); northing = unit.fromMeters(n); zone = "PA N (3701)"
            }
            HorizontalSystem.SPCS_PA_SOUTH -> {
                val (e, n) = LambertConformalConic.PA_SOUTH.forward(nad.latDeg, nad.lonDeg)
                easting = unit.fromMeters(e); northing = unit.fromMeters(n); zone = "PA S (3702)"
            }
        }

        var reason: String? = null
        val heightM: Double? = when (s.vertical) {
            VerticalReference.ELLIPSOIDAL ->
                if (src.ellipsoidalHeightM == null) { reason = "no ellipsoidal height"; null } else geo.heightM
            VerticalReference.NAVD88_GEOID18 -> when {
                src.ellipsoidalHeightM == null -> { reason = "no ellipsoidal height"; null }
                else -> geoidN(nad.latDeg, nad.lonDeg)?.let { n -> nad.heightM - n }
                    ?: run { reason = "outside GEOID18 coverage"; null }
            }
            VerticalReference.RECEIVER_MSL ->
                src.receiverMslM ?: run { reason = "receiver reported no MSL height"; null }
        }

        return ConvertedPosition(
            horizontal = s.horizontal, datum = datum, unit = unit,
            latDeg = geo.latDeg, lonDeg = geo.lonDeg,
            easting = easting, northing = northing, zoneLabel = zone,
            vertical = s.vertical, height = heightM?.let { unit.fromMeters(it) },
            heightUnavailableReason = reason,
        )
    }
}

/** Display strings for [ConvertedPosition] (shared by toolbar, detail screen, labels, export). */
object CoordinateFormat {
    private fun grouped(v: Double, decimals: Int) = String.format(Locale.US, "%,.${decimals}f", v)

    /** Decimals for grid values / heights: mm-level in metres, 0.01 in feet. */
    fun linearDecimals(unit: LinearUnit) = if (unit == LinearUnit.METERS) 3 else 2

    /** One line: "N 435,037.47 · E 2,442,754.19 ftUS" or "41.34769028, -76.02259178". */
    fun horizontal(p: ConvertedPosition, latLonDecimals: Int = 8): String =
        if (p.easting == null || p.northing == null)
            String.format(Locale.US, "%.${latLonDecimals}f, %.${latLonDecimals}f", p.latDeg, p.lonDeg)
        else {
            val d = linearDecimals(p.unit)
            "N ${grouped(p.northing, d)} · E ${grouped(p.easting, d)} ${p.unit.suffix}"
        }

    /** "1,286.74 ftUS NAVD88", or "—" with no height. */
    fun height(p: ConvertedPosition): String =
        p.height?.let { "${grouped(it, linearDecimals(p.unit))} ${p.unit.suffix} ${p.vertical.shortLabel}" } ?: "—"

    /** Short system description: "NAD83(2011) · PA N (3701) · ftUS · NAVD88". */
    fun systemSummary(p: ConvertedPosition): String =
        listOfNotNull(p.datum.label, p.zoneLabel, p.unit.suffix, p.vertical.shortLabel).joinToString(" · ")
}
