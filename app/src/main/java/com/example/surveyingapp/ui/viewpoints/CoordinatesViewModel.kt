// This file was renamed from ViewPointsViewModel.kt
// See CoordinatesViewModel implementation above.

package com.example.surveyingapp.ui.viewpoints

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.example.surveyingapp.data.AppDatabase
import com.example.surveyingapp.data.Point
import com.example.surveyingapp.data.PointRepository
import kotlinx.coroutines.launch

class CoordinatesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: PointRepository
    val allPoints: LiveData<List<Point>>

    init {
        val pointDao = AppDatabase.getDatabase(application).pointDao()
        repository = PointRepository(pointDao)
        allPoints = repository.allPoints
    }

    fun insert(point: Point) = viewModelScope.launch {
        repository.insert(point)
    }

    fun insertFakePoints() = viewModelScope.launch {
        repository.deleteAll()
        val baseLat = 41.347900
        val baseLon = -76.022400
        val maxRadiusMeters = 1609.344 // 1 mile in meters
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * kotlin.math.cos(Math.toRadians(baseLat))
        val colors = listOf(0xFFE57373.toInt(),0xFF64B5F6.toInt(),0xFF81C784.toInt(),0xFFFFB74D.toInt(),0xFFBA68C8.toInt())
        val icons = listOf("ic_menu_camera","ic_menu_gallery","ic_menu_slideshow")
        val now = System.currentTimeMillis()
        val random = kotlin.random.Random(System.currentTimeMillis())
        val points = (0 until 10).map { i ->
            val u = random.nextDouble()
            val r = maxRadiusMeters * kotlin.math.sqrt(u)
            val theta = random.nextDouble() * (2 * Math.PI)
            val dxMeters = r * kotlin.math.cos(theta)
            val dyMeters = r * kotlin.math.sin(theta)
            val lat = baseLat + (dyMeters / metersPerDegLat)
            val lon = baseLon + (dxMeters / metersPerDegLon)
            Point(
                id = (i + 1).toString(),
                name = "Random Mile Point ${(i + 1)}",
                latitude = lat,
                longitude = lon,
                altitude = 10.0 + (i % 4) * 1.0,
                timestamp = now - i * 1_000L,
                icon = icons[i % icons.size],
                color = colors[i % colors.size]
            )
        }
        repository.insertAll(points)
    }

    fun deletePoint(id: String) = viewModelScope.launch {
        repository.deleteById(id)
    }

    fun deleteAllPoints() = viewModelScope.launch {
        repository.deleteAll()
    }

    fun addPoint(point: Point) = viewModelScope.launch {
        repository.insert(point)
    }

    fun updatePoint(point: Point) = viewModelScope.launch {
        repository.update(point)
    }
}
