package app.surrealar.coords

import app.surrealar.geo.ellipsoid.GeodeticPosition
import app.surrealar.geo.datum.Itrf2014ToNad83
import app.surrealar.geo.geoid.Geoid18Grid
import app.surrealar.geo.projection.SpcsZone
import app.surrealar.geo.projection.SpcsZones

import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.VerticalReference
import app.surrealar.geo.projection.Utm
import java.util.Locale

/** EPSG codes recorded in Coordinate.crsEpsg for the datum of stored lat/lon/height. */
object StoredDatum {
    const val WGS84_3D = 4979
    const val WGS84_2D = 4326
    const val NAD83_2011_3D = 6319
    const val NAD83_2011_2D = 6318

    fun isNad83(epsg: Int?) = epsg == NAD83_2011_3D || epsg == NAD83_2011_2D

    /**
     * A stored point as WGS84 (ITRF2014) for placement on Google Maps / ARCore Geospatial, which work
     * in WGS84. Points already in WGS84 come back unchanged (the same instance); NAD83(2011) points
     * (imports, or captures from before the feed normalised receiver output) are transformed at
     * their capture epoch. Use for plotting only, not for re-saving over the stored record.
     */
    fun toWgs84(c: app.surrealar.domain.model.Coordinate): app.surrealar.domain.model.Coordinate {
        if (!isNad83(c.crsEpsg)) return c
        val w = Itrf2014ToNad83.inverse(GeodeticPosition(c.latitude, c.longitude, c.altitude),
            Itrf2014ToNad83.decimalYear(c.timestamp))
        return c.copy(latitude = w.latDeg, longitude = w.lonDeg, altitude = w.heightM,
            crsEpsg = if (c.crsEpsg == NAD83_2011_2D) WGS84_2D else WGS84_3D)
    }

    /** [toWgs84] for the Room entity (AR loads entities directly). */
    fun toWgs84(e: app.surrealar.data.local.entity.CoordinateEntity): app.surrealar.data.local.entity.CoordinateEntity {
        if (!isNad83(e.crsEpsg)) return e
        val w = Itrf2014ToNad83.inverse(GeodeticPosition(e.latitude, e.longitude, e.altitude),
            Itrf2014ToNad83.decimalYear(e.timestamp))
        return e.copy(latitude = w.latDeg, longitude = w.lonDeg, altitude = w.heightM,
            crsEpsg = if (e.crsEpsg == NAD83_2011_2D) WGS84_2D else WGS84_3D)
    }
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
    /** e.g. "SPCS 3701", "UTM 18N"; null for GEOGRAPHIC. */
    val zoneLabel: String?,
    val vertical: VerticalReference,
    /** Height in [unit] for [vertical]; null when unavailable (no height, outside GEOID18 grid…). */
    val height: Double?,
    /** Why [height] is null, when it is. */
    val heightUnavailableReason: String? = null,
    /** State Plane only: the position lies outside the selected zone's area of use (wrong zone?). */
    val outsideZone: Boolean = false,
    /** Short height-datum label: "NAVD88" (or PRVD02/VIVD09 from GEOID18 in PR/VI), "Ellip", "MSL". */
    val verticalLabel: String = vertical.shortLabel,
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
        var spcsZone: SpcsZone? = null
        when (s.horizontal) {
            HorizontalSystem.GEOGRAPHIC -> Unit
            HorizontalSystem.UTM -> {
                val u = Utm.forward(geo.latDeg, geo.lonDeg)
                easting = unit.fromMeters(u.eastingM); northing = unit.fromMeters(u.northingM)
                zone = "UTM ${u.zone}${if (geo.latDeg >= 0) "N" else "S"}"
            }
            HorizontalSystem.STATE_PLANE -> {
                val z = SpcsZones.byEpsg(s.spcsZoneEpsg)
                    ?: SpcsZones.byEpsg(CoordinateSystemSettings.DEFAULT_SPCS_ZONE_EPSG)!!
                val (e, n) = z.projection.forward(nad.latDeg, nad.lonDeg)
                easting = unit.fromMeters(e); northing = unit.fromMeters(n); zone = "SPCS ${z.zoneCode}"
                spcsZone = z
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
            outsideZone = spcsZone?.contains(nad.latDeg, nad.lonDeg) == false,
            verticalLabel = if (s.vertical == VerticalReference.NAVD88_GEOID18)
                Geoid18Grid.verticalDatumName(nad.latDeg, nad.lonDeg) else s.vertical.shortLabel,
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
        p.height?.let { "${grouped(it, linearDecimals(p.unit))} ${p.unit.suffix} ${p.verticalLabel}" } ?: "—"

    /** Short system description: "NAD83(2011) · SPCS 3701 · ftUS · NAVD88" (+ an outside-zone warning). */
    fun systemSummary(p: ConvertedPosition): String =
        listOfNotNull(p.datum.label, p.zoneLabel, p.unit.suffix, p.verticalLabel,
            if (p.outsideZone) "outside zone" else null).joinToString(" · ")

    /** Copyable text: "N 435037.47, E 2442754.19 ftUS (NAD83(2011) SPCS 3701)" or "lat, lon". No grouping commas. */
    fun copyText(p: ConvertedPosition): String =
        if (p.easting == null || p.northing == null)
            String.format(Locale.US, "%.8f, %.8f", p.latDeg, p.lonDeg)
        else {
            val d = linearDecimals(p.unit)
            String.format(Locale.US, "N %.${d}f, E %.${d}f %s (%s %s)", p.northing, p.easting, p.unit.suffix,
                p.datum.label, p.zoneLabel.orEmpty()).trimEnd()
        }

    /** A length in [unit] with a fixed precision: "12.346 m", "40.50 ftUS". */
    fun length(meters: Double, unit: LinearUnit, decimals: Int = linearDecimals(unit)): String =
        "${grouped(unit.fromMeters(meters), decimals)} ${unit.suffix}"

    /** Signed length ("+0.42 ftUS", "−1.250 m") for stakeout deltas. */
    fun delta(meters: Double, unit: LinearUnit, decimals: Int = linearDecimals(unit)): String {
        val v = unit.fromMeters(meters)
        val s = String.format(Locale.US, "%.${decimals}f", kotlin.math.abs(v))
        return (if (v < 0 && s.any { it in '1'..'9' }) "−" else "+") + s + " " + unit.suffix
    }

    /**
     * A distance with precision that falls off with size (walking/stakeout readouts). Metres:
     * "0.42 m", "4.2 m", "42 m", "1.2 km". Feet: "1.37 ftUS", "13.7 ftUS", "137 ftUS", "1.20 mi"
     * (statute mile beyond 5,280 ft).
     */
    fun distance(meters: Double, unit: LinearUnit): String {
        if (unit == LinearUnit.METERS) return when {
            meters < 1.0 -> String.format(Locale.US, "%.2f m", meters)
            meters < 10.0 -> String.format(Locale.US, "%.1f m", meters)
            meters < 1000.0 -> String.format(Locale.US, "%.0f m", meters)
            else -> String.format(Locale.US, "%.1f km", meters / 1000.0)
        }
        val ft = unit.fromMeters(meters)
        return when {
            ft < 10.0 -> String.format(Locale.US, "%.2f %s", ft, unit.suffix)
            ft < 100.0 -> String.format(Locale.US, "%.1f %s", ft, unit.suffix)
            ft < 5280.0 -> String.format(Locale.US, "%,.0f %s", ft, unit.suffix)
            else -> String.format(Locale.US, "%.2f mi", meters / 1609.344)
        }
    }
}
