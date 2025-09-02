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

package com.example.surveyingapp.ui.viewpoints

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.example.surveyingapp.data.local.db.AppDatabase
import com.example.surveyingapp.domain.model.Coordinate
import com.example.surveyingapp.domain.repository.CoordinateRepository
import com.example.surveyingapp.data.repository.impl.CoordinateRepositoryImpl
import kotlinx.coroutines.launch

class CoordinatesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: CoordinateRepository

    // LiveData automatically notifies observers (like UI) when data changes
    val allCoordinates: LiveData<List<Coordinate>>

    init {
        // Initialize the database and repository
        // This happens once when the ViewModel is created
        val dao = AppDatabase.getDatabase(application).coordinateDao()
        repository = CoordinateRepositoryImpl(dao)
        allCoordinates = repository.allCoordinates
    }

    // Database operations wrapped in viewModelScope.launch for background execution
    // viewModelScope automatically cancels when ViewModel is destroyed
    fun addCoordinate(coordinate: Coordinate) = viewModelScope.launch { repository.insert(coordinate) }

    fun updateCoordinate(coordinate: Coordinate) = viewModelScope.launch { repository.update(coordinate) }

    fun deleteCoordinate(id: String) = viewModelScope.launch { repository.deleteById(id) }

    fun deleteAllCoordinates() = viewModelScope.launch { repository.deleteAll() }

    /**
     * Creates fake test data for development and demonstration purposes.
     * Generates 10 random coordinates within a 1-mile radius using proper
     * geographic calculations to convert meters to latitude/longitude degrees.
     */
    fun insertFakeCoordinates() = viewModelScope.launch {
        repository.deleteAll()

        // Base location (approximate coordinates)
        val baseLat = 41.347900
        val baseLon = -76.022400
        val maxRadiusMeters = 1609.344 // 1 mile in meters

        // Convert meters to degrees (approximate, varies by latitude)
        val metersPerDegLat = 111_320.0  // Meters per degree latitude (constant)
        val metersPerDegLon = 111_320.0 * kotlin.math.cos(Math.toRadians(baseLat))  // Varies by latitude

        // Colors and icons for variety in the fake data
        val colors = listOf(0xFFE57373.toInt(),0xFF64B5F6.toInt(),0xFF81C784.toInt(),0xFFFFB74D.toInt(),0xFFBA68C8.toInt())
        val icons = listOf("ic_pin","ic_home","ic_star","ic_circle","ic_square","ic_triangle","ic_diamond")
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
                color = colors[i % colors.size]
            )
        }
        repository.insertAll(coords)
    }

    suspend fun getById(id: String): Coordinate? = repository.getById(id)
}
