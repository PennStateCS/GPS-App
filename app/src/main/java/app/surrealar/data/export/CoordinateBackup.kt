package app.surrealar.data.export

import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.Model
import app.surrealar.domain.model.validCoordinateEpochOrNull
import app.surrealar.domain.usecase.ValidateCoordinateForSaveUseCase
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full-fidelity "SurReal" coordinate backup format.
 *
 * Unlike the basic CSV/JSON export (id/name/lat/lon/alt/timestamp/icon/color), this preserves
 * every survey-quality and model-link field so a backup can be restored without data loss.
 * The file is schema-versioned so future readers can migrate older backups.
 *
 * Fields are added as optional keys without a [SCHEMA_VERSION] bump (readers ignore unknown keys
 * and treat absent ones as null/default), e.g. the v14 provenance keys `sourceFrame`,
 * `receiverFrame`, `coordinateEpoch`, `orthometricHeightSource`, `orthometricModel`.
 */
object CoordinateBackup {

    const val FORMAT_ID = "surreal-coordinate-backup"
    const val SCHEMA_VERSION = 1

    /** Export-only key naming an external capture's position reference ([app.surrealar.domain.model.PositionReferenceKind.token]). */
    const val POSITION_REFERENCE_KEY = "positionReference"

    /** Stateless; the single place that decides whether a coordinate may be persisted. */
    private val saveGate = ValidateCoordinateForSaveUseCase()

    /** Returns true when [raw] looks like a full backup (vs. the basic JSON array). */
    fun isFullBackup(raw: String): Boolean =
        runCatching { JSONObject(raw).optString("format") == FORMAT_ID }.getOrDefault(false)

    // ── Export ──────────────────────────────────────────────────────────────────

    /**
     * Serializes the full backup to a JSON string: every coordinate field plus the metadata for the
     * referenced [models] (not the model files themselves). [appVersion] is recorded for diagnostics.
     * The output is tagged with [FORMAT_ID]/[SCHEMA_VERSION] so [isFullBackup] and the importer can
     * recognize and version-migrate it.
     */
    fun export(coordinates: List<Coordinate>, models: List<Model>, appVersion: String?): String {
        val root = JSONObject()
        root.put("format", FORMAT_ID)
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("appVersion", appVersion ?: JSONObject.NULL)
        root.put("exportedAt", System.currentTimeMillis())

        val modelsArr = JSONArray()
        for (m in models) {
            modelsArr.put(JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
                put("fileName", m.fileName)
                put("fileSize", m.fileSize)
                put("checksum", m.checksum ?: JSONObject.NULL)
                putN("defaultScale", m.defaultScale)
                putN("defaultYawDeg", m.defaultYawDeg)
                putN("originOffsetXM", m.originOffsetXM)
                putN("originOffsetYM", m.originOffsetYM)
                putN("originOffsetZM", m.originOffsetZM)
                put("units", m.units ?: JSONObject.NULL)
            })
        }
        root.put("models", modelsArr)

        val arr = JSONArray()
        for (c in coordinates) arr.put(coordToJson(c))
        root.put("coordinates", arr)

        return root.toString(2)
    }

    private fun coordToJson(c: Coordinate): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("name", c.name)
        putN("note", c.note)
        putN("latitude", c.latitude)
        putN("longitude", c.longitude)
        putN("altitude", c.altitude)
        putN("altitudeMsl", c.altitudeMsl)
        putN("geoidSeparationM", c.geoidSeparationM)
        putN("antennaHeightM", c.antennaHeightM)
        put("timestamp", c.timestamp)
        put("color", c.color)
        // provenance / quality
        put("provider", c.provider)
        putN("captureMethod", c.captureMethod)
        // Export-only, derived from captureMethod (Phase 4 stabilization WP A): "antenna" / "receiver_pole_tip" /
        // "unconfirmed" for external captures, absent otherwise. Never read back (captureMethod is the source).
        app.surrealar.domain.model.PositionReferenceKind.of(c.captureMethod,
            app.surrealar.data.local.db.ProviderTokens.isExternal(c.provider))?.let { put(POSITION_REFERENCE_KEY, it.token) }
        putN("rtkStatus", c.rtkStatus)
        putN("hdop", c.hdop)
        putN("vDop", c.vDop)
        putN("pDop", c.pDop)
        putN("horizontalAccuracyM", c.horizontalAccuracyM)
        putN("verticalAccuracyM", c.verticalAccuracyM)
        putN("satsUsed", c.satsUsed)
        putN("satsVisible", c.satsVisible)
        putN("correctionSource", c.correctionSource)
        putN("correctionAgeS", c.correctionAgeS)
        putN("correctionStationId", c.correctionStationId)
        putN("speedMps", c.speedMps)
        putN("courseDeg", c.courseDeg)
        putN("timestampSource", c.timestampSource)
        putN("multipathIndex", c.multipathIndex)
        putN("crsEpsg", c.crsEpsg)
        putN("easting", c.easting)
        putN("northing", c.northing)
        putN("utmZone", c.utmZone)
        putN("averagedSamples", c.averagedSamples)
        putN("averageDurationMs", c.averageDurationMs)
        putN("stdLatM", c.stdLatM)
        putN("stdLonM", c.stdLonM)
        putN("stdAltM", c.stdAltM)
        putN("sourceDevice", c.sourceDevice)
        putN("appVersion", c.appVersion)
        // model link + icon
        put("icon", c.icon)
        putN("iconKey", c.iconKey)
        putN("modelId", c.modelId)
        put("renderEnabled", c.renderEnabled)
        put("createdAt", c.createdAt)
        put("updatedAt", c.updatedAt)
        // per-coordinate model placement
        putN("modelScale", c.modelScale)
        putN("modelYawDeg", c.modelYawDeg)
        putN("modelPitchDeg", c.modelPitchDeg)
        putN("modelRollDeg", c.modelRollDeg)
        putN("modelVerticalOffsetM", c.modelVerticalOffsetM)
        putN("modelOriginOffsetXM", c.modelOriginOffsetXM)
        putN("modelOriginOffsetYM", c.modelOriginOffsetYM)
        putN("modelOriginOffsetZM", c.modelOriginOffsetZM)
        putN("modelPlacementOrigin", c.modelPlacementOrigin)
        putN("modelMoveEastM", c.modelMoveEastM)
        putN("modelMoveNorthM", c.modelMoveNorthM)
        putN("modelMoveUpM", c.modelMoveUpM)
        // v14 provenance (optional keys; absent in older backups → null = unknown)
        putN("sourceFrame", c.sourceFrame)
        putN("receiverFrame", c.receiverFrame)
        putN("coordinateEpoch", c.coordinateEpoch)
        putN("orthometricHeightSource", c.orthometricHeightSource)
        putN("orthometricModel", c.orthometricModel)
    }

    // ── Import ──────────────────────────────────────────────────────────────────

    /** Outcome of parsing a backup file. Coordinates are only those that passed validation. */
    data class ImportResult(
        val schemaVersion: Int,
        val coordinates: List<Coordinate>,
        /** "name (id): reason" for entries dropped because they failed validation. */
        val skippedInvalid: List<String>,
        /** "name (id) → modelId" for imported coordinates whose model is absent from the backup. */
        val missingModelRefs: List<String>
    )

    /**
     * Parses a full backup. Each coordinate is validated; invalid ones are skipped and reported
     * (never silently dropped). A coordinate that references a model id not present in the
     * backup's model list is still imported but flagged in [ImportResult.missingModelRefs].
     */
    fun parse(raw: String): ImportResult {
        val root = JSONObject(raw)
        val schema = root.optInt("schemaVersion", 1)
        val backupModelIds = root.optJSONArray("models")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }
        }?.toHashSet() ?: hashSetOf()

        val coordinates = mutableListOf<Coordinate>()
        val skipped = mutableListOf<String>()
        val missingModels = mutableListOf<String>()

        val arr = root.optJSONArray("coordinates") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // One bad row (e.g. a number field holding text) is skipped and reported instead of
            // aborting the whole import. Only the exception type is kept: messages can quote values.
            val c = try {
                // A row with no altitude is skipped and reported: importing it at 0.0 m would place the
                // point ~360 m too low in PA (altitude is ellipsoidal and its column is non-null).
                if (o.optDoubleN("altitude") == null) {
                    val label = o.optString("name").ifBlank { "entry #$i" }
                    skipped += "'$label' (${o.optString("id").ifBlank { "#$i" }}): no altitude in backup"
                    continue
                }
                jsonToCoord(o)
            } catch (e: Exception) {
                skipped += "entry #$i: malformed (${e.javaClass.simpleName})"
                continue
            } ?: run {
                skipped += "entry #$i: malformed"
                null
            } ?: continue

            // The same save gate as capture, manual entry and the legacy import: CoordinateValidator plus
            // the model-placement checks (positive scale, finite angles/offsets). CoordinateValidator
            // alone let a 0 or negative modelScale in, which hides the model in AR.
            val validation = saveGate(c)
            if (!validation.isValid) {
                skipped += "'${c.name}' (${c.id}): ${validation.errors.joinToString()}"
                continue
            }
            val modelId = c.modelId
            if (!modelId.isNullOrBlank() && modelId !in backupModelIds) {
                missingModels += "'${c.name}' (${c.id}) → $modelId"
            }
            coordinates += c
        }
        return ImportResult(schema, coordinates, skipped, missingModels)
    }

    private fun jsonToCoord(o: JSONObject): Coordinate? {
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
        val name = o.optString("name", id)
        val lat = o.optDoubleN("latitude") ?: return null
        val lon = o.optDoubleN("longitude") ?: return null
        val ts = o.optLongN("timestamp") ?: System.currentTimeMillis()
        return Coordinate(
            id = id,
            name = name,
            latitude = lat,
            longitude = lon,
            altitude = o.optDoubleN("altitude") ?: return null,   // parse() reports this case first
            timestamp = ts,
            icon = o.optString("icon", ""),
            color = o.optIntN("color") ?: 0xFF155DA8.toInt(),
            provider = o.optString("provider", "fused"),
            rtkStatus = o.optStringN("rtkStatus"),
            satsUsed = o.optIntN("satsUsed"),
            satsVisible = o.optIntN("satsVisible"),
            hdop = o.optDoubleN("hdop"),
            vDop = o.optDoubleN("vDop"),
            pDop = o.optDoubleN("pDop"),
            horizontalAccuracyM = o.optDoubleN("horizontalAccuracyM"),
            verticalAccuracyM = o.optDoubleN("verticalAccuracyM"),
            correctionSource = o.optStringN("correctionSource"),
            correctionAgeS = o.optDoubleN("correctionAgeS"),
            correctionStationId = o.optStringN("correctionStationId"),
            altitudeMsl = o.optDoubleN("altitudeMsl"),
            geoidSeparationM = o.optDoubleN("geoidSeparationM"),
            antennaHeightM = o.optDoubleN("antennaHeightM"),
            speedMps = o.optDoubleN("speedMps"),
            courseDeg = o.optDoubleN("courseDeg"),
            timestampSource = o.optStringN("timestampSource"),
            multipathIndex = o.optDoubleN("multipathIndex"),
            // An explicit null (exported from a point with no recorded CRS) stays null; only a backup
            // written before the key existed gets the historical 4326 default.
            crsEpsg = if (o.has("crsEpsg")) o.optIntN("crsEpsg") else 4326,
            easting = o.optDoubleN("easting"),
            northing = o.optDoubleN("northing"),
            utmZone = o.optStringN("utmZone"),
            note = o.optStringN("note"),
            captureMethod = o.optStringN("captureMethod"),
            averagedSamples = o.optIntN("averagedSamples"),
            averageDurationMs = o.optLongN("averageDurationMs"),
            stdLatM = o.optDoubleN("stdLatM"),
            stdLonM = o.optDoubleN("stdLonM"),
            stdAltM = o.optDoubleN("stdAltM"),
            sourceDevice = o.optStringN("sourceDevice"),
            appVersion = o.optStringN("appVersion"),
            modelId = o.optStringN("modelId"),
            iconKey = o.optStringN("iconKey"),
            renderEnabled = o.optBooleanN("renderEnabled") ?: true,
            createdAt = o.optLongN("createdAt") ?: ts,
            updatedAt = o.optLongN("updatedAt") ?: ts,
            modelScale = o.optDoubleN("modelScale"),
            modelYawDeg = o.optDoubleN("modelYawDeg"),
            modelPitchDeg = o.optDoubleN("modelPitchDeg"),
            modelRollDeg = o.optDoubleN("modelRollDeg"),
            modelVerticalOffsetM = o.optDoubleN("modelVerticalOffsetM"),
            modelOriginOffsetXM = o.optDoubleN("modelOriginOffsetXM"),
            modelOriginOffsetYM = o.optDoubleN("modelOriginOffsetYM"),
            modelOriginOffsetZM = o.optDoubleN("modelOriginOffsetZM"),
            modelPlacementOrigin = o.optStringN("modelPlacementOrigin"),
            modelMoveEastM = o.optDoubleN("modelMoveEastM"),
            modelMoveNorthM = o.optDoubleN("modelMoveNorthM"),
            modelMoveUpM = o.optDoubleN("modelMoveUpM"),
            // v14 provenance: absent (older backup) or null → unknown. Never inferred on import.
            sourceFrame = o.optStringN("sourceFrame"),
            receiverFrame = o.optStringN("receiverFrame"),
            // Only a finite decimal year in 1980..2100 is kept; anything else (0.0, NaN, Infinity, text)
            // would shift or break ITRF2020 output later, so it is restored as unknown.
            coordinateEpoch = validCoordinateEpochOrNull(runCatching { o.optDoubleN("coordinateEpoch") }.getOrNull()),
            orthometricHeightSource = o.optStringN("orthometricHeightSource"),
            orthometricModel = o.optStringN("orthometricModel"),
        )
    }
}

// ── JSON null-preserving helpers ────────────────────────────────────────────────

/** Null, NaN and ±Infinity are written as JSON null (org.json throws on non-finite numbers). */
private fun JSONObject.putN(key: String, value: Any?) {
    val safe = when (value) {
        null -> JSONObject.NULL
        is Double -> if (value.isFinite()) value else JSONObject.NULL
        is Float -> if (value.isFinite()) value else JSONObject.NULL
        else -> value
    }
    put(key, safe)
}

private fun JSONObject.has2(key: String): Boolean = has(key) && !isNull(key)
private fun JSONObject.optDoubleN(key: String): Double? = if (has2(key)) getDouble(key) else null
private fun JSONObject.optIntN(key: String): Int? = if (has2(key)) getInt(key) else null
private fun JSONObject.optLongN(key: String): Long? = if (has2(key)) getLong(key) else null
private fun JSONObject.optStringN(key: String): String? = if (has2(key)) getString(key) else null
private fun JSONObject.optBooleanN(key: String): Boolean? = if (has2(key)) getBoolean(key) else null
