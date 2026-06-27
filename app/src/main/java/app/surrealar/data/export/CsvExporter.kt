package app.surrealar.data.export

import android.content.Context
import android.os.Environment
import app.surrealar.gnss.model.Fix
import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Writes captured fixes to a CSV file under the shared Documents folder for sharing/inspection.
 *
 * This is a lossy, human-readable export — it does not preserve full survey/model metadata and is not
 * a backup. For round-trippable backups use `CoordinateBackup`. Returns the written [File].
 */
class CsvExporter {

    fun exportToCsv(context: Context, fixes: List<Fix>): File {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val fileName = "surveying_data_$timestamp.csv"

        val documentsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "SurveyingApp")
        if (!documentsDir.exists()) {
            documentsDir.mkdirs()
        }

        val file = File(documentsDir, fileName)

        FileWriter(file).use { writer ->
            // Write header
            writer.write(getCsvHeader())
            writer.write("\n")

            // Write data rows
            fixes.forEach { fix ->
                writer.write(fixToCsvRow(fix))
                writer.write("\n")
            }
        }

        return file
    }

    private fun getCsvHeader(): String {
        return "timestamp,provider,timestampSource,latitude,longitude,altitudeEllipsoidal," +
                "altitudeMSL,geoidSeparation,hDop,vDop,pDop,horizontalAccuracy," +
                "verticalAccuracy,rtkStatus,satsUsed,satsVisible,diffAge,speed,course"
    }

    private fun fixToCsvRow(fix: Fix): String {
        return listOf(
            fix.timeUtc.toString(),
            fix.provider.name,
            fix.timestampSource.name,
            fix.latDeg.toString(),
            fix.lonDeg.toString(),
            fix.altEllipsoidalM?.toString() ?: "",
            fix.altMslM?.toString() ?: "",
            fix.geoidSeparationM?.toString() ?: "",
            fix.hDop?.toString() ?: "",
            fix.vDop?.toString() ?: "",
            fix.pDop?.toString() ?: "",
            fix.hAccM?.toString() ?: "",
            fix.vAccM?.toString() ?: "",
            fix.rtkStatus.name,
            fix.satsUsed.toString(),
            fix.satsVisible?.toString() ?: "",
            fix.diffAgeS?.toString() ?: "",
            fix.speedMps?.toString() ?: "",
            fix.courseDeg?.toString() ?: ""
        ).joinToString(",") { escapeForCsv(it) }
    }

    private fun escapeForCsv(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}
