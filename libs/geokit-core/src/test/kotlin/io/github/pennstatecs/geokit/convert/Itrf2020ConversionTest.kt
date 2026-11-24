package io.github.pennstatecs.geokit.convert

import io.github.pennstatecs.geokit.datum.CoordinateEpoch
import io.github.pennstatecs.geokit.datum.FrameTransformationLookup
import io.github.pennstatecs.geokit.datum.FrameTransformationRegistry
import io.github.pennstatecs.geokit.datum.Itrf2014ToNad83
import io.github.pennstatecs.geokit.datum.ReferenceFrame
import io.github.pennstatecs.geokit.ellipsoid.GeodeticPosition
import io.github.pennstatecs.geokit.projection.SpcsZones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** ITRF2020 as an output and source datum of the conversion engine. */
class Itrf2020ConversionTest {

    private val converter = CoordinateConverter(GeoidLookup { _, _ -> -33.0 })
    private val epoch = 2026.9

    private fun src(datum: GeodeticDatum, lat: Double = 41.3477, lon: Double = -76.0226, h: Double? = 390.0) =
        SourcePosition(lat, lon, h, null, datum, epoch)

    @Test fun `datum metadata`() {
        assertEquals(9989, GeodeticDatum.ITRF2020.epsg3d); assertEquals(9990, GeodeticDatum.ITRF2020.epsg2d)
        assertSame(GeodeticDatum.ITRF2020, GeodeticDatum.fromEpsg(9989)); assertSame(GeodeticDatum.ITRF2020, GeodeticDatum.fromEpsg(9990))
        assertTrue(Epsg.is2d(9990))
        assertSame(ReferenceFrame.WGS84_AS_ITRF2014, GeodeticDatum.WGS84_ITRF2014.frame)
        assertSame(ReferenceFrame.NAD83_2011, GeodeticDatum.NAD83_2011.frame)
        assertSame(ReferenceFrame.ITRF2020, GeodeticDatum.ITRF2020.frame)
        // Legacy constants keep their order and values.
        assertEquals(listOf("WGS84_ITRF2014", "NAD83_2011", "ITRF2020"), GeodeticDatum.entries.map { it.name })
        assertNull(GeodeticDatum.fromEpsg(10967))
    }

    @Test fun `WGS84 (ITRF2014) source to ITRF2020 geographic matches PROJ`() {
        // PROJ 9.8.1 EPSG:7912 → EPSG:9989 at 2026.9 (coords/itrf2020_vectors.csv).
        val out = converter.convert(src(GeodeticDatum.WGS84_ITRF2014), OutputSystem(datum = GeodeticDatum.ITRF2020))
        assertSame(GeodeticDatum.ITRF2020, out.datum)
        val row = javaClass.getResourceAsStream("/coords/itrf2020_vectors.csv")!!.bufferedReader().readLines()
            .first { it.startsWith("itrf2014_to_itrf2020,41.3477,-76.0226,390.0,2026.9,") }.split(',')
        assertEquals(row[5].toDouble(), out.latDeg, 1e-11)
        assertEquals(row[6].toDouble(), out.lonDeg, 1e-11)
        assertEquals(row[7].toDouble(), out.heightM!!, 1e-5)
    }

    @Test fun `ITRF2020 source to NAD83(2011), State Plane and GEOID18 go through the verified chain`() {
        val s = src(GeodeticDatum.ITRF2020)
        val chain = (FrameTransformationRegistry.DEFAULT.find(ReferenceFrame.ITRF2020, ReferenceFrame.NAD83_2011, CoordinateEpoch(epoch))
            as FrameTransformationLookup.Available).apply(GeodeticPosition(s.latDeg, s.lonDeg, 390.0))
        val nad = converter.convert(s, OutputSystem(datum = GeodeticDatum.NAD83_2011))
        assertEquals(chain.latDeg, nad.latDeg, 0.0); assertEquals(chain.lonDeg, nad.lonDeg, 0.0); assertEquals(chain.heightM, nad.heightM!!, 0.0)

        val sp = converter.convert(s, OutputSystem(TargetSystem.StatePlane(6562), datum = GeodeticDatum.ITRF2020))
        assertSame(GeodeticDatum.NAD83_2011, sp.datum)
        val (e, n) = SpcsZones.byEpsg(6562)!!.projection.forward(chain.latDeg, chain.lonDeg)
        assertEquals(e, sp.eastingM!!, 0.0); assertEquals(n, sp.northingM!!, 0.0)

        val h = converter.convert(s, OutputSystem(datum = GeodeticDatum.ITRF2020, vertical = VerticalReference.GEOID18_ORTHOMETRIC))
        assertEquals(chain.heightM + 33.0, h.heightM!!, 0.0)   // NAD83(2011) h − N, N looked up at NAD83
        assertEquals(s.latDeg, h.latDeg, 0.0)                  // source already in ITRF2020: unchanged
    }

    @Test fun `ITRF2020 and ITRF2014 differ by millimetres, NAD83 by about a metre`() {
        val i20 = converter.convert(src(GeodeticDatum.WGS84_ITRF2014), OutputSystem(datum = GeodeticDatum.ITRF2020))
        val i14 = converter.convert(src(GeodeticDatum.ITRF2020), OutputSystem(datum = GeodeticDatum.WGS84_ITRF2014))
        assertTrue(kotlin.math.abs(i20.heightM!! - 390.0) in 1e-4..1e-2)
        assertTrue(kotlin.math.abs(i14.heightM!! - 390.0) in 1e-4..1e-2)
        // Round trip ITRF2014 → ITRF2020 → ITRF2014 through the converter.
        val back = converter.convert(SourcePosition(i20.latDeg, i20.lonDeg, i20.heightM, null, GeodeticDatum.ITRF2020, epoch),
            OutputSystem(datum = GeodeticDatum.WGS84_ITRF2014))
        assertEquals(41.3477, back.latDeg, 1e-11); assertEquals(-76.0226, back.lonDeg, 1e-11); assertEquals(390.0, back.heightM!!, 1e-6)
        // UTM on ITRF2020 vs UTM on NAD83(2011): about a metre apart (plate motion since 1997 + frame offset).
        val nad = Itrf2014ToNad83.forward(GeodeticPosition(41.3477, -76.0226, 390.0), epoch)
        assertTrue(kotlin.math.abs(nad.latDeg - i20.latDeg) > 1e-6)
    }

    @Test fun `unknown ellipsoidal height stays unknown for ITRF2020`() {
        val out = converter.convert(src(GeodeticDatum.WGS84_ITRF2014, h = null), OutputSystem(datum = GeodeticDatum.ITRF2020))
        assertNull(out.heightM)
        assertEquals(HeightUnavailableReason.NO_ELLIPSOIDAL_HEIGHT, out.heightUnavailableReason)
    }

    @Test fun `a registry without a needed transformation fails loudly`() {
        val c = CoordinateConverter(GeoidLookup.NONE, FrameTransformationRegistry(listOf(FrameTransformationRegistry.ITRF2014_TO_NAD83_2011)))
        val e = runCatching { c.convert(src(GeodeticDatum.ITRF2020), OutputSystem(datum = GeodeticDatum.NAD83_2011)) }.exceptionOrNull()
        assertTrue(e is IllegalStateException && e.message!!.contains("NOT_DEFINED"))
        // Legacy pairs still work with that registry.
        c.convert(src(GeodeticDatum.WGS84_ITRF2014), OutputSystem(datum = GeodeticDatum.NAD83_2011))
    }
}
