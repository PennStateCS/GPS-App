package com.example.surveyingapp.data

import androidx.lifecycle.LiveData

/**
 * Repository class that manages data access for Coordinates.
 *
 * The Repository pattern acts as a single source of truth for data.
 * It sits between the ViewModel and the data sources (database, network, etc.).
 * This provides a clean API for the rest of the app to access data.
 *
 */
class CoordinateRepository(private val coordinateDao: CoordinateDao) {

    // Expose LiveData from the DAO - this automatically updates when database changes
    val allCoordinates: LiveData<List<Coordinate>> = coordinateDao.getAllCoordinates()

    // Backward compatibility property; same underlying list but different name
    val allPoints: LiveData<List<Point>> get() = allCoordinates

    // Wrapper functions that delegate to the DAO
    // These could be expanded later to include network calls, caching, etc.
    suspend fun insert(coordinate: Coordinate) { coordinateDao.insert(coordinate) }
    suspend fun deleteById(id: String) { coordinateDao.deleteById(id) }
    suspend fun deleteAll() { coordinateDao.deleteAll() }
    suspend fun insertAll(coordinates: List<Coordinate>) { coordinateDao.insertAll(coordinates) }
    suspend fun update(coordinate: Coordinate) { coordinateDao.update(coordinate) }
    suspend fun getAllCoordinatesList(): List<Coordinate> = coordinateDao.getAllCoordinatesList()

    // Backward compatibility list alias
    suspend fun getAllPointsList(): List<Point> = getAllCoordinatesList()
}
