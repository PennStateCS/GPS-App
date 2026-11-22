/**
 * ViewModel for managing coordinate data in the UI.
 *
 * ViewModels are part of Android's MVVM (Model-View-ViewModel) architecture:
 * - They survive configuration changes (like screen rotation)
 * - They hold and manage UI-related data
 * - They act as a bridge between the UI (Fragment/Activity) and the data layer
 *
 * AndroidViewModel provides access to the Application context, which is needed
 * to create the database instance.
 */
// This file was renamed from ViewPointsViewModel.kt
// See CoordinatesViewModel implementation above.

package app.surrealar.ui.viewpoints

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.surrealar.domain.coordinates.CoordinateValidator
import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.repository.CoordinateRepository
import app.surrealar.util.DiagnosticsLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State holder for the coordinates screens: exposes the saved-coordinate list and edit operations
 * through the repository.
 */
@HiltViewModel
class CoordinatesViewModel @Inject constructor(
    private val repository: CoordinateRepository
) : ViewModel() {

    // LiveData automatically notifies observers (like UI) when data changes
    val allCoordinates: LiveData<List<Coordinate>> = repository.allCoordinates

    // Database operations wrapped in viewModelScope.launch for background execution
    // viewModelScope automatically cancels when ViewModel is destroyed
    fun addCoordinate(coordinate: Coordinate) = viewModelScope.launch {
        if (rejectIfInvalid(coordinate, "addCoordinate")) return@launch
        try {
            repository.insert(coordinate)
            // COORD diagnostics: id/name/model link only — never the user's note.
            DiagnosticsLogger.i("COORD", "saved id=${coordinate.id} name=\"${coordinate.name}\" " +
                "modelId=${coordinate.modelId} renderEnabled=${coordinate.renderEnabled}")
        } catch (e: Exception) {
            DiagnosticsLogger.e("COORD", "save failed id=${coordinate.id}", e)
        }
    }

    fun updateCoordinate(coordinate: Coordinate) = viewModelScope.launch {
        if (rejectIfInvalid(coordinate, "updateCoordinate")) return@launch
        try {
            repository.update(coordinate)
            DiagnosticsLogger.i("COORD", "updated id=${coordinate.id} name=\"${coordinate.name}\" " +
                "modelId=${coordinate.modelId} renderEnabled=${coordinate.renderEnabled}")
        } catch (e: Exception) {
            DiagnosticsLogger.e("COORD", "update failed id=${coordinate.id}", e)
        }
    }

    /**
     * Defense-in-depth gate: refuses to persist a coordinate that fails validation
     * (out-of-range, NaN, or 0,0). The capture dialog already blocks these at the UI, so this
     * only catches programmatic callers. Returns true when the coordinate was rejected.
     */
    private fun rejectIfInvalid(coordinate: Coordinate, op: String): Boolean {
        val result = CoordinateValidator.validate(coordinate)
        if (!result.isValid) {
            DiagnosticsLogger.w("COORD", "$op rejected — validation failed: ${result.errors.joinToString()}")
            return true
        }
        return false
    }

    fun deleteCoordinate(id: String) = viewModelScope.launch {
        try {
            repository.deleteById(id)
            DiagnosticsLogger.i("COORD", "deleted id=$id")
        } catch (e: Exception) {
            DiagnosticsLogger.e("COORD", "delete failed id=$id", e)
        }
    }

    fun deleteAllCoordinates() = viewModelScope.launch {
        try {
            repository.deleteAll()
            DiagnosticsLogger.i("COORD", "deleted all coordinates")
        } catch (e: Exception) {
            DiagnosticsLogger.e("COORD", "deleteAll failed", e)
        }
    }

    suspend fun getById(id: String): Coordinate? = try {
        repository.getById(id)
    } catch (e: Exception) {
        Log.e("CoordinatesViewModel", "getById failed for id=$id", e)
        null
    }
}
