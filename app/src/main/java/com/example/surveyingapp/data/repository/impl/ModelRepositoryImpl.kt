package com.example.surveyingapp.data.repository.impl

import com.example.surveyingapp.data.files.ModelFileCleaner
import com.example.surveyingapp.data.local.dao.ModelDao
import com.example.surveyingapp.data.local.entity.ModelEntity
import com.example.surveyingapp.domain.model.Model
import com.example.surveyingapp.domain.model.FileType
import com.example.surveyingapp.domain.repository.ModelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

    override suspend fun getModelById(id: String): Model? {
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

    override suspend fun getModelByFileName(fileName: String): Model? {
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

    override suspend fun insertModel(model: Model) {
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

    override suspend fun updateModel(model: Model) {
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

    override suspend fun deleteModel(model: Model) {
        // Delete thumbnail file from disk first (if it exists). The imported model file is removed
        // by the model-list UI; both delegate to ModelFileCleaner. See docs/data-architecture.md.
        ModelFileCleaner.deleteThumbnailFile(model.thumbnailFilePath)

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

}
