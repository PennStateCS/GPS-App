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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

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
        } catch (e: Exception) {
            Log.e("CoordinatesViewModel", "addCoordinate failed", e)
        }
    }

    fun updateCoordinate(coordinate: Coordinate) = viewModelScope.launch {
        if (rejectIfInvalid(coordinate, "updateCoordinate")) return@launch
        try {
            repository.update(coordinate)
        } catch (e: Exception) {
            Log.e("CoordinatesViewModel", "updateCoordinate failed", e)
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
            Log.w("CoordinatesViewModel", "$op rejected invalid coordinate: ${result.errors.joinToString()}")
            return true
        }
        return false
    }

    fun deleteCoordinate(id: String) = viewModelScope.launch {
        try {
            repository.deleteById(id)
        } catch (e: Exception) {
            Log.e("CoordinatesViewModel", "deleteCoordinate failed for id=$id", e)
        }
    }

    fun deleteAllCoordinates() = viewModelScope.launch {
        try {
            repository.deleteAll()
        } catch (e: Exception) {
            Log.e("CoordinatesViewModel", "deleteAllCoordinates failed", e)
        }
    }

    /**
     * Creates fake test data for development and demonstration purposes.
     * Generates 10 random coordinates within a 1-mile radius using proper
     * geographic calculations to convert meters to latitude/longitude degrees.
     */
    fun insertFakeCoordinates() = viewModelScope.launch {
        try {
            repository.deleteAll()

            // Base location (approximate coordinates)
            val baseLat = 41.347900
            val baseLon = -76.022400
            val maxRadiusMeters = 1609.344 // 1 mile in meters

            // Convert meters to degrees (approximate, varies by latitude)
            val metersPerDegLat = 111_320.0  // Meters per degree latitude (constant)
            val metersPerDegLon = 111_320.0 * kotlin.math.cos(Math.toRadians(baseLat))  // Varies by latitude

            val icons = listOf("ic_pin","ic_home","ic_star","ic_circle","ic_square","ic_triangle","ic_diamond")
            val defaultColor = 0xFF155DA8.toInt()
            val now = System.currentTimeMillis()
            val random = kotlin.random.Random(System.currentTimeMillis())

            // Generate coordinates using uniform distribution within a circle
            val coords = (0 until 10).map { i ->
                // Use sqrt for uniform distribution (prevents clustering near center)
                val u = random.nextDouble()
                val r = maxRadiusMeters * kotlin.math.sqrt(u)
                val theta = random.nextDouble() * (2 * Math.PI)

                // Convert polar coordinates to Cartesian, then to lat/lon
                val dxMeters = r * kotlin.math.cos(theta)
                val dyMeters = r * kotlin.math.sin(theta)
                val lat = baseLat + (dyMeters / metersPerDegLat)
                val lon = baseLon + (dxMeters / metersPerDegLon)

                Coordinate(
                    id = (i + 1).toString(),
                    name = "Random Mile Coordinate ${(i + 1)}",
                    latitude = lat,
                    longitude = lon,
                    altitude = 10.0 + (i % 4),
                    timestamp = now - i * 1_000L,  // Spread timestamps for variety
                    icon = icons[i % icons.size],
                    color = defaultColor
                )
            }
            repository.insertAll(coords)
        } catch (e: Exception) {
            Log.e("CoordinatesViewModel", "insertFakeCoordinates failed", e)
        }
    }

    suspend fun getById(id: String): Coordinate? = try {
        repository.getById(id)
    } catch (e: Exception) {
        Log.e("CoordinatesViewModel", "getById failed for id=$id", e)
        null
    }
}
