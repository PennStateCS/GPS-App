package app.surrealar.util.diagnostics.incident

import app.surrealar.BuildConfig
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The "Mark Problem" incidents: one JSON object per line in `diagnostics/incidents.jsonl` (next to
 * the event logs), so they survive restarts. Keeps the newest [maxEntries]. A corrupt or partial line
 * (e.g. power lost mid-write) is skipped and counted, never fatal. Thread-safe; [mark] does its file
 * work on [io]. [recent] reads the file on the calling thread, so call it off the main thread.
 *
 * Only the INCIDENT_MARKED line (id, screen, session) goes to the event log; the tester's note
 * stays in this file.
 */
class IncidentStore(
    private val dir: () -> File?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val newId: () -> String = ::randomId,
    private val sessionId: () -> String? = { DiagnosticsLogger.sessionId },
    private val screen: () -> String? = { DiagnosticsLogger.currentScreen },
    private val appVersion: () -> String? = { "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" },
    private val maxEntries: Int = MAX_ENTRIES,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = { DiagnosticsLogger.i(DiagTags.DIAG, it) },
) {

    /** Lines skipped as unreadable by the last read. */
    @Volatile var corruptLinesSkipped: Int = 0
        private set

    /** Saves a new incident and logs INCIDENT_MARKED. Throws [IOException] if it can't be written. */
    suspend fun mark(description: String?, context: IncidentContext): IncidentMarker = withContext(io) {
        val now = clock()
        val z = zone()
        val instant = Instant.ofEpochMilli(now)
        val marker = IncidentMarker(
            id = newId(),
            epochMs = now,
            utcTime = DateTimeFormatter.ISO_INSTANT.format(instant),
            localTime = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atZone(z)),
            zoneId = z.id,
            sessionId = sessionId(),
            appVersion = appVersion(),
            screen = screen(),
            description = IncidentMarker.cleanDescription(description),
            context = context,
        )
        val file = file() ?: throw IOException("diagnostics folder unavailable")
        synchronized(LOCK) {
            file.parentFile?.mkdirs()
            // A partial last line (write cut off) must not swallow the new record.
            val prefix = if (endsMidLine(file)) "\n" else ""
            file.appendText(prefix + IncidentJson.encode(marker) + "\n", Charsets.UTF_8)
            compactIfNeeded(file)
        }
        log("INCIDENT_MARKED id=${marker.id} screen=${IncidentContext.safeText(marker.screen) ?: "unknown"}" +
            " session=${IncidentContext.safeText(marker.sessionId) ?: "unknown"}" +
            " description=${marker.description != null}")
        marker
    }

    /** Up to [limit] incidents, newest first. Empty when there are none or the file can't be read. */
    fun recent(limit: Int = maxEntries): List<IncidentMarker> {
        if (limit <= 0) return emptyList()
        val file = file() ?: return emptyList()
        val all = synchronized(LOCK) { readAll(file) }
        return all.takeLast(limit).reversed()
    }

    /** The newest incident, or null. */
    fun latest(): IncidentMarker? = recent(1).firstOrNull()

    private fun file(): File? = dir()?.let { File(it, FILE_NAME) }

    private fun endsMidLine(file: File): Boolean {
        val len = file.length()
        if (len == 0L) return false
        java.io.RandomAccessFile(file, "r").use { raf -> raf.seek(len - 1); return raf.read() != '\n'.code }
    }

    private fun readAll(file: File): List<IncidentMarker> {
        if (!file.exists()) { corruptLinesSkipped = 0; return emptyList() }
        var corrupt = 0
        val out = ArrayList<IncidentMarker>()
        try {
            file.forEachLine(Charsets.UTF_8) { line ->
                if (line.isBlank()) return@forEachLine
                val m = if (line.length > MAX_LINE_CHARS) null else IncidentJson.decode(line)
                if (m == null) corrupt++ else out += m
            }
        } catch (e: IOException) {
            DiagnosticsLogger.w(DiagTags.DIAG, "Incident file read failed", e)
        }
        corruptLinesSkipped = corrupt
        return out
    }

    /** Rewrites the file with only the newest [maxEntries] readable lines once it holds more. */
    private fun compactIfNeeded(file: File) {
        val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        if (lines.size <= maxEntries) return
        val keep = readAll(file).takeLast(maxEntries)
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(keep.joinToString("") { IncidentJson.encode(it) + "\n" }, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("incident file rewrite failed")
        }
    }

    companion object {
        const val FILE_NAME = "incidents.jsonl"
        const val MAX_ENTRIES = 20
        private const val MAX_LINE_CHARS = 32_000
        private val LOCK = Any()
        private const val ID_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"   // Crockford base32
        private val random = SecureRandom()

        fun randomId(): String = String(CharArray(8) { ID_ALPHABET[random.nextInt(ID_ALPHABET.length)] })

        /** The app's store, in DiagnosticsLogger's diagnostics folder (`filesDir/diagnostics`). */
        val shared: IncidentStore by lazy { IncidentStore(dir = { DiagnosticsLogger.diagnosticsDir() }) }
    }
}

/** JSON-lines codec for [IncidentMarker]. Unknown keys are ignored; a bad line decodes to null. */
internal object IncidentJson {

    fun encode(m: IncidentMarker): String {
        val c = m.context
        val ctx = JSONObject()
            .putOpt("gnssSource", c.gnssSource).putOpt("receiverProfile", c.receiverProfile)
            .putOpt("receiverLink", c.receiverLink).putOpt("corrections", c.corrections)
            .putOpt("rtkStatus", c.rtkStatus).putOpt("fixAgeMs", c.fixAgeMs)
            .putOpt("hAccM", c.hAccM).putOpt("vAccM", c.vAccM).putOpt("correctionAgeS", c.correctionAgeS)
            .putOpt("arSessionActive", c.arSessionActive).putOpt("arCameraTracking", c.arCameraTracking)
            .putOpt("arEarthTracking", c.arEarthTracking).putOpt("arModelsInScene", c.arModelsInScene)
            .putOpt("arModelsLoading", c.arModelsLoading).putOpt("freeStorageBytes", c.freeStorageBytes)
            .putOpt("cameraPermission", c.cameraPermission).putOpt("fineLocationPermission", c.fineLocationPermission)
            .putOpt("notificationPermission", c.notificationPermission)
        return JSONObject()
            .put("v", 1).put("id", m.id).put("epochMs", m.epochMs).put("utc", m.utcTime)
            .put("local", m.localTime).put("zone", m.zoneId)
            .putOpt("session", m.sessionId).putOpt("appVersion", m.appVersion).putOpt("screen", m.screen)
            .putOpt("description", m.description).put("context", ctx)
            .toString()
    }

    fun decode(line: String): IncidentMarker? = try {
        val o = JSONObject(line)
        val c = o.optJSONObject("context") ?: JSONObject()
        IncidentMarker(
            id = o.getString("id"),
            epochMs = o.getLong("epochMs"),
            utcTime = o.getString("utc"),
            localTime = o.optString("local", ""),
            zoneId = o.optString("zone", ""),
            sessionId = o.str("session"),
            appVersion = o.str("appVersion"),
            screen = o.str("screen"),
            description = o.str("description"),
            context = IncidentContext(
                gnssSource = c.str("gnssSource"), receiverProfile = c.str("receiverProfile"),
                receiverLink = c.str("receiverLink"), corrections = c.str("corrections"),
                rtkStatus = c.str("rtkStatus"), fixAgeMs = c.long("fixAgeMs"),
                hAccM = c.dbl("hAccM"), vAccM = c.dbl("vAccM"), correctionAgeS = c.dbl("correctionAgeS"),
                arSessionActive = c.bool("arSessionActive"), arCameraTracking = c.str("arCameraTracking"),
                arEarthTracking = c.str("arEarthTracking"), arModelsInScene = c.long("arModelsInScene")?.toInt(),
                arModelsLoading = c.long("arModelsLoading")?.toInt(), freeStorageBytes = c.long("freeStorageBytes"),
                cameraPermission = c.bool("cameraPermission"), fineLocationPermission = c.bool("fineLocationPermission"),
                notificationPermission = c.bool("notificationPermission"),
            ),
        )
    } catch (e: Exception) {
        null
    }

    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
    private fun JSONObject.long(k: String): Long? = if (has(k) && !isNull(k)) getLong(k) else null
    private fun JSONObject.dbl(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null
    private fun JSONObject.bool(k: String): Boolean? = if (has(k) && !isNull(k)) getBoolean(k) else null
}
