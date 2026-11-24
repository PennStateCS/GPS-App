package app.surrealar.data.export

import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.FileType
import app.surrealar.domain.model.Model
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Full-fidelity guarantees of the SurReal backup format: every field survives export -> parse exactly
 * (doubles bit-for-bit, nulls stay null), defaults apply only to absent keys, and damaged or foreign
 * input is rejected as a whole instead of yielding a partial result.
 */
// org.json is stubbed in plain JVM unit tests; Robolectric provides a real implementation.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoordinateBackupFidelityTest {

    /** Every field set to a distinctive, non-default value (awkward doubles on purpose). */
    private fun everyField(id: String = "c-full") = Coordinate(
        id = id, name = "Ctrl \"PT\" 7, north/east", latitude = 41.246813579246813, longitude = -75.88112233445566,
        altitude = 187.12345678901234, timestamp = 1_763_900_000_123L, icon = "model:m-1", color = 0x80FF00AA.toInt(),
        provider = "EXTERNAL_TCP", rtkStatus = "FLOAT", satsUsed = 19, satsVisible = 33,
        hdop = 0.61, vDop = 0.83, pDop = 1.0300000000000002,
        horizontalAccuracyM = 0.0123, verticalAccuracyM = 0.0217,
        correctionSource = "NTRIP", correctionAgeS = 1.7, correctionStationId = "PAWB",
        altitudeMsl = 219.4567, geoidSeparationM = -32.3332, antennaHeightM = 1.8,
        speedMps = 0.004, courseDeg = 359.99, timestampSource = "NMEA_RMC", multipathIndex = 0.37,
        crsEpsg = 6318, easting = 2_455_123.456, northing = 389_765.4321, utmZone = "18T",
        note = "line1\nline2 ° ✓", captureMethod = "AVERAGED",
        averagedSamples = 600, averageDurationMs = 5_000_000_000L,
        stdLatM = 0.004, stdLonM = 0.005, stdAltM = 0.009,
        sourceDevice = "Emlid Reach RS2+", appVersion = "2.3.1-debug",
        modelId = "m-1", iconKey = "ic_star", renderEnabled = false,
        createdAt = 1_763_900_000_001L, updatedAt = 1_763_900_999_999L,
        modelScale = 0.25, modelYawDeg = -137.5, modelPitchDeg = 2.25, modelRollDeg = -1.125,
        modelVerticalOffsetM = -0.75, modelOriginOffsetXM = 12.5, modelOriginOffsetYM = -3.25, modelOriginOffsetZM = 0.0625,
        modelPlacementOrigin = "CUSTOM",
        modelMoveEastM = 0.1, modelMoveNorthM = -0.2, modelMoveUpM = 0.30000000000000004,
        sourceFrame = "wgs84-as-itrf2014", receiverFrame = "nad83-2011", coordinateEpoch = 2026.8986301369863,
        orthometricHeightSource = "RECEIVER_GGA", orthometricModel = "EGM2008",
    )

    /** Only the required fields; every nullable one null. */
    private fun minimal(id: String = "c-min") = Coordinate(
        id = id, name = "Bare", latitude = 40.0, longitude = -77.0, altitude = 300.0,
        timestamp = 42L, icon = "ic_pin", color = 7, crsEpsg = 4326,
    )

    private fun model(id: String = "m-1") = Model(
        id = id, name = "Bridge", fileName = "bridge.glb", filePath = "/models/bridge.glb",
        fileSize = 123_456_789_012L, dateAdded = 1L, fileType = FileType.MESH_MODEL,
        checksum = "sha256:abc", defaultScale = 0.001, defaultYawDeg = 12.5,
        originOffsetXM = 1.5, originOffsetYM = -2.5, originOffsetZM = 3.5, units = "mm",
    )

    private fun roundTrip(vararg c: Coordinate, models: List<Model> = listOf(model())): CoordinateBackup.ImportResult =
        CoordinateBackup.parse(CoordinateBackup.export(c.toList(), models, "9.9"))

    private fun bits(d: Double?) = d?.let { java.lang.Double.doubleToRawLongBits(it) }

    // ── round trip ──────────────────────────────────────────────────────────────

    @Test
    fun everyField_roundTripsToAnEqualCoordinate() {
        val original = everyField()
        val result = roundTrip(original)
        assertTrue(result.skippedInvalid.isEmpty())
        assertTrue(result.missingModelRefs.isEmpty())
        assertEquals(original, result.coordinates.single())
    }

    @Test
    fun positionDoubles_areBitExact() {
        val original = everyField().copy(
            latitude = Math.nextUp(41.25), longitude = Math.nextDown(-75.875), altitude = 0.1 + 0.2,
            altitudeMsl = 1e-12, geoidSeparationM = -33.000000000000007,
        )
        val back = roundTrip(original).coordinates.single()
        assertEquals(bits(original.latitude), bits(back.latitude))
        assertEquals(bits(original.longitude), bits(back.longitude))
        assertEquals(bits(original.altitude), bits(back.altitude))
        assertEquals(bits(original.altitudeMsl), bits(back.altitudeMsl))
        assertEquals(bits(original.geoidSeparationM), bits(back.geoidSeparationM))
        assertEquals(bits(original.easting), bits(back.easting))
        assertEquals(bits(original.northing), bits(back.northing))
    }

    @Test
    fun extremeButValidPositions_roundTrip() {
        val poles = listOf(
            minimal("n").copy(latitude = 90.0, longitude = 180.0, altitude = 8_848.86),
            minimal("s").copy(latitude = -90.0, longitude = -180.0, altitude = -430.5),
        )
        val back = roundTrip(*poles.toTypedArray()).coordinates
        assertEquals(poles, back)
    }

    @Test
    fun zeroAltitude_isKept_asZero_notTreatedAsMissing() {
        val back = roundTrip(minimal().copy(altitude = 0.0)).coordinates.single()
        assertEquals(0.0, back.altitude, 0.0)
    }

    @Test
    fun nullOptionals_stayNull_andZeroOptionals_stayZero() {
        val nulls = roundTrip(minimal()).coordinates.single()
        assertEquals(minimal(), nulls)
        assertNull(nulls.altitudeMsl)
        assertNull(nulls.antennaHeightM)
        assertNull(nulls.modelScale)
        assertNull(nulls.modelVerticalOffsetM)
        assertNull(nulls.modelPlacementOrigin)
        assertNull(nulls.modelMoveEastM)

        val zeros = minimal("z").copy(
            altitudeMsl = 0.0, antennaHeightM = 0.0, modelYawDeg = 0.0, modelVerticalOffsetM = 0.0,
            modelMoveEastM = 0.0, modelMoveNorthM = 0.0, modelMoveUpM = 0.0, satsUsed = 0, averagedSamples = 0,
        )
        assertEquals(zeros, roundTrip(zeros).coordinates.single())
    }

    @Test
    fun crsAndProjectedFields_roundTrip() {
        val back = roundTrip(everyField()).coordinates.single()
        assertEquals(6318, back.crsEpsg)
        assertEquals("18T", back.utmZone)
        assertEquals("NMEA_RMC", back.timestampSource)
    }

    @Test
    fun modelPlacementFields_roundTrip_forEveryPlacementOriginToken() {
        for (origin in listOf("ORIGIN", "CENTER", "BOTTOM_CENTER", "CUSTOM")) {
            val c = everyField("c-$origin").copy(modelPlacementOrigin = origin)
            val back = roundTrip(c).coordinates.single()
            assertEquals(origin, back.modelPlacementOrigin)
            assertEquals(c, back)
        }
    }

    @Test
    fun providerTokens_includingLegacyAliases_areKeptVerbatim() {
        val tokens = listOf(
            "INTERNAL", "EXTERNAL_TCP", "EXTERNAL_BT", "REPLAY", "MODEL", "OTHER", "fused",
            "RS2_EXTERNAL", "RS2_TCP", "RS2_BT", "rs2-external", "rs2-tcp",
        )
        val coords = tokens.mapIndexed { i, t -> minimal("p$i").copy(provider = t) }
        val back = roundTrip(*coords.toTypedArray()).coordinates
        assertEquals(tokens, back.map { it.provider })
    }

    @Test
    fun largeLongs_andNegativeColor_areExact() {
        val c = everyField().copy(
            timestamp = Long.MAX_VALUE / 2, averageDurationMs = Long.MAX_VALUE / 3,
            createdAt = 9_007_199_254_740_993L, updatedAt = 9_007_199_254_740_995L, color = Int.MIN_VALUE,
        )
        val back = roundTrip(c).coordinates.single()
        assertEquals(c.timestamp, back.timestamp)
        assertEquals(c.averageDurationMs, back.averageDurationMs)
        assertEquals(c.createdAt, back.createdAt)   // > 2^53: must not pass through a double
        assertEquals(c.updatedAt, back.updatedAt)
        assertEquals(Int.MIN_VALUE, back.color)
    }

    @Test
    fun manyRows_keepOrderAndCount() {
        val rows = (0 until 250).map { i -> minimal("r$i").copy(latitude = 40.0 + i * 1e-6, name = "P$i") }
        val back = roundTrip(*rows.toTypedArray()).coordinates
        assertEquals(rows, back)
    }

    @Test
    fun emptyBackup_roundTripsToNothing() {
        val json = CoordinateBackup.export(emptyList(), emptyList(), null)
        assertTrue(CoordinateBackup.isFullBackup(json))
        val result = CoordinateBackup.parse(json)
        assertTrue(result.coordinates.isEmpty())
        assertTrue(result.skippedInvalid.isEmpty())
        assertTrue(result.missingModelRefs.isEmpty())
    }

    // ── envelope / model metadata ───────────────────────────────────────────────

    @Test
    fun envelope_carriesFormatVersionAndAppVersion() {
        val before = System.currentTimeMillis()
        val root = JSONObject(CoordinateBackup.export(listOf(minimal()), emptyList(), "4.5.6"))
        assertEquals(CoordinateBackup.FORMAT_ID, root.getString("format"))
        assertEquals(CoordinateBackup.SCHEMA_VERSION, root.getInt("schemaVersion"))
        assertEquals("4.5.6", root.getString("appVersion"))
        assertTrue(root.getLong("exportedAt") >= before)

        val noVersion = JSONObject(CoordinateBackup.export(emptyList(), emptyList(), null))
        assertTrue(noVersion.isNull("appVersion"))
    }

    @Test
    fun modelMetadata_isExported_withNullsAndNonFiniteAsJsonNull() {
        val m = model().copy(checksum = null, units = null, defaultScale = Double.NaN)
        val root = JSONObject(CoordinateBackup.export(emptyList(), listOf(model("m-2"), m), null))
        val arr = root.getJSONArray("models")
        assertEquals(2, arr.length())

        val full = arr.getJSONObject(0)
        assertEquals("m-2", full.getString("id"))
        assertEquals("Bridge", full.getString("name"))
        assertEquals("bridge.glb", full.getString("fileName"))
        assertEquals(123_456_789_012L, full.getLong("fileSize"))
        assertEquals("sha256:abc", full.getString("checksum"))
        assertEquals(0.001, full.getDouble("defaultScale"), 0.0)
        assertEquals(12.5, full.getDouble("defaultYawDeg"), 0.0)
        assertEquals(1.5, full.getDouble("originOffsetXM"), 0.0)
        assertEquals(-2.5, full.getDouble("originOffsetYM"), 0.0)
        assertEquals(3.5, full.getDouble("originOffsetZM"), 0.0)
        assertEquals("mm", full.getString("units"))
        assertFalse("model file path is not part of the backup", full.has("filePath"))

        val sparse = arr.getJSONObject(1)
        assertTrue(sparse.isNull("checksum"))
        assertTrue(sparse.isNull("units"))
        assertTrue(sparse.isNull("defaultScale"))
    }

    @Test
    fun nullCoordinateFields_areWrittenAsExplicitJsonNull() {
        val row = JSONObject(CoordinateBackup.export(listOf(minimal()), emptyList(), null))
            .getJSONArray("coordinates").getJSONObject(0)
        for (key in listOf("note", "altitudeMsl", "antennaHeightM", "modelScale", "modelPlacementOrigin", "modelMoveUpM", "modelId")) {
            assertTrue("$key present", row.has(key))
            assertTrue("$key null", row.isNull(key))
        }
    }

    @Test
    fun modelPresentInBackup_isNotReportedMissing_butBlankModelIdIsIgnored() {
        val linked = everyField("linked")
        val blank = minimal("blank").copy(modelId = "")
        val result = roundTrip(linked, blank, models = listOf(model("m-1")))
        assertEquals(2, result.coordinates.size)
        assertTrue(result.missingModelRefs.isEmpty())

        val missing = CoordinateBackup.parse(CoordinateBackup.export(listOf(linked), listOf(model("other")), null))
        assertEquals(listOf("'${linked.name}' (linked) → m-1"), missing.missingModelRefs)
    }

    // ── isFullBackup ────────────────────────────────────────────────────────────

    @Test
    fun isFullBackup_recognizesOnlyTheTaggedObject() {
        assertTrue(CoordinateBackup.isFullBackup(CoordinateBackup.export(emptyList(), emptyList(), null)))
        assertFalse(CoordinateBackup.isFullBackup("""[{"id":"a","latitude":41.0,"longitude":-76.0}]"""))
        assertFalse(CoordinateBackup.isFullBackup("""{"format":"some-other-app","coordinates":[]}"""))
        assertFalse(CoordinateBackup.isFullBackup("""{"coordinates":[]}"""))
        assertFalse(CoordinateBackup.isFullBackup(""))
        assertFalse(CoordinateBackup.isFullBackup("not json at all"))
        assertFalse(CoordinateBackup.isFullBackup("""{"format":"surreal-coordinate-backup""""))
    }

    // ── versions ────────────────────────────────────────────────────────────────

    @Test
    fun schemaVersion_isReported_andDefaultsTo1() {
        assertEquals(1, roundTrip(minimal()).schemaVersion)
        assertEquals(1, CoordinateBackup.parse("""{"coordinates":[]}""").schemaVersion)
        assertEquals(7, CoordinateBackup.parse("""{"schemaVersion":7,"coordinates":[]}""").schemaVersion)
    }

    @Test
    fun olderBackupWithoutNewerKeys_getsTheDocumentedDefaults() {
        // A row written before antenna height / placement origin / move offset / audit fields existed.
        val json = """{"format":"surreal-coordinate-backup","schemaVersion":1,"coordinates":[
            {"id":"old","name":"Old","latitude":41.1,"longitude":-75.9,"altitude":200.5,"timestamp":5000}
        ]}"""
        val c = CoordinateBackup.parse(json).coordinates.single()
        assertEquals(41.1, c.latitude, 0.0)
        assertEquals(200.5, c.altitude, 0.0)
        assertEquals(5000L, c.timestamp)
        assertEquals("fused", c.provider)
        assertEquals("", c.icon)
        assertEquals(0xFF155DA8.toInt(), c.color)
        assertEquals(4326, c.crsEpsg)
        assertTrue(c.renderEnabled)
        assertEquals(5000L, c.createdAt)
        assertEquals(5000L, c.updatedAt)
        assertNull(c.antennaHeightM)
        assertNull(c.modelPlacementOrigin)
        assertNull(c.modelMoveEastM)
        assertNull(c.modelId)
    }

    @Test
    fun missingIdAndName_areFilledIn() {
        val json = """{"coordinates":[{"latitude":41.1,"longitude":-75.9,"altitude":200.5,"timestamp":1}]}"""
        val c = CoordinateBackup.parse(json).coordinates.single()
        assertTrue(c.id.isNotBlank())
        assertEquals(c.id, c.name)
        val again = CoordinateBackup.parse(json).coordinates.single()
        assertNotEquals("generated ids are unique", c.id, again.id)
    }

    @Test
    fun missingTimestamp_usesImportTime() {
        val before = System.currentTimeMillis()
        val json = """{"coordinates":[{"id":"t","name":"T","latitude":41.1,"longitude":-75.9,"altitude":1.0}]}"""
        val c = CoordinateBackup.parse(json).coordinates.single()
        assertTrue(c.timestamp >= before)
        assertEquals(c.timestamp, c.createdAt)
    }

    // ── rejection ───────────────────────────────────────────────────────────────

    @Test
    fun malformedText_throws_withoutPartialResult() {
        assertThrows(JSONException::class.java) { CoordinateBackup.parse("not json") }
        assertThrows(JSONException::class.java) { CoordinateBackup.parse("") }
        // A JSON array (the legacy basic format) is not a full backup object.
        assertThrows(JSONException::class.java) { CoordinateBackup.parse("""[{"id":"a"}]""") }
    }

    @Test
    fun truncatedBackup_throws_insteadOfImportingTheRowsBeforeTheCut() {
        val full = CoordinateBackup.export(listOf(everyField("a"), everyField("b"), everyField("c")), listOf(model()), null)
        for (cut in listOf(full.length / 3, full.length / 2, full.length - 2)) {
            assertThrows("cut at $cut", JSONException::class.java) { CoordinateBackup.parse(full.substring(0, cut)) }
        }
    }

    @Test
    fun foreignJsonObject_yieldsNothing() {
        val result = CoordinateBackup.parse("""{"type":"FeatureCollection","features":[{"type":"Feature"}]}""")
        assertTrue(result.coordinates.isEmpty())
        assertTrue(result.skippedInvalid.isEmpty())
    }

    @Test
    fun rowsMissingLatOrLon_areReportedMalformed() {
        val json = """{"coordinates":[
            {"id":"nolat","name":"A","longitude":-75.9,"altitude":1.0},
            {"id":"nolon","name":"B","latitude":41.1,"altitude":1.0},
            {"id":"ok","name":"C","latitude":41.1,"longitude":-75.9,"altitude":1.0}
        ]}"""
        val result = CoordinateBackup.parse(json)
        assertEquals(listOf("ok"), result.coordinates.map { it.id })
        assertEquals(listOf("entry #0: malformed", "entry #1: malformed"), result.skippedInvalid)
    }

    @Test
    fun outOfRangeOrBlankName_isSkippedWithTheValidatorReason() {
        val json = """{"coordinates":[
            {"id":"lat","name":"TooNorth","latitude":90.5,"longitude":-75.9,"altitude":1.0},
            {"id":"lon","name":"TooEast","latitude":41.0,"longitude":180.5,"altitude":1.0},
            {"id":"noname","name":"  ","latitude":41.0,"longitude":-75.9,"altitude":1.0}
        ]}"""
        val result = CoordinateBackup.parse(json)
        assertTrue(result.coordinates.isEmpty())
        assertEquals(3, result.skippedInvalid.size)
        assertTrue(result.skippedInvalid[0].startsWith("'TooNorth' (lat): Latitude"))
        assertTrue(result.skippedInvalid[1].startsWith("'TooEast' (lon): Longitude"))
        assertTrue(result.skippedInvalid[2].contains("name cannot be empty"))
    }

    @Test
    fun nonPositiveModelScale_isSkippedLikeEverySaveGate() {
        // Capture, manual entry and the legacy import reject these; the full backup must too.
        val result = roundTrip(
            everyField("zero").copy(modelScale = 0.0),
            everyField("neg").copy(modelScale = -1.0),
            everyField("ok"),
            minimal("noscale"),                       // null scale = not overridden, still valid
        )
        assertEquals(listOf("ok", "noscale"), result.coordinates.map { it.id })
        assertEquals(2, result.skippedInvalid.size)
        assertTrue(result.skippedInvalid[0], result.skippedInvalid[0].startsWith("'${everyField().name}' (zero): "))
        assertTrue(result.skippedInvalid.all { "Model scale must be a positive number" in it })
    }

    @Test
    fun explicitNullCrs_staysNull_butAMissingKeyGets4326() {
        val back = roundTrip(minimal("nocrs").copy(crsEpsg = null)).coordinates.single()
        assertNull(back.crsEpsg)
        val exported = CoordinateBackup.export(listOf(minimal("nocrs").copy(crsEpsg = null)), emptyList(), null)
        assertTrue(JSONObject(exported).getJSONArray("coordinates").getJSONObject(0).isNull("crsEpsg"))
        // Backups written before the key existed keep the historical default.
        val old = """{"coordinates":[{"id":"o","name":"O","latitude":41.1,"longitude":-75.9,"altitude":1.0}]}"""
        assertEquals(4326, CoordinateBackup.parse(old).coordinates.single().crsEpsg)
        // NAD83 / other codes are unaffected.
        assertEquals(6318, roundTrip(everyField()).coordinates.single().crsEpsg)
    }

    @Test
    fun noAltitudeRow_withoutNameOrId_isLabelledByIndex() {
        val json = """{"coordinates":[{"latitude":41.1,"longitude":-75.9}]}"""
        assertEquals(listOf("'entry #0' (#0): no altitude in backup"), CoordinateBackup.parse(json).skippedInvalid)
    }

    @Test
    fun wrongTypedFields_areMalformed_andTheReasonDoesNotQuoteTheValue() {
        val json = """{"coordinates":[
            {"id":"a","name":"A","latitude":41.1,"longitude":-75.9,"altitude":1.0,"timestamp":"secret-text"},
            {"id":"b","name":"B","latitude":41.1,"longitude":-75.9,"altitude":1.0,"renderEnabled":"maybe"},
            {"id":"c","name":"C","latitude":41.1,"longitude":-75.9,"altitude":"high"}
        ]}"""
        val result = CoordinateBackup.parse(json)
        assertTrue(result.coordinates.isEmpty())
        assertEquals(3, result.skippedInvalid.size)
        assertTrue(result.skippedInvalid.all { it.contains("malformed (") })
        assertTrue(result.skippedInvalid.none { "secret-text" in it || "maybe" in it || "high" in it })
    }

    @Test
    fun numericStrings_areAccepted_asOrgJsonCoercesThem() {
        val json = """{"coordinates":[{"id":"s","name":"S","latitude":"41.5","longitude":"-75.5","altitude":"12.25","satsUsed":"14"}]}"""
        val c = CoordinateBackup.parse(json).coordinates.single()
        assertEquals(41.5, c.latitude, 0.0)
        assertEquals(-75.5, c.longitude, 0.0)
        assertEquals(12.25, c.altitude, 0.0)
        assertEquals(14, c.satsUsed)
    }
}
