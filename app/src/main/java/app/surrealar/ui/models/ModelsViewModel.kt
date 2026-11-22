package app.surrealar.ui.models

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import app.surrealar.data.files.ModelFileCleaner
import app.surrealar.data.local.dao.CoordinateDao
import app.surrealar.domain.repository.CoordinateRepository
import app.surrealar.domain.repository.ModelRepository
import app.surrealar.domain.model.Model
import app.surrealar.ui.models.readiness.ModelReadinessRepository
import app.surrealar.ui.models.readiness.ReadinessStyle
import app.surrealar.ui.models.readiness.Verdict
import kotlinx.coroutines.flow.combine
import app.surrealar.domain.model.FileType
import app.surrealar.util.DiagnosticsLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * State holder for the models screen: exposes the model list and loading state from `ModelRepository`.
 */
@HiltViewModel
class ModelsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: ModelRepository,
    private val coordinateDao: CoordinateDao,
    private val coordinateRepository: CoordinateRepository,
    private val readinessRepository: ModelReadinessRepository
) : ViewModel() {

    private val modelsRoot = File(context.filesDir, ModelFileCleaner.MODELS_DIR_NAME)

    val allModels = repository.getAllModels().asLiveData()

    /** Model id → number of coordinates linking it, for the "N linked" card badge. */
    val linkedCounts = coordinateDao.observeAll()
        .map { coords -> ModelCardInfo.linkedCounts(coords.map { it.modelId to it.icon }) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .catch { emit(emptyMap()) }
        .asLiveData()

    /**
     * Model id → AR readiness verdict for the card chip. Recomputed (on IO, file facts cached by the
     * repository) whenever the models or the coordinates change.
     */
    val readiness = combine(repository.getAllModels(), coordinateRepository.allCoordinatesFlow) { models, coords ->
        models to coords
    }
        .map { (models, coords) -> ModelCardInfo.verdicts(readinessRepository.reports(models, coords)) }
        .distinctUntilChanged()
        .catch { emit(emptyMap()) }
        .asLiveData()

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
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Non-fatal: thumbnail spinner state may not clear automatically,
                // but it won't affect core functionality.
                DiagnosticsLogger.w("MODEL", "thumbnail-state observer stopped: ${e.javaClass.simpleName}", e)
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
        embeddedLatitude: Double? = null,
        embeddedLongitude: Double? = null,
        embeddedAltitudeM: Double? = null,
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
                    fileType = FileType.OTHER,  // repository re-derives this on read
                    embeddedLatitude = embeddedLatitude,
                    embeddedLongitude = embeddedLongitude,
                    embeddedAltitudeM = embeddedAltitudeM
                )
                repository.insertModel(model)
                DiagnosticsLogger.i("MODEL", "db record created id=$modelId name=\"$name\" " +
                    "file=\"$fileName\" sizeBytes=$fileSize")
                // Mark thumbnail as generating BEFORE notifying caller so the UI
                // shows the spinner as soon as the item appears in the list.
                markThumbnailGenerating(modelId)
                onModelId?.invoke(modelId)
                _statusMessage.value = "Model '$name' added successfully"
            } catch (e: Exception) {
                DiagnosticsLogger.e("MODEL", "db insert failed name=\"$name\": ${e.javaClass.simpleName} ${e.message}", e)
                _statusMessage.value = "Failed to add model: ${e.message ?: "Unknown error"}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Evaluates a just-imported model, logs one MODEL_READINESS line for the diagnostic report, and
     * calls [onNotReady] with the model name when the verdict is NOT_READY.
     */
    fun checkReadinessAfterImport(modelId: String, onNotReady: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val facts = readinessRepository.facts(modelId) ?: return@launch
                val report = withContext(Dispatchers.Default) { readinessRepository.evaluate(facts) }
                DiagnosticsLogger.i("MODEL", ReadinessStyle.logLine(facts.fileName, report))
                if (report.verdict == Verdict.NOT_READY) onNotReady(facts.modelName)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                DiagnosticsLogger.w("MODEL", "readiness check failed id=$modelId: ${e.javaClass.simpleName}", e)
            }
        }
    }

    fun deleteModel(model: Model) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.deleteModel(model)
                DiagnosticsLogger.i("MODEL", "db record deleted id=${model.id} name=\"${model.name}\"")
                // Only remove the file once the row is gone, so a failed DB delete never leaves
                // a record pointing at a missing file.
                val fileDeleted = withContext(Dispatchers.IO) {
                    ModelFileCleaner.deleteModelFile(model.filePath, modelsRoot)
                }
                if (!fileDeleted) {
                    DiagnosticsLogger.w("MODEL", "model file not deleted id=${model.id} (missing or outside models/)")
                }
                _statusMessage.value = "Model '${model.name}' deleted"
            } catch (e: Exception) {
                DiagnosticsLogger.e("MODEL", "db delete failed id=${model.id}: ${e.javaClass.simpleName} ${e.message}", e)
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
                if (e is kotlinx.coroutines.CancellationException) throw e
                DiagnosticsLogger.e("MODEL", "model update failed id=$modelId: ${e.javaClass.simpleName}", e)
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
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            DiagnosticsLogger.w("MODEL", "linked-coordinate count query failed id=$modelId: ${e.javaClass.simpleName}", e)
            -1
        }
    }
}
