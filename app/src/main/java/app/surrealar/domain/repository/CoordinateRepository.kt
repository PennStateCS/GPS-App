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
 *  - CSV/GeoJSON export → `app.surrealar.data.export` (`CsvExporter`, `GeoJsonExporter`)
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
    suspend fun deleteById(id: String)
    suspend fun deleteAll()

    // Single-shot reads
    suspend fun getAllCoordinatesList(): List<Coordinate>
    suspend fun getById(id: String): Coordinate?
    suspend fun count(): Int
}
