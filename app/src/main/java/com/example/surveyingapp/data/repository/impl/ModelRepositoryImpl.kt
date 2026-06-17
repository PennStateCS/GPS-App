package com.example.surveyingapp.data.repository.impl

import com.example.surveyingapp.data.local.dao.ModelDao
import com.example.surveyingapp.data.local.entity.ModelEntity
import com.example.surveyingapp.domain.model.Model
import com.example.surveyingapp.domain.model.FileType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ModelRepositoryImpl(private val modelDao: ModelDao) {

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

    fun getAllModels(): Flow<List<Model>> = modelDao.getAllModels().map { entities ->
        entities.map { entity ->
            Model(
                id = entity.id,
                name = entity.name,
                fileName = entity.fileName,
                filePath = entity.filePath,
                fileSize = entity.fileSize,
                dateAdded = entity.dateAdded,
                description = entity.description,
                fileType = getFileTypeFromExtension(entity.fileName),
                thumbnailFileName = entity.thumbnailFileName,
                thumbnailFilePath = entity.thumbnailFilePath,
                embeddedLatitude = entity.embeddedLatitude,
                embeddedLongitude = entity.embeddedLongitude,
                embeddedAltitudeM = entity.embeddedAltitudeM
            )
        }
    }

    suspend fun getModelById(id: String): Model? {
        return modelDao.getModelById(id)?.let { entity ->
            Model(
                id = entity.id,
                name = entity.name,
                fileName = entity.fileName,
                filePath = entity.filePath,
                fileSize = entity.fileSize,
                dateAdded = entity.dateAdded,
                description = entity.description,
                fileType = getFileTypeFromExtension(entity.fileName),
                thumbnailFileName = entity.thumbnailFileName,
                thumbnailFilePath = entity.thumbnailFilePath,
                embeddedLatitude = entity.embeddedLatitude,
                embeddedLongitude = entity.embeddedLongitude,
                embeddedAltitudeM = entity.embeddedAltitudeM
            )
        }
    }

    suspend fun getModelByFileName(fileName: String): Model? {
        return modelDao.getModelByFileName(fileName)?.let { entity ->
            Model(
                id = entity.id,
                name = entity.name,
                fileName = entity.fileName,
                filePath = entity.filePath,
                fileSize = entity.fileSize,
                dateAdded = entity.dateAdded,
                description = entity.description,
                fileType = getFileTypeFromExtension(entity.fileName),
                thumbnailFileName = entity.thumbnailFileName,
                thumbnailFilePath = entity.thumbnailFilePath,
                embeddedLatitude = entity.embeddedLatitude,
                embeddedLongitude = entity.embeddedLongitude,
                embeddedAltitudeM = entity.embeddedAltitudeM
            )
        }
    }

    suspend fun insertModel(model: Model) {
        val entity = ModelEntity(
            id = model.id,
            name = model.name,
            fileName = model.fileName,
            filePath = model.filePath,
            fileSize = model.fileSize,
            dateAdded = model.dateAdded,
            description = model.description,
            thumbnailFileName = model.thumbnailFileName,
            thumbnailFilePath = model.thumbnailFilePath,
            embeddedLatitude = model.embeddedLatitude,
            embeddedLongitude = model.embeddedLongitude,
            embeddedAltitudeM = model.embeddedAltitudeM
        )
        modelDao.insertModel(entity)
    }

    suspend fun updateModel(model: Model) {
        val entity = ModelEntity(
            id = model.id,
            name = model.name,
            fileName = model.fileName,
            filePath = model.filePath,
            fileSize = model.fileSize,
            dateAdded = model.dateAdded,
            description = model.description,
            thumbnailFileName = model.thumbnailFileName,
            thumbnailFilePath = model.thumbnailFilePath,
            embeddedLatitude = model.embeddedLatitude,
            embeddedLongitude = model.embeddedLongitude,
            embeddedAltitudeM = model.embeddedAltitudeM
        )
        modelDao.updateModel(entity)
    }

    suspend fun deleteModel(model: Model) {
        // Delete thumbnail file from disk first (if it exists)
        deleteThumbnailFile(model.thumbnailFilePath)

        val entity = ModelEntity(
            id = model.id,
            name = model.name,
            fileName = model.fileName,
            filePath = model.filePath,
            fileSize = model.fileSize,
            dateAdded = model.dateAdded,
            description = model.description,
            thumbnailFileName = model.thumbnailFileName,
            thumbnailFilePath = model.thumbnailFilePath,
            embeddedLatitude = model.embeddedLatitude,
            embeddedLongitude = model.embeddedLongitude,
            embeddedAltitudeM = model.embeddedAltitudeM
        )
        modelDao.deleteModel(entity)
    }

    suspend fun deleteModelById(id: String) {
        // Fetch the model first so we can clean up its thumbnail file
        val entity = modelDao.getModelById(id)
        if (entity != null) {
            deleteThumbnailFile(entity.thumbnailFilePath)
        }
        modelDao.deleteModelById(id)
    }

    /**
     * Deletes the thumbnail file at [filePath] from disk.
     * Silently ignores null/blank paths or files that don't exist.
     */
    private fun deleteThumbnailFile(filePath: String?) {
        if (filePath.isNullOrBlank()) return
        try {
            val file = java.io.File(filePath)
            if (file.exists() && file.isFile) {
                val deleted = file.delete()
                if (!deleted) {
                    android.util.Log.w("ModelRepository", "Failed to delete thumbnail: $filePath")
                } else {
                    android.util.Log.d("ModelRepository", "Deleted thumbnail: $filePath")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ModelRepository", "Error deleting thumbnail file: $filePath", e)
        }
    }

    suspend fun getModelCount(): Int = modelDao.getModelCount()
}
