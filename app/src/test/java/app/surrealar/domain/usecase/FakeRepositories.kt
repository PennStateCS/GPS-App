package app.surrealar.domain.usecase

import androidx.lifecycle.LiveData
import app.surrealar.domain.model.Coordinate
import app.surrealar.domain.model.Model
import app.surrealar.domain.repository.CoordinateRepository
import app.surrealar.domain.repository.ModelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** In-memory [CoordinateRepository] for use-case tests. Tracks writes for assertions. */
class FakeCoordinateRepository(initial: List<Coordinate> = emptyList()) : CoordinateRepository {
    val store = initial.toMutableList()
    var deleteAllCount = 0

    override val allCoordinates: LiveData<List<Coordinate>> get() = error("unused in tests")
    override val allCoordinatesFlow: Flow<List<Coordinate>> get() = flowOf(store.toList())
    override val coordinateCountFlow: Flow<Int> get() = flowOf(store.size)

    override suspend fun insert(coordinate: Coordinate) {
        store.removeAll { it.id == coordinate.id }
        store += coordinate
    }

    override suspend fun insertAll(coordinates: List<Coordinate>) {
        coordinates.forEach { c -> store.removeAll { it.id == c.id }; store += c }
    }

    override suspend fun update(coordinate: Coordinate) = insert(coordinate)
    var updateByIdCount = 0
    /** When set, [updateById] throws before touching [store] (simulates a rolled-back transaction). */
    var failUpdate: Exception? = null
    override suspend fun updateById(id: String, transform: (Coordinate) -> Coordinate): Coordinate? {
        failUpdate?.let { throw it }
        val c = getById(id) ?: return null
        val updated = transform(c)
        updateByIdCount++
        insert(updated)
        return updated
    }
    override suspend fun deleteById(id: String) { store.removeAll { it.id == id } }
    override suspend fun deleteAll() { deleteAllCount++; store.clear() }
    var replaceAllCount = 0
    /** When set, [replaceAll] throws before touching [store] (simulates a rolled-back transaction). */
    var failReplace: Exception? = null
    override suspend fun replaceAll(coordinates: List<Coordinate>) {
        failReplace?.let { throw it }
        replaceAllCount++
        deleteAllCount++
        store.clear()
        insertAll(coordinates)
    }
    override suspend fun getAllCoordinatesList(): List<Coordinate> = store.toList()
    override suspend fun getById(id: String): Coordinate? = store.firstOrNull { it.id == id }
    override suspend fun count(): Int = store.size
}

/** In-memory [ModelRepository] for use-case tests. */
class FakeModelRepository(private val models: List<Model> = emptyList()) : ModelRepository {
    override fun getAllModels(): Flow<List<Model>> = flowOf(models)
    override fun observeModelCount(): Flow<Int> = flowOf(models.size)
    override suspend fun getModelById(id: String): Model? = models.firstOrNull { it.id == id }
    override suspend fun getModelByFileName(fileName: String): Model? = models.firstOrNull { it.fileName == fileName }
    override suspend fun insertModel(model: Model) {}
    override suspend fun updateModel(model: Model) {}
    override suspend fun deleteModel(model: Model) {}
}
