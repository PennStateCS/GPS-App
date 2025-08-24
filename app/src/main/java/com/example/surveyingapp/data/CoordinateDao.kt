package com.example.surveyingapp.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface CoordinateDao {
    @Query("SELECT * FROM coordinates ORDER BY timestamp DESC")
    fun getAllCoordinates(): LiveData<List<Coordinate>>

    @Query("SELECT * FROM coordinates ORDER BY timestamp DESC")
    suspend fun getAllCoordinatesList(): List<Coordinate>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(coordinate: Coordinate)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(coordinates: List<Coordinate>)

    @Update
    suspend fun update(coordinate: Coordinate)

    @Query("DELETE FROM coordinates")
    suspend fun deleteAll()

    @Query("DELETE FROM coordinates WHERE id = :id")
    suspend fun deleteById(id: String)
}
