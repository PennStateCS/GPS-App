package io.github.pennstatecs.geokit.convert

import io.github.pennstatecs.geokit.datum.Itrf2014ToNad83
import io.github.pennstatecs.geokit.ellipsoid.GeodeticPosition
import io.github.pennstatecs.geokit.geoid.Geoid18Grid
import io.github.pennstatecs.geokit.projection.SpcsZone
import io.github.pennstatecs.geokit.projection.Utm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Proves that routing the converter's datum steps through the frame-transformation registry left every
 * legacy combination (WGS84 (ITRF2014) / NAD83(2011) sources and outputs) bit-identical: the current
 * converter is compared with a verbatim copy of the pre-registry converter ([LegacyConverter], commit
 * 90fdf47) over many positions, epochs, systems, units and height references, with exact equality
 * (data-class equality plus raw-bit checks of every Double, i.e. delta 0.0). This is what the app's
 * CoordinateConverterGoldenTest pins.
 */
class LegacyConverterIdentityTest {

    private val legacyDatums = listOf(GeodeticDatum.WGS84_ITRF2014, GeodeticDatum.NAD83_2011)
    private val syntheticGeoid = GeoidLookup { lat, lon -> if (lat > 60.0) null else -33.0 + 0.1 * lat - 0.01 * lon }

    private fun systems(lat: Double, lon: Double): List<OutputSystem> {
        val horizontals = buildList<TargetSystem> {
            add(TargetSystem.Geographic); add(TargetSystem.UtmAuto)
            SpcsZone.suggestFor(lat, lon)?.let { add(TargetSystem.StatePlane(it.epsg)) }
        }
        return buildList {
            for (h in horizontals) for (d in legacyDatums) for (u in LengthUnit.entries) for (v in VerticalReference.entries)
                add(OutputSystem(h, d, u, v))
        }
    }

    private fun bits(x: Double?): Long? = x?.toRawBits()

    private fun assertBitIdentical(label: String, legacy: LegacyConverter, current: CoordinateConverter,
                                   src: SourcePosition, sys: OutputSystem) {
        val a = runCatching { legacy.convert(src, sys) }
        val b = runCatching { current.convert(src, sys) }
        if (a.isFailure) {
            assertEquals(label, a.exceptionOrNull()!!::class, b.exceptionOrNull()?.let { it::class })
            return
        }
        val x = a.getOrThrow(); val y = b.getOrThrow()
        assertEquals(label, x, y)
        assertEquals("$label lat", bits(x.latDeg), bits(y.latDeg))
        assertEquals("$label lon", bits(x.lonDeg), bits(y.lonDeg))
        assertEquals("$label e", bits(x.eastingM), bits(y.eastingM))
        assertEquals("$label n", bits(x.northingM), bits(y.northingM))
        assertEquals("$label h", bits(x.heightM), bits(y.heightM))
    }

    @Test fun `legacy datums give bit-identical results through the registry`() {
        val rnd = Random(20261124)
        val points = buildList {
            addAll(listOf(
                41.3477 to -76.0226, 39.7392 to -104.9903, 61.2181 to -149.9003, 71.2906 to -156.7887,
                19.8968 to -155.5828, 18.4655 to -66.1057, 25.7617 to -80.1918, 47.6062 to -122.3321,
                -33.8688 to 151.2093, 0.0 to 0.0, 83.9 to 179.99, -79.9 to -179.99,
            ))
            // Random CONUS points (State Plane paths) and random world points.
            repeat(120) { add(rnd.nextDouble(25.0, 49.0) to rnd.nextDouble(-124.0, -67.0)) }
            repeat(80) { add(rnd.nextDouble(-80.0, 84.0) to rnd.nextDouble(-180.0, 180.0)) }
        }
        val heights = listOf<Double?>(null, 0.0, 390.0, -25.0, 4205.0)
        val epochs = listOf(1997.5, 2010.0, 2015.0, 2020.0, 2026.886, 2026.9, 2035.25)
        var n = 0
        for (geoid in listOf(GeoidLookup.NONE, syntheticGeoid)) {
            val legacy = LegacyConverter(geoid)
            val current = CoordinateConverter(geoid)
            for ((i, p) in points.withIndex()) {
                val sys = systems(p.first, p.second)
                for (d in legacyDatums) for ((j, h) in heights.withIndex()) {
                    val epoch = epochs[(i + j) % epochs.size]
                    val src = SourcePosition(p.first, p.second, h, h?.let { it - 30.0 }, d, epoch)
                    for (s in sys) { assertBitIdentical("$src $s", legacy, current, src, s); n++ }
                }
            }
        }
        assertTrue("compared $n conversions", n > 50_000)
    }

    @Test fun `legacy datums are bit-identical with the real GEOID18 grid`() {
        val grid = Geoid18Grid.fromClasspath()
        val legacy = LegacyConverter(GeoidLookup.of(grid))
        val current = CoordinateConverter(grid)
        for (p in listOf(41.3477 to -76.0226, 18.4655 to -66.1057, 39.0 to -77.5, 34.0522 to -118.2437))
            for (d in legacyDatums) for (epoch in listOf(2010.0, 2026.886)) for (s in systems(p.first, p.second)) {
                val src = SourcePosition(p.first, p.second, 120.0, 150.0, d, epoch)
                assertBitIdentical("$src $s", legacy, current, src, s)
            }
    }
}

/** Verbatim copy of `CoordinateConverter.convert` before the registry (commit 90fdf47); do not edit. */
private class LegacyConverter(private val geoid: GeoidLookup) {
    fun convert(source: SourcePosition, output: OutputSystem): ConvertedCoordinate {
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
                outsideZone = io.github.pennstatecs.geokit.projection.SpcsZoneLocator.SPCS83
                    .check(z, nad.latDeg, nad.lonDeg).fit == io.github.pennstatecs.geokit.projection.SpcsZoneFit.OUTSIDE
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
