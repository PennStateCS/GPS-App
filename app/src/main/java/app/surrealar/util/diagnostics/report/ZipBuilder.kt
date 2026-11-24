package app.surrealar.util.diagnostics.report

import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import java.io.BufferedReader
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes report entries and remembers each name and size (for manifest.txt). Every text goes
 * through [scrub]; [textSafe] never lets one failing section break the export.
 *
 * Failures reading a *source* (an unreadable log, an unsafe file name) are recorded in [omitted] /
 * [truncated] and the export goes on. Failures writing the *ZIP* (disk full, closed stream) propagate:
 * the ZIP is broken and the caller must discard it.
 */
class ZipBuilder(private val zos: ZipOutputStream, private val scrub: (String) -> String = { it }) {

    private val _entries = mutableListOf<Pair<String, Long>>()
    val entries: List<Pair<String, Long>> get() = _entries
    private val _omitted = mutableListOf<OmittedFile>()
    val omitted: List<OmittedFile> get() = _omitted
    private val _truncated = mutableListOf<TruncatedFile>()
    val truncated: List<TruncatedFile> get() = _truncated

    fun text(name: String, content: String) {
        val bytes = scrub(content).toByteArray(Charsets.UTF_8)
        zos.putNextEntry(ZipEntry(name))
        zos.write(bytes)
        zos.closeEntry()
        _entries += name to bytes.size.toLong()
    }

    suspend fun textSafe(name: String, build: suspend () -> String) {
        val content = ReportFailures.catching { build() }.getOrElse {
            DiagnosticsLogger.w(DiagTags.EXPORT, "Report section failed section=$name", it)
            "Section failed to generate: ${ReportFailures.describe(it)}\n"
        }
        text(name, content)
    }

    /** Decides, line by line, what of a source file is written: [emit] zero or more lines per input line. */
    interface LineFilter {
        fun onLine(line: String, emit: (String) -> Unit)
    }

    /**
     * Copies [file] line by line through [scrub], keeping only lines [keep] accepts. [name] comes from
     * outside text (a file name) and is made safe with [safeEntryName]. [filterReason] (set when [keep]
     * drops lines by policy) records the file as truncated with its original and included sizes.
     * Returns whether the entry was written.
     */
    fun file(name: String, file: File, keep: (String) -> Boolean = { true }, filterReason: String? = null): Boolean =
        file(name, file, object : LineFilter {
            override fun onLine(line: String, emit: (String) -> Unit) { if (keep(line)) emit(line) }
        }, filterReason?.let { TruncationNote(it) })

    /**
     * Copies [file] through [filter] (which may drop lines or add marker lines) and [scrub]. [note]
     * (set when the filter reduces the file by policy) records it as an expected truncation.
     */
    fun file(name: String, file: File, filter: LineFilter, note: TruncationNote?): Boolean {
        val safe = safeEntryName(name)
        if (safe == null) return omit(name.take(MAX_ENTRY_NAME), "unsafe file name")
        if (_entries.any { it.first == safe }) return omit(safe, "duplicate name")
        val reader: BufferedReader = try {
            file.bufferedReader(Charsets.UTF_8)
        } catch (e: Exception) {
            DiagnosticsLogger.w(DiagTags.EXPORT, "Report file copy failed file=$safe", e)
            return omit(safe, "unreadable: ${e.javaClass.simpleName}")
        }
        var size = 0L
        var readError: Exception? = null
        var writeError: Throwable? = null
        val emit: (String) -> Unit = { out ->
            val bytes = (scrub(out) + "\n").toByteArray(Charsets.UTF_8)
            try { zos.write(bytes) } catch (e: Throwable) { writeError = e; throw e }
            size += bytes.size
        }
        try {
            zos.putNextEntry(ZipEntry(safe))                      // ZIP write errors propagate
            while (true) {
                // Reading, filtering and scrubbing are the source side: a failure there cuts the copy
                // short. A failure writing the ZIP propagates.
                try {
                    val line = reader.readLine() ?: break
                    filter.onLine(line, emit)
                } catch (e: Exception) {
                    if (e is kotlin.coroutines.cancellation.CancellationException || e === writeError) throw e
                    readError = e; break
                }
            }
            zos.closeEntry()
        } finally {
            runCatching { reader.close() }                        // best effort: the copy is done or failing anyway
        }
        _entries += safe to size
        val original = runCatching { file.length() }.getOrDefault(-1L)
        readError?.let {
            DiagnosticsLogger.w(DiagTags.EXPORT, "Report file copy cut short file=$safe bytes=$size", it)
            _truncated += TruncatedFile(safe, original, size, "read failed: ${it.javaClass.simpleName}", expected = false)
        } ?: note?.let { _truncated += TruncatedFile(safe, original, size, it.reason, expected = true,
            originalSpan = it.originalSpan, includedSpan = it.includedSpan, omittedSpans = it.omittedSpans, omittedLines = it.omittedLines) }
        return true
    }

    private fun omit(name: String, reason: String): Boolean {
        _omitted += OmittedFile(name, reason)
        return false
    }

    companion object {
        const val MAX_ENTRY_NAME = 120
        private val UNSAFE = Regex("[^A-Za-z0-9._/-]")

        /**
         * A ZIP entry name built from outside text: only `A-Z a-z 0-9 . _ - /`, no `..`, no empty or
         * leading `/` segments, at most [MAX_ENTRY_NAME] chars. Null when nothing usable is left.
         */
        fun safeEntryName(raw: String): String? {
            val cleaned = UNSAFE.replace(raw, "_")
                .replace(Regex("\\.{2,}"), "_")
                .split('/').filter { it.isNotEmpty() && it != "." }
                .joinToString("/")
                .take(MAX_ENTRY_NAME)
                .trimEnd('/')
            return cleaned.takeIf { it.isNotEmpty() && it.any { c -> c.isLetterOrDigit() } }
        }
    }
}
