package app.surrealar.data.repository.impl

import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import app.surrealar.data.local.dao.CoordinateDao
import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.repository.CoordinateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Use the actual mapper package you shared:
import app.surrealar.data.repository.mapper.toDomain
import app.surrealar.data.repository.mapper.toEntity

/**
 * Room-backed [CoordinateRepository]. Core CRUD + reads only — see the interface KDoc for where
 * validation, statistics, and export logic live.
 */
class CoordinateRepositoryImpl @javax.inject.Inject constructor(
    private val coordinateDao: CoordinateDao
) : CoordinateRepository {

    // LiveData stream (legacy/Views)
    override val allCoordinates: LiveData<List<Coordinate>> =
        coordinateDao.getAllCoordinates().map { rows -> rows.map { it.toDomain() } }

    // Flow stream (preferred for coroutines/Compose)
    override val allCoordinatesFlow: Flow<List<Coordinate>> =
        coordinateDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override val coordinateCountFlow: Flow<Int> = coordinateDao.observeCoordinateCount()

    // Basic CRUD operations
    override suspend fun insert(coordinate: Coordinate) {
        coordinateDao.insert(coordinate.toEntity())
    }

    override suspend fun insertAll(coordinates: List<Coordinate>) {
        coordinateDao.insertAll(coordinates.map { it.toEntity() })
    }

    override suspend fun update(coordinate: Coordinate) {
        coordinateDao.update(coordinate.toEntity())
    }

    override suspend fun deleteById(id: String) {
        coordinateDao.deleteById(id)
    }

    override suspend fun deleteAll() {
        coordinateDao.deleteAll()
    }

    // Single-shot reads
    override suspend fun getAllCoordinatesList(): List<Coordinate> =
        coordinateDao.getAllCoordinatesList().map { it.toDomain() }

    override suspend fun getById(id: String): Coordinate? =
        coordinateDao.getById(id)?.toDomain()

    override suspend fun count(): Int = coordinateDao.count()
}
