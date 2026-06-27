package app.surrealar.data.local.dao

import androidx.room.*
import app.surrealar.data.local.entity.ModelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY dateAdded DESC")
    fun getAllModels(): Flow<List<ModelEntity>>

    /** Returns all model rows as a one-shot list — used for batch joins in the AR ViewModel. */
    @Query("SELECT * FROM models")
    suspend fun getAllModelsList(): List<ModelEntity>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun getModelById(id: String): ModelEntity?

    @Query("SELECT * FROM models WHERE fileName = :fileName LIMIT 1")
    suspend fun getModelByFileName(fileName: String): ModelEntity?

    @Insert
    suspend fun insertModel(model: ModelEntity)

    @Update
    suspend fun updateModel(model: ModelEntity)

    @Delete
    suspend fun deleteModel(model: ModelEntity)

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun deleteModelById(id: String)

    @Query("SELECT COUNT(*) FROM models")
    suspend fun getModelCount(): Int

    @Query("SELECT COUNT(*) FROM models")
    fun observeModelCount(): Flow<Int>
}
