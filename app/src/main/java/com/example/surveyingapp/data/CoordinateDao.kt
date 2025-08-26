package com.example.surveyingapp.data

import androidx.lifecycle.LiveData
import androidx.room.*

/**
 * Data Access Object (DAO) for Coordinate database operations.
 *
 * A DAO defines the methods that your app uses to interact with the database.
 * Room automatically generates the implementation of these methods.
 *
 * @Dao annotation tells Room this interface contains database access methods.
 * suspend functions run on background threads to avoid blocking the UI.
 * LiveData automatically updates the UI when database data changes.
 */
@Dao
interface CoordinateDao {
    // Query methods - used to read data from the database
    @Query("SELECT * FROM coordinates ORDER BY timestamp DESC")
    fun getAllCoordinates(): LiveData<List<Coordinate>>  // Returns LiveData that updates automatically

    @Query("SELECT * FROM coordinates ORDER BY timestamp DESC")
    suspend fun getAllCoordinatesList(): List<Coordinate>  // Returns a one-time snapshot of data

    // Insert methods - used to add new data to the database
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(coordinate: Coordinate)  // Add a single coordinate

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(coordinates: List<Coordinate>)  // Add multiple coordinates at once

    // Update method - used to modify existing data
    @Update
    suspend fun update(coordinate: Coordinate)  // Update an existing coordinate

    // Delete methods - used to remove data from the database
    @Query("DELETE FROM coordinates")
    suspend fun deleteAll()  // Remove all coordinates (careful!)

    @Query("DELETE FROM coordinates WHERE id = :id")
    suspend fun deleteById(id: String)  // Remove a specific coordinate by its ID
}
