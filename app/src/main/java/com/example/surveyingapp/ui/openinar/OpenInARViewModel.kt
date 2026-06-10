package com.example.surveyingapp.ui.openinar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.surveyingapp.data.local.dao.CoordinateDao
import com.example.surveyingapp.data.local.dao.ModelDao
import com.example.surveyingapp.data.local.entity.CoordinateEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * A coordinate entity paired with its assigned 3D model's file path (if any).
 *
 * [modelId]       – the ID parsed from `icon = "model:<id>"`, null when no model is assigned.
 * [modelFilePath] – absolute path to the GLB/GLTF file; null when no model is assigned or
 *                   the model record has been deleted.
 */
data class CoordWithModel(
    val coordinate: CoordinateEntity,
    val modelId: String?,
    val modelFilePath: String?
)

/**
 * ViewModel for [OpenInARFragment].
 *
 * Streams all saved coordinates from the database, enriching each entry with the
 * file path of its associated 3D model so the AR renderer can later load it.
 */
@HiltViewModel
class OpenInARViewModel @Inject constructor(
    private val coordinateDao: CoordinateDao,
    private val modelDao: ModelDao
) : ViewModel() {

    /**
     * Live stream of every saved coordinate, each enriched with its associated
     * model file path (or null when the coordinate has no model assigned).
     *
     * Emits a new list whenever the `coordinates` table changes.
     */
    val coordsWithModels: StateFlow<List<CoordWithModel>> = coordinateDao.observeAll()
        .map { entities ->
            val result = mutableListOf<CoordWithModel>()
            for (entity in entities) {
                val modelId = entity.icon
                    .takeIf { it.startsWith("model:") }
                    ?.removePrefix("model:")
                val modelFilePath = modelId?.let { modelDao.getModelById(it)?.filePath }
                result += CoordWithModel(entity, modelId, modelFilePath)
            }
            result
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )
}
