package app.surrealar.ui.openinar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.surrealar.data.local.entity.CoordinateEntity
import app.surrealar.domain.repository.ArDisplaySettingsRepository
import app.surrealar.domain.repository.GnssReceiverSettingsRepository
import app.surrealar.settings.model.ArDisplaySettings
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
    val modelFilePath: String?,
    /** Resolved AR placement (per-coordinate override → model default → identity). */
    val placement: ModelPlacement = ModelPlacement()
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
    private val observeArCoordinateModels: ObserveArCoordinateModelsUseCase,
    private val arDisplayRepo: ArDisplaySettingsRepository,
    private val gnssReceiverRepo: GnssReceiverSettingsRepository
) : ViewModel() {

    // ── Coordinate stream ─────────────────────────────────────────────────────

    /**
     * Live stream of every saved coordinate, each enriched with its associated
     * model file path (or null when the coordinate has no model assigned).
     *
     * Emits a new list whenever the `coordinates` table changes.
     */
    val coordsWithModels: StateFlow<List<CoordWithModel>> = observeArCoordinateModels()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    // ── Init: read persisted AR display defaults ──────────────────────────────

    init {
        viewModelScope.launch {
            runCatching {
                val ar = arDisplayRepo.arDisplaySettings.first()
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
    val highAccuracyEnabled: StateFlow<Boolean> = gnssReceiverRepo.gnssReceiverSettings
        .map { it.highAccuracy }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // ── AR Display settings ───────────────────────────────────────────────────

    val arDisplaySettings: StateFlow<ArDisplaySettings> = arDisplayRepo.arDisplaySettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, ArDisplaySettings())

    /**
     * Persist the AR altitude mode chosen from the in-AR toolbar so it survives pause/resume and stays
     * in sync with the Settings screen. The fragment applies the change through its [arDisplaySettings]
     * collector; persisting here makes it durable — the toolbar previously flipped a transient local
     * flag that the collector silently reverted on the next emission. runCatching so a DataStore write
     * failure can never crash the toggle.
     */
    fun setAltitudeMode(terrain: Boolean) {
        viewModelScope.launch {
            runCatching {
                arDisplayRepo.setArDisplaySettings(
                    arDisplaySettings.value.copy(altitudeMode = if (terrain) "TERRAIN" else "STORED")
                )
            }
        }
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        /** Ordered distance-filter steps; null = show all pins regardless of distance. */
        val DISTANCE_FILTER_STEPS: List<Double?> = listOf(null, 50.0, 100.0, 500.0)
    }
}

