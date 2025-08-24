package com.example.surveyingapp.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface PointDao {
    @Query("SELECT * FROM points ORDER BY timestamp DESC")
    fun getAllPoints(): LiveData<List<Point>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(point: Point)

    @Update
    suspend fun update(point: Point)

    @Query("DELETE FROM points")
    suspend fun deleteAll()

    @Query("DELETE FROM points WHERE id = :id")
    suspend fun deleteById(id: String)
}
