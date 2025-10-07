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
                fileType = getFileTypeFromExtension(entity.fileName)
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
                fileType = getFileTypeFromExtension(entity.fileName)
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
            description = model.description
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
            description = model.description
        )
        modelDao.updateModel(entity)
    }

    suspend fun deleteModel(model: Model) {
        val entity = ModelEntity(
            id = model.id,
            name = model.name,
            fileName = model.fileName,
            filePath = model.filePath,
            fileSize = model.fileSize,
            dateAdded = model.dateAdded,
            description = model.description
        )
        modelDao.deleteModel(entity)
    }

    suspend fun deleteModelById(id: String) {
        modelDao.deleteModelById(id)
    }

    suspend fun getModelCount(): Int = modelDao.getModelCount()
}
