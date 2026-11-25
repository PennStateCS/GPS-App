package app.surrealar.gnss.diagnostics.raw

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The app-private recordings folder (`filesDir/nmea_recordings/`): naming, listing and deletion. Nothing
 * here uploads or copies files anywhere; sharing goes through the Android share sheet only.
 */
class RawNmeaFiles(private val directory: () -> File) {

    data class Recording(
        val nmeaFile: File,
        val metadataFile: File,
        val sizeBytes: Long,
        val modifiedMs: Long,
        /** Null when the companion file is missing or unreadable. */
        val summary: RawNmeaMetadataJson.Summary?,
    )

    fun dir(): File = directory().also { it.mkdirs() }

    /** `GNSS_2026-11-25_143012_RS4Pro.nmea` (device local time); a numeric suffix avoids collisions. */
    fun newRecordingFile(startedAtMs: Long, zone: ZoneId, label: String): File {
        val stamp = STAMP.format(Instant.ofEpochMilli(startedAtMs).atZone(zone))
        val base = "GNSS_${stamp}_${sanitizeLabel(label)}"
        val d = dir()
        var f = File(d, "$base.nmea")
        var n = 2
        while (f.exists() || metadataFor(f).exists()) { f = File(d, "${base}_$n.nmea"); n++ }
        return f
    }

    fun metadataFor(nmea: File): File = File(nmea.parentFile, nmea.name.removeSuffix(".nmea") + ".json")

    /** Newest first. */
    fun list(): List<Recording> {
        val files = directory().listFiles { f -> f.isFile && f.name.endsWith(".nmea") } ?: return emptyList()
        return files.map { f ->
            val meta = metadataFor(f)
            val summary = if (meta.isFile) runCatching { RawNmeaMetadataJson.readSummary(meta.readText()) }.getOrNull() else null
            Recording(f, meta, f.length(), f.lastModified(), summary)
        }.sortedByDescending { it.nmeaFile.name }
    }

    /** Deletes the recording and its companion file; true when neither remains. */
    fun delete(nmea: File): Boolean {
        if (nmea.parentFile?.canonicalFile != directory().canonicalFile) return false
        val meta = metadataFor(nmea)
        nmea.delete(); meta.delete()
        return !nmea.exists() && !meta.exists()
    }

    companion object {
        const val DIR_NAME = "nmea_recordings"
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

        /** Letters and digits only ("RS4 Pro" → "RS4Pro", "RS2+" → "RS2Plus"); blank → "Receiver". */
        fun sanitizeLabel(label: String): String {
            val s = label.replace("+", "Plus").filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }.take(24)
            return s.ifEmpty { "Receiver" }
        }
    }
}
