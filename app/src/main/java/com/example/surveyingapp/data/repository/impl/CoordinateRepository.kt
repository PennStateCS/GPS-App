package com.example.surveyingapp.data.repository.impl

import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import com.example.surveyingapp.data.local.dao.CoordinateDao
import com.example.surveyingapp.domain.model.Coordinate
import com.example.surveyingapp.domain.repository.CoordinateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Use the actual mapper package you shared:
import com.example.surveyingapp.data.repository.mapper.toDomain
import com.example.surveyingapp.data.repository.mapper.toEntity

class CoordinateRepositoryImpl(
    private val coordinateDao: CoordinateDao
) : CoordinateRepository {

    // LiveData stream (legacy/Views)
    override val allCoordinates: LiveData<List<Coordinate>> =
        coordinateDao.getAllCoordinates().map { rows -> rows.map { it.toDomain() } }

    // Flow stream (preferred for coroutines/Compose)
    override val allCoordinatesFlow: Flow<List<Coordinate>> =
        coordinateDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    // Mutations
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

    // Queries
    override suspend fun getAllCoordinatesList(): List<Coordinate> =
        coordinateDao.getAllCoordinatesList().map { it.toDomain() }

    override suspend fun getById(id: String): Coordinate? =
        coordinateDao.getById(id)?.toDomain()

    override suspend fun count(): Int = coordinateDao.count()

    override suspend fun pruneOlderThan(cutoffEpochMs: Long) {
        coordinateDao.deleteOlderThan(cutoffEpochMs)
    }
}
