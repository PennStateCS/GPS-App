package app.surrealar.util.diagnostics.report

import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes report entries and remembers each name and size (for manifest.txt). Every text goes
 * through [scrub]; [textSafe] never lets one failing section break the export.
 */
class ZipBuilder(private val zos: ZipOutputStream, private val scrub: (String) -> String = { it }) {

    private val _entries = mutableListOf<Pair<String, Long>>()
    val entries: List<Pair<String, Long>> get() = _entries

    fun text(name: String, content: String) {
        val bytes = scrub(content).toByteArray(Charsets.UTF_8)
        zos.putNextEntry(ZipEntry(name))
        zos.write(bytes)
        zos.closeEntry()
        _entries += name to bytes.size.toLong()
    }

    suspend fun textSafe(name: String, build: suspend () -> String) {
        val content = runCatching { build() }.getOrElse {
            DiagnosticsLogger.w(DiagTags.EXPORT, "section $name failed: ${it.message}", it)
            "Section failed to generate: ${it.javaClass.simpleName}: ${it.message}\n"
        }
        text(name, content)
    }

    /** Copies [file] line by line through [scrub], keeping only lines [keep] accepts. */
    fun file(name: String, file: File, keep: (String) -> Boolean = { true }) {
        runCatching {
            zos.putNextEntry(ZipEntry(name))
            var size = 0L
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (!keep(line)) continue
                    val bytes = (scrub(line) + "\n").toByteArray(Charsets.UTF_8)
                    zos.write(bytes); size += bytes.size
                }
            }
            zos.closeEntry()
            _entries += name to size
        }.onFailure { DiagnosticsLogger.w(DiagTags.EXPORT, "file $name failed: ${it.message}") }
    }
}