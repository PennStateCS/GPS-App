package app.surrealar.data.export

import android.content.Context
import android.os.Environment
import app.surrealar.gnss.model.Fix
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Writes captured fixes to a GeoJSON `FeatureCollection` file for use in GIS tools.
 *
 * Like [CsvExporter] this is a lossy interchange export, not a backup: it carries point geometry and
 * basic properties only. Use `CoordinateBackup` when full-fidelity restore is needed. Returns the
 * written [File].
 */
class GeoJsonExporter {

    fun exportToGeoJson(context: Context, fixes: List<Fix>): File {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val fileName = "surveying_data_$timestamp.geojson"

        val documentsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "SurveyingApp")
        if (!documentsDir.exists()) {
            documentsDir.mkdirs()
        }

        val file = File(documentsDir, fileName)

        val geoJson = createGeoJson(fixes)

        FileWriter(file).use { writer ->
            writer.write(geoJson.toString(2))
        }

        return file
    }

    private fun createGeoJson(fixes: List<Fix>): JSONObject {
        val geoJson = JSONObject()
        geoJson.put("type", "FeatureCollection")

        val features = JSONArray()

        fixes.forEach { fix ->
            val feature = JSONObject()
            feature.put("type", "Feature")

            // Geometry
            val geometry = JSONObject()
            geometry.put("type", "Point")
            val coordinates = JSONArray()
            coordinates.put(fix.lonDeg)
            coordinates.put(fix.latDeg)
            fix.altEllipsoidalM?.let { coordinates.put(it) }
            geometry.put("coordinates", coordinates)
            feature.put("geometry", geometry)

            // Properties
            val properties = JSONObject()
            properties.put("timestamp", fix.timeUtc.toString())
            properties.put("provider", fix.provider.name)
            properties.put("timestampSource", fix.timestampSource.name)
            properties.put("rtkStatus", fix.rtkStatus.name)
            properties.put("satsUsed", fix.satsUsed)

            fix.altMslM?.let { properties.put("altitudeMSL", it) }
            fix.geoidSeparationM?.let { properties.put("geoidSeparation", it) }
            fix.hDop?.let { properties.put("hDop", it) }
            fix.vDop?.let { properties.put("vDop", it) }
            fix.pDop?.let { properties.put("pDop", it) }
            fix.hAccM?.let { properties.put("horizontalAccuracy", it) }
            fix.vAccM?.let { properties.put("verticalAccuracy", it) }
            fix.satsVisible?.let { properties.put("satsVisible", it) }
            fix.diffAgeS?.let { properties.put("diffAge", it) }
            fix.speedMps?.let { properties.put("speed", it) }
            fix.courseDeg?.let { properties.put("course", it) }

            feature.put("properties", properties)
            features.put(feature)
        }

        geoJson.put("features", features)
        return geoJson
    }
}
