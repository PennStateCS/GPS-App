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

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.surrealar.domain.coordinates.CoordinateValidator
import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.repository.CoordinateRepository
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.diagnostics.DiagTags
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /** Outcome of a save: [Saved], or why it wasn't ([Rejected] by validation, [Failed] in the database). */
    sealed interface SaveResult {
        data object Saved : SaveResult
        data class Rejected(val reasons: List<String>) : SaveResult
        data class Failed(val error: Throwable) : SaveResult

        /** Short text for the user, or null when saved. */
        val userMessage: String?
            get() = when (this) {
                Saved -> null
                is Rejected -> "Not saved: ${reasons.joinToString("; ")}"
                is Failed -> "Not saved: database error (${error.javaClass.simpleName})"
            }
    }

    /**
     * Saves a new coordinate and reports the outcome to [onResult] (main thread). Callers must show
     * success only for [SaveResult.Saved]: a failed save used to look like it worked.
     *
     * The database write is NonCancellable: viewModelScope runs on Main.immediate, so it starts as soon
     * as this is called and finishes even if the screen (and this ViewModel) goes away right after —
     * e.g. an edit followed immediately by Back on a fragment-scoped ViewModel. [onResult] is skipped
     * once the ViewModel is cleared.
     */
    fun addCoordinate(coordinate: Coordinate, onResult: (SaveResult) -> Unit = {}) =
        save(coordinate, "save", onResult) { repository.insert(it) }

    /** Updates a coordinate; same contract as [addCoordinate]. */
    fun updateCoordinate(coordinate: Coordinate, onResult: (SaveResult) -> Unit = {}) =
        save(coordinate, "update", onResult) { repository.update(it) }

    private fun save(
        coordinate: Coordinate, op: String, onResult: (SaveResult) -> Unit,
        write: suspend (Coordinate) -> Unit,
    ) = viewModelScope.launch {
        val result = rejectIfInvalid(coordinate, op)
            ?: withContext(NonCancellable) {
                try {
                    write(coordinate)
                    // COORD diagnostics: id/model link only — never the point's name or note.
                    DiagnosticsLogger.i(DiagTags.COORD, "Coordinate ${if (op == "save") "saved" else "updated"} id=${coordinate.id} " +
                        "modelId=${coordinate.modelId} renderEnabled=${coordinate.renderEnabled}")
                    SaveResult.Saved
                } catch (e: Exception) {
                    DiagnosticsLogger.e(DiagTags.COORD, "Coordinate $op failed id=${coordinate.id}", e)
                    SaveResult.Failed(e)
                }
            }
        if (isActive) onResult(result)
    }

    /**
     * Defense-in-depth gate: refuses to persist a coordinate that fails validation
     * (out-of-range, NaN, or 0,0). The capture dialog already blocks these at the UI, so this
     * only catches programmatic callers. Returns the rejection, or null when the coordinate is valid.
     */
    private fun rejectIfInvalid(coordinate: Coordinate, op: String): SaveResult.Rejected? {
        val result = CoordinateValidator.validate(coordinate)
        if (!result.isValid) {
            DiagnosticsLogger.w(DiagTags.COORD, "Coordinate $op rejected by validation: ${result.errors.joinToString("; ")} id=${coordinate.id}")
            return SaveResult.Rejected(result.errors)
        }
        return null
    }

    fun deleteCoordinate(id: String) = viewModelScope.launch {
        try {
            repository.deleteById(id)
            DiagnosticsLogger.i(DiagTags.COORD, "Coordinate deleted id=$id")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticsLogger.e(DiagTags.COORD, "Coordinate delete failed id=$id", e)
        }
    }

    fun deleteAllCoordinates() = viewModelScope.launch {
        try {
            repository.deleteAll()
            DiagnosticsLogger.i(DiagTags.COORD, "Coordinates deleted scope=all")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticsLogger.e(DiagTags.COORD, "Coordinates delete failed scope=all", e)
        }
    }

    suspend fun getById(id: String): Coordinate? = try {
        repository.getById(id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DiagnosticsLogger.e(DiagTags.COORD, "Coordinate load failed id=$id", e)
        null
    }
}
