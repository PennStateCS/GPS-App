package app.surrealar.data.repository.impl

import app.surrealar.data.files.ModelFileCleaner
import app.surrealar.data.local.dao.ModelDao
import app.surrealar.data.local.entity.ModelEntity
import app.surrealar.domain.model.BoundingBox
import app.surrealar.domain.model.Model
import app.surrealar.domain.model.FileType
import app.surrealar.domain.repository.ModelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * [ModelRepository] backed by [ModelDao]. The boundary between [ModelEntity] rows and domain `Model`
 * objects: it maps every persisted field (including file type, bounding box, and placement/health
 * metadata) in both directions and must not drop data on round-trip. It owns model-file lifecycle via
 * `ModelFileCleaner`; deleting a model removes both the row and its file.
 */
class ModelRepositoryImpl @javax.inject.Inject constructor(private val modelDao: ModelDao) : ModelRepository {

    private fun getFileTypeFromExtension(fileName: String): FileType {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "csv", "kml", "gpx" -> FileType.COORDINATE_DATA
            "nmea", "log" -> FileType.NMEA_LOG
            "dwg", "dxf" -> FileType.CAD_DRAWING
            "jpg", "jpeg", "png", "tiff", "tif" -> FileType.IMAGE
            "las", "laz" -> FileType.POINT_CLOUD
            "obj", "ply", "stl" -> FileType.MESH_MODEL
            "pdf" -> FileType.REPORT
            "db", "sqlite", "sql" -> FileType.DATABASE
            "json", "xml", "cfg", "ini" -> FileType.CONFIGURATION
            else -> FileType.OTHER
        }
    }

    override fun observeModelCount(): kotlinx.coroutines.flow.Flow<Int> = modelDao.observeModelCount()

    override fun getAllModels(): Flow<List<Model>> = modelDao.getAllModels().map { entities ->
        entities.map { it.toModel() }
    }

    override suspend fun getModelById(id: String): Model? =
        modelDao.getModelById(id)?.toModel()

    override suspend fun getModelByFileName(fileName: String): Model? =
        modelDao.getModelByFileName(fileName)?.toModel()

    override suspend fun insertModel(model: Model) {
        modelDao.insertModel(model.toEntity())
    }

    override suspend fun updateModel(model: Model) {
        modelDao.updateModel(model.toEntity())
    }

    override suspend fun deleteModel(model: Model) {
        // Delete thumbnail file from disk first (if it exists). The imported model file is removed
        // by the model-list UI; both delegate to ModelFileCleaner. See docs/data-architecture.md.
        ModelFileCleaner.deleteThumbnailFile(model.thumbnailFilePath)
        modelDao.deleteModel(model.toEntity())
    }

    // ── Entity <-> domain mapping (single source of truth) ──────────────────────

    private fun ModelEntity.toModel(): Model = Model(
        id = id,
        name = name,
        fileName = fileName,
        filePath = filePath,
        fileSize = fileSize,
        dateAdded = dateAdded,
        description = description,
        fileType = getFileTypeFromExtension(fileName),
        thumbnailFileName = thumbnailFileName,
        thumbnailFilePath = thumbnailFilePath,
        embeddedLatitude = embeddedLatitude,
        embeddedLongitude = embeddedLongitude,
        embeddedAltitudeM = embeddedAltitudeM,
        // v10 health + placement metadata
        checksum = checksum,
        isValid = isValid,
        validationErrors = decodeValidationErrors(validationErrorsJson),
        boundingBox = decodeBoundingBox(boundingBoxJson),
        defaultScale = defaultScale,
        defaultYawDeg = defaultYawDeg,
        originOffsetXM = originOffsetXM,
        originOffsetYM = originOffsetYM,
        originOffsetZM = originOffsetZM,
        units = units
    )

    private fun Model.toEntity(): ModelEntity = ModelEntity(
        id = id,
        name = name,
        fileName = fileName,
        filePath = filePath,
        fileSize = fileSize,
        dateAdded = dateAdded,
        description = description,
        thumbnailFileName = thumbnailFileName,
        thumbnailFilePath = thumbnailFilePath,
        embeddedLatitude = embeddedLatitude,
        embeddedLongitude = embeddedLongitude,
        embeddedAltitudeM = embeddedAltitudeM,
        // v10 health + placement metadata
        checksum = checksum,
        isValid = isValid,
        validationErrorsJson = encodeValidationErrors(validationErrors),
        boundingBoxJson = encodeBoundingBox(boundingBox),
        defaultScale = defaultScale,
        defaultYawDeg = defaultYawDeg,
        originOffsetXM = originOffsetXM,
        originOffsetYM = originOffsetYM,
        originOffsetZM = originOffsetZM,
        units = units
    )

    private fun encodeValidationErrors(errors: List<String>): String? =
        if (errors.isEmpty()) null else JSONArray(errors).toString()

    private fun decodeValidationErrors(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun encodeBoundingBox(bb: BoundingBox?): String? = bb?.let {
        JSONObject().apply {
            put("minLat", it.minLat); put("maxLat", it.maxLat)
            put("minLon", it.minLon); put("maxLon", it.maxLon)
        }.toString()
    }

    private fun decodeBoundingBox(json: String?): BoundingBox? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(json)
            BoundingBox(o.getDouble("minLat"), o.getDouble("maxLat"), o.getDouble("minLon"), o.getDouble("maxLon"))
        }.getOrNull()
    }

}
