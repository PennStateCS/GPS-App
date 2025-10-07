package com.example.surveyingapp.data.local.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.example.surveyingapp.data.local.entity.CoordinateEntity
import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.gnss.model.RtkStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface CoordinateDao {
    // --- Core retrieval ---
    @Query("SELECT * FROM coordinates ORDER BY timestamp DESC")
    fun getAllCoordinates(): LiveData<List<CoordinateEntity>>

    @Query("SELECT * FROM coordinates ORDER BY timestamp DESC")
    suspend fun getAllCoordinatesList(): List<CoordinateEntity>

    // --- Mutations ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(coordinate: CoordinateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(coordinates: List<CoordinateEntity>)

    @Update
    suspend fun update(coordinate: CoordinateEntity)

    @Query("DELETE FROM coordinates")
    suspend fun deleteAll()

    @Query("DELETE FROM coordinates WHERE id = :id")
    suspend fun deleteById(id: String)

    // --- Reactive Flow ---
    @Query("SELECT * FROM coordinates ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<CoordinateEntity>>

    // --- Targeted retrieval ---
    @Query("SELECT * FROM coordinates ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<CoordinateEntity>

    @Query("SELECT * FROM coordinates WHERE timestamp BETWEEN :startMs AND :endMs ORDER BY timestamp DESC")
    suspend fun getBetween(startMs: Long, endMs: Long): List<CoordinateEntity>

    @Query("""
        SELECT * FROM coordinates
        WHERE latitude  BETWEEN :minLat AND :maxLat
          AND longitude BETWEEN :minLon AND :maxLon
        ORDER BY timestamp DESC
    """)
    suspend fun getWithinBBox(
        minLat: Double, minLon: Double,
        maxLat: Double, maxLon: Double
    ): List<CoordinateEntity>

    @Query("""
        SELECT * FROM coordinates
        ORDER BY ((latitude - :lat)*(latitude - :lat) + (longitude - :lon)*(longitude - :lon)) ASC
        LIMIT :limit
    """)
    suspend fun getNearest(lat: Double, lon: Double, limit: Int = 1): List<CoordinateEntity>

    @Query("SELECT * FROM coordinates WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CoordinateEntity?

    @Query("SELECT COUNT(*) FROM coordinates")
    suspend fun count(): Int

    @Query("DELETE FROM coordinates WHERE timestamp < :olderThanMs")
    suspend fun deleteOlderThan(olderThanMs: Long)

    // --- Enum-typed filters (updated) ---
    @Query("SELECT * FROM coordinates WHERE provider = :provider ORDER BY timestamp DESC")
    suspend fun getByProvider(provider: Provider): List<CoordinateEntity>

    @Query("SELECT * FROM coordinates WHERE rtkStatus = :rtk ORDER BY timestamp DESC")
    suspend fun getByRtkStatus(rtk: RtkStatus): List<CoordinateEntity>

    @Query("SELECT * FROM coordinates WHERE hdop IS NULL OR hdop <= :maxHdop ORDER BY timestamp DESC")
    suspend fun getWithMaxHdop(maxHdop: Double): List<CoordinateEntity>

    @Query("SELECT rtkStatus AS status, COUNT(*) AS count FROM coordinates GROUP BY rtkStatus")
    suspend fun countByRtkStatus(): List<RtkStatusCount>
}

data class RtkStatusCount(
    val status: RtkStatus?,   // now typed
    val count: Int
)
