package app.surrealar.util

import app.surrealar.domain.model.EmbeddedModelLocation
import app.surrealar.domain.model.ModelLocationConfidence
import app.surrealar.util.diagnostics.DiagTags
import io.github.pennstatecs.geokit.models.georef.GeoreferenceConfidence
import io.github.pennstatecs.geokit.models.georef.PositionSpan
import java.io.File
import io.github.pennstatecs.geokit.models.georef.GlbGeoreferenceDetector as Detector

/**
 * Detects WGS84-like coordinates baked into a GLB's POSITION vertices (photogrammetry exports such as
 * Agisoft Metashape with a geographic CRS). The heuristic lives in geokit-models
 * ([Detector]); this wrapper writes the app's log lines (never coordinates) and maps the result to
 * [EmbeddedModelLocation].
 *
 * NOTE: the extracted location can be used as a placement suggestion, but AR rendering may also require
 * recentering the mesh geometry around a local origin (see [GlbGeoreferenceReprojector]).
 *
 * Must be called from a background thread (IO dispatcher).
 */
object GlbGeoreferenceDetector {

    private const val TAG = "GlbGeoreferenceDetector"

    /**
     * Attempt to detect embedded WGS84-like coordinates in [file].
     * Returns null if the file is not a GLB, cannot be parsed, or contains no plausible location.
     */
    fun detect(file: File): EmbeddedModelLocation? {
        val name = file.name
        return when (val r = Detector.detect(file)) {
            is Detector.Result.NotGlb -> {
                when (r.reason) {
                    Detector.Result.NotGlb.Reason.UNREADABLE -> Unit
                    Detector.Result.NotGlb.Reason.EXTENSION -> DevLog.d(TAG) { "$name: skipping non-GLB file" }
                    Detector.Result.NotGlb.Reason.MAGIC ->
                        DevLog.d(TAG) { "$name: not a GLB (magic 0x${(r.magic ?: 0).toString(16)})" }
                }
                null
            }
            is Detector.Result.Rejected -> {
                logRejected(name, r)
                null
            }
            is Detector.Result.NotGeoreferenced -> {
                r.span?.let { logSpan(name, it) }
                when (r.reason) {
                    Detector.Result.NotGeoreferenced.Reason.NO_POSITION ->
                        DevLog.d(TAG) { "$name: no POSITION attribute in first primitive" }
                    Detector.Result.NotGeoreferenced.Reason.NO_MIN_MAX ->
                        DevLog.d(TAG) { "$name: POSITION accessor missing min/max; skipping binary read" }
                    else -> Unit
                }
                null
            }
            is Detector.Result.Found -> {
                logSpan(name, r.span)
                val confidence = r.confidence.toDomain()
                DiagnosticsLogger.i(DiagTags.MODEL, "Model georeference detected file=$name georeferenced=true " +
                    "method=${r.method} confidence=$confidence")   // no coordinates
                EmbeddedModelLocation(
                    latitude = r.origin.latitudeDeg,
                    longitude = r.origin.longitudeDeg,
                    altitudeMeters = r.origin.heightM,
                    confidence = confidence,
                    source = r.method
                )
            }
        }
    }

    private fun logRejected(name: String, r: Detector.Result.Rejected) {
        when (r.reason) {
            Detector.Result.Rejected.Reason.UNSUPPORTED_VERSION ->
                DiagnosticsLogger.w(DiagTags.MODEL, "Model georeference detection skipped: unsupported GLB version file=$name version=${r.value}")
            Detector.Result.Rejected.Reason.FIRST_CHUNK_NOT_JSON ->
                DiagnosticsLogger.w(DiagTags.MODEL, "Model georeference detection skipped: first chunk is not JSON " +
                    "file=$name chunkType=0x${(r.value ?: 0).toString(16)}")
            Detector.Result.Rejected.Reason.JSON_CHUNK_LENGTH ->
                DiagnosticsLogger.w(DiagTags.MODEL, "Model georeference detection skipped: JSON chunk length out of safe range " +
                    "file=$name chunkBytes=${r.value}")
            Detector.Result.Rejected.Reason.TRUNCATED ->
                DiagnosticsLogger.w(DiagTags.MODEL, "Model georeference detection failed: unexpected end of file file=$name", r.cause)
            Detector.Result.Rejected.Reason.JSON_PARSE_ERROR ->
                DiagnosticsLogger.w(DiagTags.MODEL, "Model georeference detection failed: JSON parse error file=$name", r.cause)
            Detector.Result.Rejected.Reason.FAILED ->
                DiagnosticsLogger.w(DiagTags.MODEL, "Model georeference detection failed file=$name", r.cause)
        }
    }

    // No center values in logs: for a georeferenced model they are coordinates.
    private fun logSpan(name: String, s: PositionSpan) =
        DevLog.d(TAG) { "$name: POSITION spanX=%.6f spanZ=%.6f".format(s.spanX, s.spanZ) }
}

/** geokit-models confidence → the domain enum (same names). */
internal fun GeoreferenceConfidence.toDomain(): ModelLocationConfidence = when (this) {
    GeoreferenceConfidence.HIGH -> ModelLocationConfidence.HIGH
    GeoreferenceConfidence.MEDIUM -> ModelLocationConfidence.MEDIUM
    GeoreferenceConfidence.LOW -> ModelLocationConfidence.LOW
}
