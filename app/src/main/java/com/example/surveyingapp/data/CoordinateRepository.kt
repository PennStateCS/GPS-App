package com.example.surveyingapp.data

import androidx.lifecycle.LiveData
import kotlinx.coroutines.flow.Flow

/**
 * Repository class that manages data access for Coordinates.
 *
 * Responsibilities / notes:
 * - Thin abstraction over the DAO (no caching layer yet).
 * - Exposes both LiveData (legacy / XML or older fragments) and Flow (modern / Compose) reactive streams.
 * - Future extension points: merge remote + local, add paging, add in‑memory cache, offline sync.
 */
class CoordinateRepository(private val coordinateDao: CoordinateDao) {

    // Reactive list (LiveData) – existing UI relies on this.
    val allCoordinates: LiveData<List<Coordinate>> = coordinateDao.getAllCoordinates()

    // Reactive list (Flow) – preferred for new Compose code. Not currently referenced, safe addition.
    val allCoordinatesFlow: Flow<List<Coordinate>> = coordinateDao.observeAll()

    // Basic CRUD (direct pass‑through). Consider wrapping mutations in withContext(Dispatchers.IO) at call site if needed.
    suspend fun insert(coordinate: Coordinate) { coordinateDao.insert(coordinate) }
    suspend fun insertAll(coordinates: List<Coordinate>) { coordinateDao.insertAll(coordinates) }
    suspend fun update(coordinate: Coordinate) { coordinateDao.update(coordinate) }
    suspend fun deleteById(id: String) { coordinateDao.deleteById(id) }
    suspend fun deleteAll() { coordinateDao.deleteAll() }

    // Snapshot fetch of all coordinates (non‑reactive)
    suspend fun getAllCoordinatesList(): List<Coordinate> = coordinateDao.getAllCoordinatesList()

    // Lookup by primary key
    suspend fun getById(id: String): Coordinate? = coordinateDao.getById(id)

    // --- Optional convenience helpers (currently unused; keep if planning analytics / maintenance tasks) ---
    suspend fun count(): Int = coordinateDao.count()            // For quick stats / diagnostics
    suspend fun pruneOlderThan(cutoffEpochMs: Long) = coordinateDao.deleteOlderThan(cutoffEpochMs) // Retention policy hook
}
