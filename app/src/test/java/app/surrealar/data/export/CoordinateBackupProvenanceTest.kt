package app.surrealar.data.export

import app.surrealar.domain.model.Coordinate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v14 provenance keys in the SurReal backup: written as optional keys, restored exactly, absent in
 * older backups (→ null = unknown, never inferred), unknown future keys ignored.
 */
// org.json is stubbed in plain JVM unit tests; Robolectric provides a real implementation.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoordinateBackupProvenanceTest {

    private val keys = listOf("sourceFrame", "receiverFrame", "coordinateEpoch", "orthometricHeightSource", "orthometricModel")

    private fun captured() = Coordinate(
        id = "cap", name = "Captured", latitude = 41.246813579246813, longitude = -75.88112233445566,
        altitude = 187.12345678901234, timestamp = 1_763_900_000_123L, icon = "ic_pin", color = 7,
        provider = "EXTERNAL_TCP", altitudeMsl = 219.4567, geoidSeparationM = -32.3332, crsEpsg = 4326,
        sourceFrame = "wgs84-as-itrf2014", receiverFrame = "nad83-2011", coordinateEpoch = Math.nextUp(2025.8986301369863),
        orthometricHeightSource = "RECEIVER_GGA", orthometricModel = null,
    )

    /** A backup as the app wrote it before v14: no provenance keys at all. */
    private val oldBackup = """{"format":"surreal-coordinate-backup","schemaVersion":1,"appVersion":"1.0","models":[],
        "coordinates":[{"id":"old","name":"Old","latitude":41.1,"longitude":-75.9,"altitude":180.5,
        "altitudeMsl":212.75,"geoidSeparationM":-32.25,"timestamp":1700000000000,"color":0,"provider":"EXTERNAL_TCP",
        "crsEpsg":6319,"icon":"ic_pin","modelMoveUpM":0.5}]}"""

    @Test
    fun export_writesEveryProvenanceKey() {
        val o = JSONObject(CoordinateBackup.export(listOf(captured()), emptyList(), "9.9"))
            .getJSONArray("coordinates").getJSONObject(0)
        for (k in keys) assertTrue("missing key $k", o.has(k))
        assertEquals("wgs84-as-itrf2014", o.getString("sourceFrame"))
        assertEquals("nad83-2011", o.getString("receiverFrame"))
        assertEquals("RECEIVER_GGA", o.getString("orthometricHeightSource"))
        assertTrue(o.isNull("orthometricModel"))
        assertEquals(CoordinateBackup.SCHEMA_VERSION, 1)   // optional keys: no format bump
    }

    @Test
    fun newBackup_restoresProvenanceExactly() {
        val original = captured()
        val back = CoordinateBackup.parse(CoordinateBackup.export(listOf(original), emptyList(), "9.9")).coordinates.single()
        assertEquals(original, back)
        assertEquals(
            java.lang.Double.doubleToRawLongBits(original.coordinateEpoch!!),
            java.lang.Double.doubleToRawLongBits(back.coordinateEpoch!!),
        )
    }

    @Test
    fun oldBackup_importsWithUnknownProvenance_andKeepsItsValues() {
        val result = CoordinateBackup.parse(oldBackup)
        assertTrue(result.skippedInvalid.isEmpty())
        val c = result.coordinates.single()
        assertNull(c.sourceFrame)
        assertNull(c.receiverFrame)
        assertNull(c.coordinateEpoch)
        assertNull(c.orthometricHeightSource)
        assertNull(c.orthometricModel)
        // Nothing is inferred from crsEpsg or the timestamp, and the stored values are untouched.
        assertEquals(6319, c.crsEpsg)
        assertEquals(180.5, c.altitude, 0.0)
        assertEquals(212.75, c.altitudeMsl!!, 0.0)
        assertEquals(0.5, c.modelMoveUpM!!, 0.0)
    }

    @Test
    fun oldBackup_reExported_thenReimported_staysUnknown() {
        val fromOld = CoordinateBackup.parse(oldBackup).coordinates
        val again = CoordinateBackup.parse(CoordinateBackup.export(fromOld, emptyList(), "9.9")).coordinates
        assertEquals(fromOld, again)
        assertNull(again.single().sourceFrame)
    }

    @Test
    fun implausibleCoordinateEpoch_isRestoredAsUnknown() {
        // Review LOW-3: only a finite decimal year in 1980..2100 is kept; the point itself is restored.
        fun epochOf(raw: String): Double? {
            val json = """{"format":"surreal-coordinate-backup","schemaVersion":1,"coordinates":[
                {"id":"e","name":"E","latitude":41.0,"longitude":-76.0,"altitude":300.0,"timestamp":5,"color":0,
                 "icon":"","coordinateEpoch":$raw}]}"""
            val result = CoordinateBackup.parse(json)
            assertTrue(raw, result.skippedInvalid.isEmpty())
            return result.coordinates.single().coordinateEpoch
        }
        for (bad in listOf("0", "0.0", "-2026.5", "1979.99", "2100.5", "1e300", "\"NaN\"", "\"Infinity\"", "\"-Infinity\"", "\"soon\"")) {
            assertNull(bad, epochOf(bad))
        }
        assertEquals(1980.0, epochOf("1980")!!, 0.0)
        assertEquals(2026.886, epochOf("2026.886")!!, 0.0)
        assertEquals(2100.0, epochOf("2100.0")!!, 0.0)
    }

    /** Phase 4 stabilization WP A: the position-reference capture methods round-trip; the derived key is export-only. */
    @Test
    fun positionReference_captureMethodsRoundTrip_andTheDerivedKeyIsExportOnly() {
        val unconfirmed = captured().copy(id = "u", captureMethod = "EXTERNAL_GNSS_REFERENCE_UNCONFIRMED", antennaHeightM = 1.895)
        val tip = captured().copy(id = "t", captureMethod = "EXTERNAL_GNSS_POLE_TIP", antennaHeightM = null)
        val antenna = captured().copy(id = "a", captureMethod = "external_gnss", antennaHeightM = 1.934)
        val internal = captured().copy(id = "i", provider = "fused", captureMethod = "internal_gps")
        val json = CoordinateBackup.export(listOf(unconfirmed, tip, antenna, internal), emptyList(), "9.9")
        val arr = JSONObject(json).getJSONArray("coordinates")
        assertEquals("unconfirmed", arr.getJSONObject(0).getString(CoordinateBackup.POSITION_REFERENCE_KEY))
        assertEquals("receiver_pole_tip", arr.getJSONObject(1).getString(CoordinateBackup.POSITION_REFERENCE_KEY))
        assertEquals("antenna", arr.getJSONObject(2).getString(CoordinateBackup.POSITION_REFERENCE_KEY))
        assertTrue("absent for non-external points", !arr.getJSONObject(3).has(CoordinateBackup.POSITION_REFERENCE_KEY))
        val back = CoordinateBackup.parse(json)
        assertTrue(back.skippedInvalid.isEmpty())
        assertEquals(listOf(unconfirmed, tip, antenna, internal), back.coordinates)
        // A backup from before WP A (no key) and a hand-edited key that contradicts captureMethod: captureMethod wins.
        val edited = json.replace("\"unconfirmed\"", "\"antenna\"")
        assertEquals("EXTERNAL_GNSS_REFERENCE_UNCONFIRMED", CoordinateBackup.parse(edited).coordinates.first().captureMethod)
        assertNull(CoordinateBackup.parse(oldBackup).coordinates.single().captureMethod)
    }

    @Test
    fun explicitNulls_andUnknownFutureKeys_areAccepted() {
        // Unknown keys at this app's schema version are forward-compatible by contract (a NEWER schema version is
        // rejected: CoordinateBackupCompatibilityTest).
        val json = """{"format":"surreal-coordinate-backup","schemaVersion":1,"futureRootKey":{"x":1},"coordinates":[
            {"id":"f","name":"Future","latitude":41.0,"longitude":-76.0,"altitude":300.0,"timestamp":5,"color":0,
             "icon":"","sourceFrame":null,"receiverFrame":null,"coordinateEpoch":null,"orthometricHeightSource":null,
             "orthometricModel":null,"sourceFrameRealisation":"g2296","heightModelVersion":7,"futureNested":{"a":[1,2]}},
            {"id":"g","name":"Future2","latitude":41.0,"longitude":-76.0,"altitude":300.0,"timestamp":5,"color":0,
             "icon":"","sourceFrame":"itrf2020","coordinateEpoch":2030.25,"orthometricModel":"GEOID2022"}]}"""
        val result = CoordinateBackup.parse(json)
        assertTrue(result.skippedInvalid.isEmpty())
        val (f, g) = result.coordinates
        assertNull(f.sourceFrame); assertNull(f.receiverFrame); assertNull(f.coordinateEpoch)
        assertNull(f.orthometricHeightSource); assertNull(f.orthometricModel)
        // Tokens the app doesn't know yet are kept verbatim (restore must not lose them).
        assertEquals("itrf2020", g.sourceFrame)
        assertEquals(2030.25, g.coordinateEpoch!!, 0.0)
        assertEquals("GEOID2022", g.orthometricModel)
        assertNull(g.receiverFrame)
    }
}
