package com.example.surveyingapp.data

import androidx.lifecycle.LiveData

class CoordinateRepository(private val coordinateDao: CoordinateDao) {
    val allCoordinates: LiveData<List<Coordinate>> = coordinateDao.getAllCoordinates()
    // Backward compatibility property; same underlying list
    val allPoints: LiveData<List<Point>> get() = allCoordinates

    suspend fun insert(coordinate: Coordinate) { coordinateDao.insert(coordinate) }
    suspend fun deleteById(id: String) { coordinateDao.deleteById(id) }
    suspend fun deleteAll() { coordinateDao.deleteAll() }
    suspend fun insertAll(coordinates: List<Coordinate>) { coordinateDao.insertAll(coordinates) }
    suspend fun update(coordinate: Coordinate) { coordinateDao.update(coordinate) }
    suspend fun getAllCoordinatesList(): List<Coordinate> = coordinateDao.getAllCoordinatesList()
    // Backward compatibility list alias
    suspend fun getAllPointsList(): List<Point> = getAllCoordinatesList()
}
