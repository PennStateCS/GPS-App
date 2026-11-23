package io.github.pennstatecs.geokit.convert

import io.github.pennstatecs.geokit.datum.Itrf2014ToNad83
import io.github.pennstatecs.geokit.ellipsoid.GeodeticPosition
import io.github.pennstatecs.geokit.geoid.Geoid18Grid
import io.github.pennstatecs.geokit.projection.Utm

/**
 * Converts measured positions ([SourcePosition]) into an [OutputSystem]: datum (ITRF2014 ⇄
 * NAD83(2011) at the observation epoch), geographic / UTM / State Plane, length unit and height
 * reference. Stateless and thread-safe; the geoid is injected so it can be loaded lazily (a lookup
 * that returns null while the grid is loading simply makes GEOID18 heights unavailable).
 *
 * Rules, all deliberate:
 * - State Plane is always computed from, and expressed in, NAD83(2011).
 * - UTM and geographic output use [OutputSystem.datum].
 * - GEOID18 heights are NAD83(2011) ellipsoidal height minus N looked up at the NAD83(2011) lat/lon,
 *   whatever the output datum.
 * - A source already in the needed datum is used as is (never shifted twice).
 * - An unknown ellipsoidal height gives no ellipsoidal or GEOID18 height (never 0); lat/lon are then
 *   shifted at a height of 0 m. A height of exactly 0.0 is a real height.
 * - The receiver MSL height is passed through unchanged (only converted to the unit).
 *
 * Throws [IllegalArgumentException] for a latitude/longitude out of range (from [Utm.forward]).
 */
public class CoordinateConverter(private val geoid: GeoidLookup) {

    /** Uses a loaded GEOID18 grid for orthometric heights. */
    public constructor(grid: Geoid18Grid) : this(GeoidLookup.of(grid))

    /** Converts [source] into [output]. */
    public fun convert(source: SourcePosition, output: OutputSystem): ConvertedCoordinate {
        val sourceIsNad83 = source.datum == GeodeticDatum.NAD83_2011
        val src = GeodeticPosition(source.latDeg, source.lonDeg, source.ellipsoidalHeightM ?: 0.0)
        // Each datum computed only when needed.
        val nad by lazy(LazyThreadSafetyMode.NONE) {
            if (sourceIsNad83) src else Itrf2014ToNad83.forward(src, source.epochYear)
        }
        val itrf by lazy(LazyThreadSafetyMode.NONE) {
            if (sourceIsNad83) Itrf2014ToNad83.inverse(src, source.epochYear) else src
        }

        val datum = output.effectiveDatum
        val geo = if (datum == GeodeticDatum.NAD83_2011) nad else itrf

        var eastingM: Double? = null
        var northingM: Double? = null
        var zoneLabel: String? = null
        var utmZone: Int? = null
        var utmHemisphere: Char? = null
        var outsideZone = false
        val horizontal = output.horizontal
        when (horizontal) {
            TargetSystem.Geographic -> Unit
            TargetSystem.UtmAuto -> {
                val u = Utm.forward(geo.latDeg, geo.lonDeg)
                eastingM = u.eastingM; northingM = u.northingM
                utmZone = u.zone; utmHemisphere = u.hemisphere
                zoneLabel = "UTM ${u.zone}${u.hemisphere}"
            }
            is TargetSystem.StatePlane -> {
                val z = horizontal.zone
                val (e, n) = z.projection.forward(nad.latDeg, nad.lonDeg)
                eastingM = e; northingM = n
                zoneLabel = "SPCS ${z.zoneCode}"
                outsideZone = !z.contains(nad.latDeg, nad.lonDeg)
            }
        }

        var reason: HeightUnavailableReason? = null
        val heightM: Double? = when (output.vertical) {
            VerticalReference.ELLIPSOIDAL ->
                if (source.ellipsoidalHeightM == null) { reason = HeightUnavailableReason.NO_ELLIPSOIDAL_HEIGHT; null }
                else geo.heightM
            VerticalReference.GEOID18_ORTHOMETRIC ->
                if (source.ellipsoidalHeightM == null) { reason = HeightUnavailableReason.NO_ELLIPSOIDAL_HEIGHT; null }
                else geoid.undulationM(nad.latDeg, nad.lonDeg)?.let { n -> nad.heightM - n }
                    ?: run { reason = HeightUnavailableReason.OUTSIDE_GEOID18_COVERAGE; null }
            VerticalReference.RECEIVER_MSL ->
                source.receiverMslHeightM ?: run { reason = HeightUnavailableReason.NO_RECEIVER_MSL; null }
        }

        return ConvertedCoordinate(
            system = output, datum = datum,
            latDeg = geo.latDeg, lonDeg = geo.lonDeg,
            eastingM = eastingM, northingM = northingM, zoneLabel = zoneLabel,
            utmZone = utmZone, utmHemisphere = utmHemisphere,
            spcsZone = (horizontal as? TargetSystem.StatePlane)?.zone,
            outsideZone = outsideZone,
            heightM = heightM, heightUnavailableReason = reason,
            verticalDatumName = if (output.vertical == VerticalReference.GEOID18_ORTHOMETRIC)
                Geoid18Grid.verticalDatumName(nad.latDeg, nad.lonDeg) else null,
        )
    }
}
