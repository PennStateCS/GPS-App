package com.example.surveyingapp.ui.openinar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.surveyingapp.data.local.dao.CoordinateDao
import com.example.surveyingapp.data.local.dao.ModelDao
import com.example.surveyingapp.data.local.entity.CoordinateEntity
import com.example.surveyingapp.SurveyingApp
import com.example.surveyingapp.domain.repository.SettingsRepository
import com.example.surveyingapp.gnss.settings.ArDisplaySettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * Also owns UI state that should survive configuration changes: distance filter
 * and debug overlay visibility.
 */
@HiltViewModel
class OpenInARViewModel @Inject constructor(
    private val coordinateDao: CoordinateDao,
    private val modelDao: ModelDao,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    // ── Coordinate stream ─────────────────────────────────────────────────────

    /**
     * Live stream of every saved coordinate, each enriched with its associated
     * model file path (or null when the coordinate has no model assigned).
     *
     * Emits a new list whenever the `coordinates` table changes.
     */
    val coordsWithModels: StateFlow<List<CoordWithModel>> = coordinateDao.observeAll()
        .map { entities ->
            val modelIndex: Map<String, String> = modelDao.getAllModelsList()
                .associate { it.id to it.filePath }
            entities.map { entity ->
                val modelId = entity.icon
                    .takeIf { it.startsWith("model:") }
                    ?.removePrefix("model:")
                CoordWithModel(entity, modelId, modelId?.let { modelIndex[it] })
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    // ── Init: read persisted AR display defaults ──────────────────────────────

    init {
        viewModelScope.launch {
            runCatching {
                val ar = SurveyingApp.settingsRepo.arDisplaySettings.first()
                _distanceFilterIndex.value = ar.distanceFilterIndex.coerceIn(0, DISTANCE_FILTER_STEPS.lastIndex)
                _debugVisible.value = ar.showDebugOverlay
            }
        }
    }

    // ── Distance filter ───────────────────────────────────────────────────────

    /** Defaults to index 1 (50 m) until the persisted setting is loaded in init. */
    private val _distanceFilterIndex = MutableStateFlow(1)

    /** Current maximum display distance in metres, or null for "show all". */
    val distanceFilterM: StateFlow<Double?> = _distanceFilterIndex
        .map { DISTANCE_FILTER_STEPS[it] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 50.0)

    /** Human-readable label for the distance filter button. */
    val distanceFilterLabel: StateFlow<String> = _distanceFilterIndex
        .map { i ->
            when (DISTANCE_FILTER_STEPS[i]) {
                null  -> "📏 All"
                50.0  -> "📏 <50m"
                100.0 -> "📏 <100m"
                500.0 -> "📏 <500m"
                else  -> "📏 <${DISTANCE_FILTER_STEPS[i]!!.toInt()}m"
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "📏 <50m")

    /** Advance to the next distance-filter step (wraps around). */
    fun cycleDistanceFilter() {
        _distanceFilterIndex.update { (it + 1) % DISTANCE_FILTER_STEPS.size }
    }

    // ── Debug overlay ─────────────────────────────────────────────────────────

    private val _debugVisible = MutableStateFlow(false)

    /** Whether the debug overlay panel is currently shown. */
    val debugVisible: StateFlow<Boolean> = _debugVisible.asStateFlow()

    /** Toggle debug overlay on/off. */
    fun toggleDebug() { _debugVisible.update { !it } }

    // ── AR debug visual toggles (runtime, reset each session) ─────────────────

    private val _showPlanes     = MutableStateFlow(false)
    private val _showPointCloud = MutableStateFlow(false)

    val showPlanes:     StateFlow<Boolean> = _showPlanes.asStateFlow()
    val showPointCloud: StateFlow<Boolean> = _showPointCloud.asStateFlow()

    fun togglePlanes()     { _showPlanes.update { !it } }
    fun togglePointCloud() { _showPointCloud.update { !it } }

    // ── High-accuracy GPS preference ──────────────────────────────────────────

    /**
     * Reflects the user's "high accuracy" GPS preference from SettingsRepository (DataStore).
     * Emits immediately with the current value and again whenever it changes.
     */
    val highAccuracyEnabled: StateFlow<Boolean> = settingsRepository.gnssReceiverSettings
        .map { it.highAccuracy }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // ── AR Display settings ───────────────────────────────────────────────────

    val arDisplaySettings: StateFlow<ArDisplaySettings> = settingsRepository.arDisplaySettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, ArDisplaySettings())

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        /** Ordered distance-filter steps; null = show all pins regardless of distance. */
        val DISTANCE_FILTER_STEPS: List<Double?> = listOf(null, 50.0, 100.0, 500.0)
    }
}

