package app.surrealar.domain.repository

import androidx.lifecycle.LiveData
import app.surrealar.domain.model.Coordinate
import kotlinx.coroutines.flow.Flow

/**
 * Coordinate data access — **core CRUD and reads only**.
 *
 * This interface is intentionally narrow. Logic that is not data access lives elsewhere; do NOT add
 * it back here:
 *  - validation / duplicate detection → [app.surrealar.domain.coordinates.CoordinateValidator]
 *  - statistics (counts, accuracy, bounding box) → [app.surrealar.domain.coordinates.CoordinateStatsCalculator]
 *  - CSV export → `app.surrealar.data.export` (`PointsCsvExporter`)
 *  - capture-to-coordinate construction → [app.surrealar.domain.model.CoordinateFactory]
 *
 * New filtered/spatial queries should be backed by a `CoordinateDao` query rather than in-memory
 * filtering in the repository.
 */
interface CoordinateRepository {
    // Observe streams
    val allCoordinates: LiveData<List<Coordinate>>
    val allCoordinatesFlow: Flow<List<Coordinate>>
    val coordinateCountFlow: Flow<Int>

    // Basic CRUD operations
    suspend fun insert(coordinate: Coordinate)
    suspend fun insertAll(coordinates: List<Coordinate>)
    suspend fun update(coordinate: Coordinate)
    /**
     * Read-modify-write of one coordinate in a single transaction: reads [id], applies [transform] and
     * stores the result. Returns the stored coordinate, or null when [id] doesn't exist. If
     * [transform] or the write fails, the stored coordinate is unchanged.
     */
    suspend fun updateById(id: String, transform: (Coordinate) -> Coordinate): Coordinate?
    suspend fun deleteById(id: String)
    suspend fun deleteAll()
    /** Deletes every coordinate and inserts [coordinates] atomically (all or nothing). */
    suspend fun replaceAll(coordinates: List<Coordinate>)

    // Single-shot reads
    suspend fun getAllCoordinatesList(): List<Coordinate>
    suspend fun getById(id: String): Coordinate?
    suspend fun count(): Int
}
