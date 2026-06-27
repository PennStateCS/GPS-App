package com.example.surveyingapp.data.repository.impl

import com.example.surveyingapp.data.local.dao.ModelDao
import com.example.surveyingapp.data.local.entity.ModelEntity
import com.example.surveyingapp.domain.model.BoundingBox
import com.example.surveyingapp.domain.model.FileType
import com.example.surveyingapp.domain.model.Model
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** In-memory ModelDao so the repository's entity<->domain mapping (incl. JSON helpers) is exercised. */
private class FakeModelDao : ModelDao {
    val store = LinkedHashMap<String, ModelEntity>()
    override fun getAllModels(): Flow<List<ModelEntity>> = flowOf(store.values.toList())
    override suspend fun getAllModelsList(): List<ModelEntity> = store.values.toList()
    override suspend fun getModelById(id: String): ModelEntity? = store[id]
    override suspend fun getModelByFileName(fileName: String): ModelEntity? =
        store.values.firstOrNull { it.fileName == fileName }
    override suspend fun insertModel(model: ModelEntity) { store[model.id] = model }
    override suspend fun updateModel(model: ModelEntity) { store[model.id] = model }
    override suspend fun deleteModel(model: ModelEntity) { store.remove(model.id) }
    override suspend fun deleteModelById(id: String) { store.remove(id) }
    override suspend fun getModelCount(): Int = store.size
    override fun observeModelCount(): Flow<Int> = flowOf(store.size)
}

// org.json is stubbed in plain JVM unit tests; Robolectric supplies a real implementation.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelRepositoryImplMappingTest {

    private val dao = FakeModelDao()
    private val repo = ModelRepositoryImpl(dao)

    private fun model(id: String = "m1") = Model(
        id = id, name = "Tower", fileName = "tower.glb", filePath = "/tower.glb",
        fileSize = 10L, dateAdded = 5L, fileType = FileType.MESH_MODEL,
        checksum = "abc123", isValid = false,
        validationErrors = listOf("missing texture", "non-metric units"),
        boundingBox = BoundingBox(minLat = 1.0, maxLat = 2.0, minLon = 3.0, maxLon = 4.0),
        defaultScale = 2.0, defaultYawDeg = 45.0,
        originOffsetXM = 1.0, originOffsetYM = 2.0, originOffsetZM = 3.0, units = "meters"
    )

    @Test
    fun fullMetadata_roundTripsThroughRepository() = runBlocking {
        repo.insertModel(model())
        val back = repo.getModelById("m1")!!

        assertEquals("abc123", back.checksum)
        assertEquals(false, back.isValid)
        assertEquals(listOf("missing texture", "non-metric units"), back.validationErrors)
        assertEquals(BoundingBox(1.0, 2.0, 3.0, 4.0), back.boundingBox)
        assertEquals(2.0, back.defaultScale, 1e-9)
        assertEquals(45.0, back.defaultYawDeg, 1e-9)
        assertEquals(1.0, back.originOffsetXM, 1e-9)
        assertEquals(2.0, back.originOffsetYM, 1e-9)
        assertEquals(3.0, back.originOffsetZM, 1e-9)
        assertEquals("meters", back.units)
    }

    @Test
    fun nullAndEmptyMetadata_roundTripCleanly() = runBlocking {
        repo.insertModel(
            model().copy(boundingBox = null, validationErrors = emptyList(), units = null, checksum = null)
        )
        // The stored entity should carry null JSON (not "null"/"[]" strings).
        val entity = dao.getModelById("m1")!!
        assertNull(entity.boundingBoxJson)
        assertNull(entity.validationErrorsJson)

        val back = repo.getModelById("m1")!!
        assertNull(back.boundingBox)
        assertTrue(back.validationErrors.isEmpty())
        assertNull(back.units)
        assertNull(back.checksum)
    }

    @Test
    fun malformedJson_doesNotCrash() = runBlocking {
        // Seed a raw entity with corrupt JSON directly (bypassing the mapper).
        dao.insertModel(
            ModelEntity(
                id = "bad", name = "Bad", fileName = "b.glb", filePath = "/b.glb",
                fileSize = 1L, dateAdded = 0L,
                boundingBoxJson = "{ not json", validationErrorsJson = "[ broken"
            )
        )
        val back = repo.getModelById("bad")!!
        assertNull(back.boundingBox)            // decode failure -> null, no crash
        assertTrue(back.validationErrors.isEmpty())
    }

    @Test
    fun defaultMetadata_survivesMapping() = runBlocking {
        // A model created with only the required fields keeps the safe placement defaults.
        repo.insertModel(
            Model(
                id = "d1", name = "Default", fileName = "d.glb", filePath = "/d.glb",
                fileSize = 1L, dateAdded = 0L, fileType = FileType.MESH_MODEL
            )
        )
        val back = repo.getModelById("d1")!!
        assertEquals(1.0, back.defaultScale, 1e-9)
        assertEquals(0.0, back.defaultYawDeg, 1e-9)
        assertEquals(0.0, back.originOffsetXM, 1e-9)
        assertEquals(true, back.isValid)
    }
}
