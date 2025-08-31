package com.example.surveyingapp.data.local.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.example.surveyingapp.data.local.entity.CoordinateEntity

/**
 * Data Access Object (DAO) for Coordinate database operations.
 *
 * Notes / improvement ideas (inline comments near methods give specifics):
 * - Prefer Flow over LiveData for new code (Compose-first); keep LiveData for legacy observers.
 * - Consider adding DB indices on (timestamp), (provider), (rtkStatus), and maybe (latitude, longitude) for bbox lookups.
 * - Nearest / bbox queries are naive (degree-space); acceptable for small sets. For large datasets, introduce spatial index or geohash bucketing.
 * - Several filter helpers are currently unused; remove if not planning future UI/stats to keep DAO lean.
 */

@Dao
interface CoordinateDao {
    // --- Core retrieval ---
    @Query("SELECT * FROM coordinates ORDER BY timestamp DESC")
    fun getAllCoordinates(): LiveData<List<CoordinateEntity>> // Legacy reactive stream (UI auto-updates)

    @Query("SELECT * FROM coordinates ORDER BY timestamp DESC")
    suspend fun getAllCoordinatesList(): List<CoordinateEntity> // Snapshot list

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

    // --- Reactive Flow (preferred for new Compose screens) ---
    @Query("SELECT * FROM coordinates ORDER BY timestamp DESC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<CoordinateEntity>>

    // --- Targeted retrieval helpers ---
    @Query("SELECT * FROM coordinates ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<CoordinateEntity> // Ensure caller bounds 'limit'

    @Query("SELECT * FROM coordinates WHERE timestamp BETWEEN :startMs AND :endMs ORDER BY timestamp DESC")
    suspend fun getBetween(startMs: Long, endMs: Long): List<CoordinateEntity> // Inclusive range

    // Bounding box: parameter order (minLat, minLon, maxLat, maxLon) differs from common (minLat, maxLat, minLon, maxLon) => documented here
    // NOTE: Does not handle anti-meridian spanning boxes; caller must split if crossing +/-180°.
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

    // Nearest (simple planar distance approximation in degrees). Good for small radii; for large distances or high latitudes adapt with cos(lat).
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

    // Retention helper: prune old entries. Caller supplies cutoff epoch millis.
    @Query("DELETE FROM coordinates WHERE timestamp < :olderThanMs")
    suspend fun deleteOlderThan(olderThanMs: Long)

    // Provider filter (currently UNUSED). Values expected: "fused", "rs2-bt", "rs2-tcp". Remove if not needed.
    @Query("SELECT * FROM coordinates WHERE provider = :provider ORDER BY timestamp DESC")
    suspend fun getByProvider(provider: String): List<CoordinateEntity>

    // RTK status filter (currently UNUSED). Status values: FIX, FLOAT, DGPS, SINGLE.
    @Query("SELECT * FROM coordinates WHERE rtkStatus = :rtk ORDER BY timestamp DESC")
    suspend fun getByRtkStatus(rtk: String): List<CoordinateEntity>

    // HDOP quality filter (currently UNUSED). Null HDOP treated as passing (could invert depending on policy).
    @Query("SELECT * FROM coordinates WHERE hdop IS NULL OR hdop <= :maxHdop ORDER BY timestamp DESC")
    suspend fun getWithMaxHdop(maxHdop: Double): List<CoordinateEntity>

    // Quick aggregate for status distribution (currently UNUSED in codebase).
    @Query("SELECT rtkStatus AS status, COUNT(*) AS count FROM coordinates GROUP BY rtkStatus")
    suspend fun countByRtkStatus(): List<RtkStatusCount>
}

// Projection for RTK status counts (supports null). Consider moving to its own file if reused broadly.
data class RtkStatusCount(
    val status: String?,
    val count: Int
)
