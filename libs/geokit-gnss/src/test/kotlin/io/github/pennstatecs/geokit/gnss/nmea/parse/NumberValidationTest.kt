package io.github.pennstatecs.geokit.gnss.nmea.parse

import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaFuser
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.Provider
import io.github.pennstatecs.geokit.gnss.nmea.sentence.GGA
import io.github.pennstatecs.geokit.gnss.nmea.sentence.GST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Non-finite or impossible numbers are "not reported", never a value: a GST σ of 0.000 must not
 * become hAccM = 0.0 (which would pass every accuracy gate), and NaN/Infinity never reach a fix.
 */
class NumberValidationTest {

    private val registry = DefaultNmeaRegistry.create(verifyChecksum = false)
    private val badSigmas = listOf("0.000", "0", "-0.5", "NaN", "Infinity", "-Infinity", "nan", "inf")

    private fun gst(sigma: String) =
        registry.parse("\$GNGST,172424.80,$sigma,$sigma,$sigma,45.0,$sigma,$sigma,$sigma") as GST

    @Test fun `GST sigmas that are zero, negative or non-finite are absent`() {
        badSigmas.forEach { s ->
            val g = gst(s)
            assertNull("rms '$s'", g.rmsStdDev)
            assertNull("major '$s'", g.stdDevMajor); assertNull("minor '$s'", g.stdDevMinor)
            assertNull("lat '$s'", g.stdDevLat); assertNull("lon '$s'", g.stdDevLon); assertNull("alt '$s'", g.stdDevAlt)
            assertEquals("orientation is still parsed", 45.0, g.orientationDeg!!, 0.0)
        }
    }

    @Test fun `GST with valid sigmas is unchanged`() {
        val g = gst("0.012")
        assertEquals(0.012, g.stdDevLat!!, 0.0); assertEquals(0.012, g.stdDevAlt!!, 0.0)
    }

    @Test fun `a zero-sigma GST gives the fix no accuracy instead of 0 m`() {
        badSigmas.forEach { s ->
            val fixes = mutableListOf<Fix>()
            val fuser = NmeaFuser(Provider.EXTERNAL_TCP, registry, onFix = { fixes += it }, onGsv = {})
            // Two epochs with a good GST make the stream GST-capable, so the last GGA waits for its GST.
            for (t in listOf("172422.80", "172423.80")) {
                fuser.accept("\$GNGGA,$t,4118.4448800,N,07601.0300517,W,4,22,0.8,395.539,M,-33.145,M,1.0,0001")
                fuser.accept("\$GNGST,$t,0.010,0.012,0.011,45.0,0.011,0.012,0.020")
            }
            fuser.accept("\$GNGGA,172424.80,4118.4448800,N,07601.0300517,W,4,22,0.8,395.539,M,-33.145,M,1.0,0001")
            fuser.accept("\$GNGST,172424.80,$s,$s,$s,45.0,$s,$s,$s")
            fuser.flush()
            assertNotNull("a good GST still gives accuracy", fixes[1].hAccM)
            val f = fixes.last()
            assertNull("hAccM for σ '$s'", f.hAccM)
            assertNull("vAccM for σ '$s'", f.vAccM)
            assertNull(f.stdDevEastM); assertNull(f.stdDevNorthM); assertNull(f.stdDevUpM)
        }
    }

    private fun gga(hdop: String, alt: String, geoid: String, age: String) = registry.parse(
        "\$GNGGA,172424.80,4118.4448800,N,07601.0300517,W,4,22,$hdop,$alt,M,$geoid,M,$age,0001"
    ) as GGA

    @Test fun `GGA drops non-finite HDOP, altitude, geoid and age and non-positive HDOP, keeping the rest`() {
        listOf("NaN", "Infinity", "-Infinity").forEach { bad ->
            val g = gga(bad, bad, bad, bad)
            assertNull("hdop '$bad'", g.hdop); assertNull("alt '$bad'", g.altMsl)
            assertNull("geoid '$bad'", g.geoidSeparation); assertNull("age '$bad'", g.diffAge)
            assertNotNull("position kept", g.lat); assertNotNull(g.lon)
            assertEquals(4, g.fixQuality); assertEquals(22, g.satsUsed)
        }
        listOf("0", "0.0", "-1.2").forEach { bad -> assertNull("hdop '$bad'", gga(bad, "395.5", "-33.1", "1.0").hdop) }
        val ok = gga("0.8", "-12.5", "-33.145", "0")          // negative altitude and zero age are real
        assertEquals(0.8, ok.hdop!!, 0.0); assertEquals(-12.5, ok.altMsl!!, 0.0)
        assertEquals(-33.145, ok.geoidSeparation!!, 0.0); assertEquals(0.0, ok.diffAge!!, 0.0)
    }
}
