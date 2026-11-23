package app.surrealar.data.repository.impl

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.surrealar.data.local.db.AppDatabase
import app.surrealar.data.local.entity.ModelEntity
import app.surrealar.domain.model.BoundingBox
import app.surrealar.domain.model.FileType
import app.surrealar.domain.model.Model
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [ModelRepositoryImpl] against a real (in-memory) Room database: persistence, mapping, deletion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelRepositoryImplRoomTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var repo: ModelRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = ModelRepositoryImpl(db.modelDao())
    }

    @After
    fun tearDown() = db.close()

    private fun model(id: String = "m1", fileName: String = "tower.glb", dateAdded: Long = 5L) = Model(
        id = id, name = "Tower $id", fileName = fileName, filePath = "/models/$fileName",
        fileSize = 3_000_000_000L, dateAdded = dateAdded, description = "Lattice tower",
        fileType = FileType.MESH_MODEL,
        thumbnailFileName = "$id.png", thumbnailFilePath = "/thumbs/$id.png",
        embeddedLatitude = 41.246813579246813, embeddedLongitude = -75.88112233445566, embeddedAltitudeM = 187.125,
        checksum = "sha256:00ff", isValid = false, validationErrors = listOf("missing texture", "quote \" and , comma"),
        boundingBox = BoundingBox(minLat = 41.1, maxLat = 41.2, minLon = -76.2, maxLon = -76.1),
        defaultScale = 0.001, defaultYawDeg = -12.5, originOffsetXM = 1.25, originOffsetYM = -2.5, originOffsetZM = 0.75,
        units = "mm",
    )

    @Test
    fun everyPersistedField_roundTripsThroughSqlite() = runBlocking {
        val m = model(fileName = "tower.obj")
        repo.insertModel(m)
        val back = repo.getModelById("m1")!!
        // Fields the entity does not persist keep their defaults; the rest must match exactly.
        assertEquals(m, back)
    }

    @Test
    fun nullAndDefaultMetadata_staysNullInTheTable() = runBlocking {
        repo.insertModel(
            Model(id = "d", name = "D", fileName = "d.glb", filePath = "/d.glb", fileSize = 0L, dateAdded = 0L,
                fileType = FileType.OTHER)
        )
        val entity = db.modelDao().getModelById("d")!!
        assertNull(entity.validationErrorsJson)
        assertNull(entity.boundingBoxJson)
        assertNull(entity.embeddedLatitude)
        assertTrue(entity.isValid)
        assertEquals(1.0, entity.defaultScale, 0.0)

        val back = repo.getModelById("d")!!
        assertNull(back.boundingBox)
        assertTrue(back.validationErrors.isEmpty())
        assertNull(back.embeddedAltitudeM)
    }

    @Test
    fun missingRows_returnNull() = runBlocking {
        assertNull(repo.getModelById("nope"))
        assertNull(repo.getModelByFileName("nope.glb"))
    }

    @Test
    fun getModelByFileName_findsTheRow() = runBlocking {
        repo.insertModel(model("a", "a.glb"))
        repo.insertModel(model("b", "b.glb"))
        assertEquals("b", repo.getModelByFileName("b.glb")!!.id)
    }

    @Test
    fun getAllModels_isNewestFirst_andCountIsObserved() = runBlocking {
        repo.insertModel(model("old", "old.glb", dateAdded = 1L))
        repo.insertModel(model("new", "new.glb", dateAdded = 9L))
        repo.insertModel(model("mid", "mid.glb", dateAdded = 5L))
        assertEquals(listOf("new", "mid", "old"), repo.getAllModels().first().map { it.id })
        assertEquals(3, repo.observeModelCount().first())
    }

    @Test
    fun insertWithSameId_isRejected_andKeepsTheOriginalRow() = runBlocking {
        // ModelDao.insertModel uses the default ABORT strategy: a duplicate id never silently overwrites.
        repo.insertModel(model())
        val result = runCatching { repo.insertModel(model().copy(name = "Renamed")) }
        assertTrue(result.exceptionOrNull() is android.database.sqlite.SQLiteConstraintException)
        assertEquals(listOf("Tower m1"), repo.getAllModels().first().map { it.name })
    }

    @Test
    fun updateModel_changesMetadata_andClearsLists() = runBlocking {
        repo.insertModel(model())
        repo.updateModel(model().copy(isValid = true, validationErrors = emptyList(), boundingBox = null, units = "m", defaultScale = 2.0))
        val back = repo.getModelById("m1")!!
        assertTrue(back.isValid)
        assertTrue(back.validationErrors.isEmpty())
        assertNull(back.boundingBox)
        assertEquals("m", back.units)
        assertEquals(2.0, back.defaultScale, 0.0)
        assertNull(db.modelDao().getModelById("m1")!!.validationErrorsJson)
    }

    @Test
    fun deleteModel_removesTheRow_andItsThumbnailFile() = runBlocking {
        val thumb = tmp.newFile("m1.png").apply { writeText("png") }
        val m = model().copy(thumbnailFilePath = thumb.absolutePath)
        repo.insertModel(m)
        repo.insertModel(model("keep", "keep.glb"))

        repo.deleteModel(m)

        assertNull(repo.getModelById("m1"))
        assertFalse(thumb.exists())
        assertEquals(listOf("keep"), repo.getAllModels().first().map { it.id })
        assertEquals(1, repo.observeModelCount().first())
    }

    @Test
    fun deleteModel_withoutThumbnail_stillDeletesTheRow() = runBlocking {
        val m = model().copy(thumbnailFilePath = null)
        repo.insertModel(m)
        repo.deleteModel(m)
        assertNull(repo.getModelById("m1"))
    }

    @Test
    fun corruptJsonColumns_decodeToEmpty_insteadOfFailingTheRead() = runBlocking {
        db.modelDao().insertModel(
            ModelEntity(id = "x", name = "X", fileName = "x.glb", filePath = "/x.glb", fileSize = 1L, dateAdded = 0L,
                validationErrorsJson = "{\"not\":\"an array\"}", boundingBoxJson = "{\"minLat\":1.0}")
        )
        val back = repo.getModelById("x")!!
        assertTrue(back.validationErrors.isEmpty())
        assertNull(back.boundingBox)   // incomplete box is dropped, not filled with zeros
    }

    @Test
    fun fileType_isDerivedFromTheExtension_caseInsensitively() = runBlocking {
        val expected = linkedMapOf(
            "pts.csv" to FileType.COORDINATE_DATA, "a.KML" to FileType.COORDINATE_DATA, "t.gpx" to FileType.COORDINATE_DATA,
            "r.nmea" to FileType.NMEA_LOG, "r.log" to FileType.NMEA_LOG,
            "p.dwg" to FileType.CAD_DRAWING, "p.DXF" to FileType.CAD_DRAWING,
            "i.jpg" to FileType.IMAGE, "i.jpeg" to FileType.IMAGE, "i.png" to FileType.IMAGE,
            "i.tiff" to FileType.IMAGE, "i.tif" to FileType.IMAGE,
            "c.las" to FileType.POINT_CLOUD, "c.laz" to FileType.POINT_CLOUD,
            "m.obj" to FileType.MESH_MODEL, "m.ply" to FileType.MESH_MODEL, "m.Stl" to FileType.MESH_MODEL,
            "d.pdf" to FileType.REPORT,
            "x.db" to FileType.DATABASE, "x.sqlite" to FileType.DATABASE, "x.sql" to FileType.DATABASE,
            "k.json" to FileType.CONFIGURATION, "k.xml" to FileType.CONFIGURATION,
            "k.cfg" to FileType.CONFIGURATION, "k.ini" to FileType.CONFIGURATION,
            "noext" to FileType.OTHER, "weird.xyz" to FileType.OTHER,
        )
        expected.keys.forEachIndexed { i, name -> repo.insertModel(model("f$i", name)) }
        expected.entries.forEachIndexed { i, (name, type) ->
            assertEquals(name, type, repo.getModelById("f$i")!!.fileType)
        }
    }
}
