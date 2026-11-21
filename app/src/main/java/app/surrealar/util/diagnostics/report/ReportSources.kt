package app.surrealar.util.diagnostics.report

import java.io.File

/**
 * Everything the report is built from. The exporter reads it from the running app; tests pass
 * fakes, so the whole ZIP can be checked (file list, redaction) without a device.
 */
interface ReportSources {
    val reportId: String
    val nowMs: Long

    suspend fun summary(): String
    suspend fun settingsSnapshot(): String
    suspend fun permissions(): String
    suspend fun appState(): String
    suspend fun coordinates(): String
    suspend fun models(): String
    fun arSession(): String
    suspend fun mapDiagnostics(): String
    suspend fun nmeaStream(): String

    fun liveState(): LiveState
    /** Saved ANR / native-crash traces (diagnostics/exits). */
    fun exitTraceFiles(): List<File>

    fun logFiles(): List<File>
    fun errorFiles(): List<File>
}