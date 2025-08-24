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
        val fakePoints = listOf(
            Point(
                id = "1",
                name = "Test Coordinate 1",
                latitude = 37.4219983,
                longitude = -122.084,
                altitude = 10.0,
                timestamp = System.currentTimeMillis(),
                icon = "ic_menu_camera",
                color = 0xFFE57373.toInt()
            ),
            Point(
                id = "2",
                name = "Test Coordinate 2",
                latitude = 40.7128,
                longitude = -74.0060,
                altitude = 20.0,
                timestamp = System.currentTimeMillis() - 100_000,
                icon = "ic_menu_gallery",
                color = 0xFF64B5F6.toInt()
            ),
            Point(
                id = "3",
                name = "Test Coordinate 3",
                latitude = 34.0522,
                longitude = -118.2437,
                altitude = 30.0,
                timestamp = System.currentTimeMillis() - 200_000,
                icon = "ic_menu_slideshow",
                color = 0xFF81C784.toInt()
            ),
            Point(
                id = "4",
                name = "Test Coordinate 4",
                latitude = 47.6062,
                longitude = -122.3321,
                altitude = 15.3,
                timestamp = System.currentTimeMillis() - 300_000,
                icon = "ic_menu_camera",
                color = 0xFFFFB74D.toInt()
            ),
            Point(
                id = "5",
                name = "Test Coordinate 5",
                latitude = 51.5074,
                longitude = -0.1278,
                altitude = 5.5,
                timestamp = System.currentTimeMillis() - 400_000,
                icon = "ic_menu_gallery",
                color = 0xFFBA68C8.toInt()
            ),
            Point(
                id = "6",
                name = "Test Coordinate 6",
                latitude = 48.8566,
                longitude = 2.3522,
                altitude = 35.2,
                timestamp = System.currentTimeMillis() - 500_000,
                icon = "ic_menu_slideshow",
                color = 0xFF4DB6AC.toInt()
            ),
            Point(
                id = "7",
                name = "Test Coordinate 7",
                latitude = 52.5200,
                longitude = 13.4050,
                altitude = 42.0,
                timestamp = System.currentTimeMillis() - 600_000,
                icon = "ic_menu_camera",
                color = 0xFFA1887F.toInt()
            ),
            Point(
                id = "8",
                name = "Test Coordinate 8",
                latitude = -33.8688,
                longitude = 151.2093,
                altitude = 8.0,
                timestamp = System.currentTimeMillis() - 700_000,
                icon = "ic_menu_gallery",
                color = 0xFF90A4AE.toInt()
            ),
            Point(
                id = "9",
                name = "Test Coordinate 9",
                latitude = 35.6895,
                longitude = 139.6917,
                altitude = 55.0,
                timestamp = System.currentTimeMillis() - 800_000,
                icon = "ic_menu_slideshow",
                color = 0xFF7986CB.toInt()
            ),
            Point(
                id = "10",
                name = "Test Coordinate 10",
                latitude = 55.7558,
                longitude = 37.6173,
                altitude = 25.0,
                timestamp = System.currentTimeMillis() - 900_000,
                icon = "ic_menu_camera",
                color = 0xFF009688.toInt()
            )
        )
        repository.insertAll(fakePoints)
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
