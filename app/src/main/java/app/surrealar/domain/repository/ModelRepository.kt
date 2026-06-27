package app.surrealar.domain.repository

import app.surrealar.domain.model.Model
import kotlinx.coroutines.flow.Flow

/**
 * Domain abstraction for imported 3D model data access. UI/ViewModels should depend on this
 * interface rather than the concrete `ModelRepositoryImpl`, mirroring [CoordinateRepository].
 *
 * Implementations map Room entities to domain [Model]s and own thumbnail-file cleanup on delete.
 */
interface ModelRepository {

    /** Observes all models, newest mapping rules applied (fileType re-derived from filename). */
    fun getAllModels(): Flow<List<Model>>

    /** Observes the total number of models. */
    fun observeModelCount(): Flow<Int>

    /** One-shot model lookup by id, or null if not found. */
    suspend fun getModelById(id: String): Model?

    /** One-shot model lookup by file name, or null if not found. */
    suspend fun getModelByFileName(fileName: String): Model?

    /** Inserts a new model. */
    suspend fun insertModel(model: Model)

    /** Updates an existing model. */
    suspend fun updateModel(model: Model)

    /**
     * Deletes a model row and its **thumbnail** file from disk (if present).
     *
     * Note: this does NOT delete the imported model file — the model-list UI removes that (it also
     * guards against deleting a model still linked to coordinates). Both file deletions go through
     * [app.surrealar.data.files.ModelFileCleaner]. See docs/data-architecture.md.
     */
    suspend fun deleteModel(model: Model)
}
