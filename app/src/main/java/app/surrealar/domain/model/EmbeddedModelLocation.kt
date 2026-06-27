package app.surrealar.domain.model

/**
 * A plausible geographic location extracted from a model file's geometry.
 *
 * Some GLB files exported from tools like Agisoft Metashape have global WGS84 coordinates
 * baked directly into POSITION vertex data rather than explicit metadata. The extracted
 * location can be offered as a placement suggestion, but AR rendering may also require
 * recentering the mesh geometry around a local origin — that is not done here.
 */
data class EmbeddedModelLocation(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val confidence: ModelLocationConfidence,
    val source: String
)

enum class ModelLocationConfidence {
    HIGH,
    MEDIUM,
    LOW
}
