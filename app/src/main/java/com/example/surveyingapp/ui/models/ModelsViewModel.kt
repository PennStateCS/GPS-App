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
import kotlinx.coroutines.launch
import java.util.UUID

class ModelsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ModelRepositoryImpl(AppDatabase.getDatabase(application).modelDao())
    private val coordinateDao = AppDatabase.getDatabase(application).coordinateDao()

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
            try {
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
            } catch (_: Exception) {
                // Non-fatal: thumbnail spinner state may not clear automatically,
                // but it won't affect core functionality.
            }
        }
    }

    fun markThumbnailGenerating(modelId: String) {
        _thumbnailGenerating.value = _thumbnailGenerating.value + modelId
    }

    fun markThumbnailDone(modelId: String) {
        _thumbnailGenerating.value = _thumbnailGenerating.value - modelId
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
                    fileType = FileType.OTHER   // repository re-derives this on read
                )
                repository.insertModel(model)
                // Mark thumbnail as generating BEFORE notifying caller so the UI
                // shows the spinner as soon as the item appears in the list.
                markThumbnailGenerating(modelId)
                onModelId?.invoke(modelId)
                _statusMessage.value = "Model '$name' added successfully"
            } catch (e: Exception) {
                _statusMessage.value = "Failed to add model: ${e.message ?: "Unknown error"}"
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
                _statusMessage.value = "Failed to delete model: ${e.message ?: "Unknown error"}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun editModel(modelId: String, newName: String, newDescription: String?) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Direct by-ID lookup — faster and safer than scanning the full list.
                val currentModel = repository.getModelById(modelId)
                if (currentModel != null) {
                    repository.updateModel(currentModel.copy(name = newName, description = newDescription))
                    _statusMessage.value = "Model '$newName' updated successfully"
                } else {
                    _statusMessage.value = "Model not found — it may have been deleted"
                }
            } catch (e: Exception) {
                _statusMessage.value = "Failed to update model: ${e.message ?: "Unknown error"}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    /**
     * Returns how many coordinates currently use [modelId] as their icon
     * (stored as "model:<modelId>" in the icon field).
     * Returns -1 if the query fails so callers can handle the error case.
     */
    suspend fun linkedCoordinateCount(modelId: String): Int {
        return try {
            coordinateDao.countByModelId(modelId)
        } catch (_: Exception) {
            -1
        }
    }
}
