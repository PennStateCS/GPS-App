package com.example.surveyingapp.domain.repository

import androidx.lifecycle.LiveData
import com.example.surveyingapp.domain.model.Coordinate
import kotlinx.coroutines.flow.Flow

interface CoordinateRepository {
    val allCoordinates: LiveData<List<Coordinate>>
    val allCoordinatesFlow: Flow<List<Coordinate>>
    suspend fun insert(coordinate: Coordinate)
    suspend fun insertAll(coordinates: List<Coordinate>)
    suspend fun update(coordinate: Coordinate)
    suspend fun deleteById(id: String)
    suspend fun deleteAll()
    suspend fun getAllCoordinatesList(): List<Coordinate>
    suspend fun getById(id: String): Coordinate?
    suspend fun count(): Int
    suspend fun pruneOlderThan(cutoffEpochMs: Long)
}

