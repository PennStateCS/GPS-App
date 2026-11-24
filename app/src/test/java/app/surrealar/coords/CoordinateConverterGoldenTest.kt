package app.surrealar.coords

import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.HorizontalSystem
import app.surrealar.settings.model.LinearUnit
import app.surrealar.settings.model.OutputDatum
import app.surrealar.settings.model.VerticalReference
import io.github.pennstatecs.geokit.geoid.Geoid18Grid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Golden regression test for [CoordinateConverter]: the output recorded from the converter as it was
 * before the engine moved to geokit-core (commit 94ec713) must not change by a single bit.
 *
 * Inputs are deterministic: points across PA, other CONUS states, AK (TM and HOM zones), PR, VI, HI,
 * UTM zone edges, the equator and the southern hemisphere; heights null / 0 / negative / positive;
 * receiver MSL present or not; both source datums; three epochs. Settings cover every
 * [HorizontalSystem] (incl. several SPCS zones, a point outside its zone and an unknown zone EPSG that
 * falls back to the default), every pre-Phase-2 [OutputDatum] and [VerticalReference], and every [LinearUnit].
 *
 * The golden file holds (a) one readable row per (source, horizontal system) with datum, unit and
 * vertical reference rotating, every field of [ConvertedPosition], doubles as their exact shortest representation; and
 * (b) one SHA-256 per point over the FULL settings cross product, so every combination is covered
 * bit-exactly without a huge file.
 *
 * To regenerate (only if a numeric change is intended and reviewed): set GOLDEN_WRITE to the
 * absolute path of the resource file and run this test.
 */
class CoordinateConverterGoldenTest {

    private val geoid: Geoid18Grid by lazy { Geoid18Grid.fromClasspath() }
    private val converter by lazy { CoordinateConverter { lat, lon -> geoid.undulation(lat, lon) } }

    private data class Pt(val name: String, val lat: Double, val lon: Double, val h: Double)

    private val points = listOf(
        Pt("PA-north", 41.3477, -76.0226, 390.0),
        Pt("PA-south", 40.0, -77.5, 150.0),
        Pt("PA-sw-corner", 39.72, -80.52, 300.0),
        Pt("CA-LA", 34.0522, -118.2437, 60.0),
        Pt("WA-Seattle", 47.6062, -122.3321, 40.0),
        Pt("FL-Miami", 25.7617, -80.1918, -25.0),
        Pt("TX-central", 31.0, -100.0, 600.0),
        Pt("CO-Denver", 39.7392, -104.9903, 1600.0),
        Pt("AK-Anchorage", 61.2181, -149.9003, 30.0),
        Pt("AK-Juneau", 58.3019, -134.4197, 10.0),
        Pt("PR-SanJuan", 18.4655, -66.1057, -30.0),
        Pt("VI-StThomas", 18.3419, -64.9307, -20.0),
        Pt("HI-Honolulu", 21.3069, -157.8583, 20.0),
        Pt("UTM-edge-west", 40.0, -78.0000001, 200.0),
        Pt("UTM-edge-exact", 40.0, -78.0, 200.0),
        Pt("UTM-edge-east", 40.0, -77.9999999, 200.0),
        Pt("equator", 0.0, -78.0, 10.0),
        Pt("just-south", -0.0001, -78.0001, 10.0),
        Pt("Sydney", -33.8688, 151.2093, 50.0),
        Pt("Norway", 60.5, 4.5, 100.0),
    )

    private data class Variant(val h: (Pt) -> Double?, val msl: Double?, val nad: Boolean, val epoch: Double)

    private val variants = listOf(
        Variant({ it.h }, 424.0, false, 2026.886),
        Variant({ null }, null, false, 2026.886),
        Variant({ 0.0 }, 0.0, true, 2010.0),
        Variant({ -35.2 }, -2.5, true, 2035.5),
        Variant({ it.h }, null, true, 2026.886),
        Variant({ 0.0 }, 12.0, false, 2010.0),
    )

    /** GEOGRAPHIC, UTM, then State Plane zones (99999 = unknown EPSG → default zone fallback). */
    private val horizontals: List<Pair<HorizontalSystem, Int>> =
        listOf(HorizontalSystem.GEOGRAPHIC to CoordinateSystemSettings.DEFAULT_SPCS_ZONE_EPSG,
            HorizontalSystem.UTM to CoordinateSystemSettings.DEFAULT_SPCS_ZONE_EPSG) +
            listOf(6562, 6564, 6423, 6596, 6437, 6577, 6427, 6394, 6397, 6566, 99999)
                .map { HorizontalSystem.STATE_PLANE to it }

    /**
     * The settings that existed when the golden file was recorded, in their original enum order. Phase 2
     * appended OutputDatum.ITRF2020 and VerticalReference.EGM2008 / EGM96; they are covered by
     * CoordinateConverterGlobalGeoidTest, and this test keeps proving that the old settings are unchanged
     * (iterating over `entries` would silently fold the new values into the old digests).
     */
    private val legacyDatums = listOf(OutputDatum.WGS84, OutputDatum.NAD83_2011)
    private val legacyVerticals =
        listOf(VerticalReference.ELLIPSOIDAL, VerticalReference.NAVD88_GEOID18, VerticalReference.RECEIVER_MSL)

    private val datumUnits = legacyDatums.flatMap { d -> LinearUnit.entries.map { d to it } }

    private fun source(p: Pt, v: Variant) =
        SourcePosition(p.lat, p.lon, v.h(p), v.msl, isNad83 = v.nad, epochYear = v.epoch)

    private fun settings(h: Pair<HorizontalSystem, Int>, d: OutputDatum, u: LinearUnit, vr: VerticalReference) =
        CoordinateSystemSettings(horizontal = h.first, spcsZoneEpsg = h.second, datum = d, unit = u, vertical = vr)

    private fun d(v: Double?) = v?.toString() ?: "null"

    private fun row(c: ConvertedPosition): String = listOf(
        c.horizontal.name, c.datum.name, c.unit.name, d(c.latDeg), d(c.lonDeg), d(c.easting), d(c.northing),
        c.zoneLabel ?: "null", c.vertical.name, d(c.height), c.heightUnavailableReason ?: "null",
        c.outsideZone.toString(), c.verticalLabel,
    ).joinToString("\t")

    private fun generate(): List<String> {
        val out = ArrayList<String>()
        for ((pi, p) in points.withIndex()) {
            val digest = MessageDigest.getInstance("SHA-256")
            for ((vi, v) in variants.withIndex()) {
                val src = source(p, v)
                // (a) readable sample.
                for ((hi, h) in horizontals.withIndex()) {
                    val (dt, u) = datumUnits[(pi + vi + hi) % datumUnits.size]
                    val vr = legacyVerticals[(pi + 2 * vi + hi) % legacyVerticals.size]
                    out += "${p.name}\tv$vi\th$hi\t" + row(converter.convert(src, settings(h, dt, u, vr)))
                }
                // (b) full cross product, digested.
                for (h in horizontals) for ((dt, u) in datumUnits) for (vr in legacyVerticals) {
                    digest.update((row(converter.convert(src, settings(h, dt, u, vr))) + "\n").toByteArray())
                }
            }
            out += "#digest\t${p.name}\t" + digest.digest().joinToString("") { "%02x".format(it) }
        }
        return out
    }

    @Test fun `converter output matches the recorded golden values bit for bit`() {
        val actual = generate()
        System.getenv("GOLDEN_WRITE")?.let { path ->
            File(path).writeText(actual.joinToString("\n", postfix = "\n"))
            return
        }
        val stream = javaClass.classLoader!!.getResourceAsStream(RESOURCE)
        assertNotNull("missing golden resource $RESOURCE", stream)
        val expected = stream!!.bufferedReader().readLines().filter { it.isNotBlank() }
        assertEquals("row count", expected.size, actual.size)
        for (i in expected.indices) assertEquals("golden row $i", expected[i], actual[i])
    }

    private companion object {
        const val RESOURCE = "coords/converter_golden.tsv"
    }
}
