package com.example.surveyingapp.ui.models

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.surveyingapp.data.local.db.AppDatabase
import com.example.surveyingapp.data.repository.impl.ModelRepositoryImpl
import com.example.surveyingapp.domain.model.Model
import com.example.surveyingapp.domain.model.FileType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class ModelsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ModelRepositoryImpl(AppDatabase.getDatabase(application).modelDao())

    val allModels = repository.getAllModels().asLiveData()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    /** IDs of models whose thumbnails are currently being generated. */
    private val _thumbnailGenerating = MutableStateFlow<Set<String>>(emptySet())
    val thumbnailGenerating: StateFlow<Set<String>> = _thumbnailGenerating.asStateFlow()

    init {
        // Auto-clear generating state once Room emits a model with a thumbnail path.
        viewModelScope.launch {
            repository.getAllModels().collect { models ->
                val currentGenerating = _thumbnailGenerating.value
                if (currentGenerating.isNotEmpty()) {
                    val doneIds = models
                        .filter { it.id in currentGenerating && !it.thumbnailFilePath.isNullOrBlank() }
                        .map { it.id }
                        .toSet()
                    if (doneIds.isNotEmpty()) {
                        _thumbnailGenerating.value = currentGenerating - doneIds
                    }
                }
            }
        }
    }

    fun markThumbnailGenerating(modelId: String) {
        _thumbnailGenerating.value = _thumbnailGenerating.value + modelId
    }

    fun markThumbnailDone(modelId: String) {
        _thumbnailGenerating.value = _thumbnailGenerating.value - modelId
    }

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

    fun addModel(
        name: String,
        fileName: String,
        filePath: String,
        fileSize: Long,
        description: String? = null,
        onModelId: ((String) -> Unit)? = null
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val modelId = UUID.randomUUID().toString()
                val model = Model(
                    id = modelId,
                    name = name,
                    fileName = fileName,
                    filePath = filePath,
                    fileSize = fileSize,
                    dateAdded = System.currentTimeMillis(),
                    description = description,
                    fileType = getFileTypeFromExtension(fileName)
                )
                repository.insertModel(model)
                // Mark thumbnail as generating BEFORE notifying caller so the UI
                // shows the spinner as soon as the item appears in the list.
                markThumbnailGenerating(modelId)
                onModelId?.invoke(modelId)
                _statusMessage.value = "Model '$name' added successfully"
            } catch (e: Exception) {
                _statusMessage.value = "Failed to add model: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteModel(model: Model) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.deleteModel(model)
                _statusMessage.value = "Model '${model.name}' deleted"
            } catch (e: Exception) {
                _statusMessage.value = "Failed to delete model: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun editModel(modelId: String, newName: String, newDescription: String?) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Get the current model
                val currentModel = repository.getAllModels().first().find { it.id == modelId }
                if (currentModel != null) {
                    val updatedModel = currentModel.copy(
                        name = newName,
                        description = newDescription
                    )
                    repository.updateModel(updatedModel)
                    _statusMessage.value = "Model '$newName' updated successfully"
                } else {
                    _statusMessage.value = "Model not found"
                }
            } catch (e: Exception) {
                _statusMessage.value = "Failed to update model: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}
