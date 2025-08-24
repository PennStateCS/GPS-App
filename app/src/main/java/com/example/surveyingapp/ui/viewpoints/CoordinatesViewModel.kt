// This file was renamed from ViewPointsViewModel.kt
// See CoordinatesViewModel implementation above.

package com.example.surveyingapp.ui.viewpoints

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.example.surveyingapp.data.AppDatabase
import com.example.surveyingapp.data.Coordinate
import com.example.surveyingapp.data.CoordinateRepository
import com.example.surveyingapp.data.Point
import kotlinx.coroutines.launch

class CoordinatesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: CoordinateRepository
    val allCoordinates: LiveData<List<Coordinate>>

    // Backward compatibility: legacy observers
    val allPoints: LiveData<List<Point>> get() = allCoordinates

    init {
        val dao = AppDatabase.getDatabase(application).coordinateDao()
        repository = CoordinateRepository(dao)
        allCoordinates = repository.allCoordinates
    }

    fun addCoordinate(coordinate: Coordinate) = viewModelScope.launch { repository.insert(coordinate) }

    fun updateCoordinate(coordinate: Coordinate) = viewModelScope.launch { repository.update(coordinate) }

    fun deleteCoordinate(id: String) = viewModelScope.launch { repository.deleteById(id) }

    fun deleteAllCoordinates() = viewModelScope.launch { repository.deleteAll() }

    fun insertFakeCoordinates() = viewModelScope.launch {
        repository.deleteAll()
        val baseLat = 41.347900
        val baseLon = -76.022400
        val maxRadiusMeters = 1609.344 // 1 mile
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * kotlin.math.cos(Math.toRadians(baseLat))
        val colors = listOf(0xFFE57373.toInt(),0xFF64B5F6.toInt(),0xFF81C784.toInt(),0xFFFFB74D.toInt(),0xFFBA68C8.toInt())
        val icons = listOf("ic_menu_camera","ic_menu_gallery","ic_menu_slideshow")
        val now = System.currentTimeMillis()
        val random = kotlin.random.Random(System.currentTimeMillis())
        val coords = (0 until 10).map { i ->
            val u = random.nextDouble()
            val r = maxRadiusMeters * kotlin.math.sqrt(u)
            val theta = random.nextDouble() * (2 * Math.PI)
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
                timestamp = now - i * 1_000L,
                icon = icons[i % icons.size],
                color = colors[i % colors.size]
            )
        }
        repository.insertAll(coords)
    }

    // Backward compatible wrappers
    fun addPoint(point: Point) = addCoordinate(point as Coordinate)
    fun updatePoint(point: Point) = updateCoordinate(point as Coordinate)
    fun deletePoint(id: String) = deleteCoordinate(id)
    fun deleteAllPoints() = deleteAllCoordinates()
    fun insertFakePoints() = insertFakeCoordinates()
}
