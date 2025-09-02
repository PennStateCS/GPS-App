package com.example.surveyingapp.ui.models

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.surveyingapp.data.local.db.AppDatabase
import com.example.surveyingapp.data.repository.impl.ModelRepositoryImpl
import com.example.surveyingapp.domain.model.Model
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    fun addModel(name: String, fileName: String, filePath: String, fileSize: Long, description: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val model = Model(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    fileName = fileName,
                    filePath = filePath,
                    fileSize = fileSize,
                    dateAdded = System.currentTimeMillis(),
                    description = description
                )
                repository.insertModel(model)
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
