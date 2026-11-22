package app.surrealar.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelFileCleanerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun modelsRoot(): File = tmp.newFolder("files", "models")

    @Test
    fun deleteModelFile_glbDirectlyInModels_deletesOnlyThatFile() {
        val root = modelsRoot()
        val glb = File(root, "house.glb").apply { writeText("x") }
        val other = File(root, "other.glb").apply { writeText("y") }

        assertTrue(ModelFileCleaner.deleteModelFile(glb.absolutePath, root))
        assertFalse(glb.exists())
        assertTrue(other.exists())
        assertTrue(root.exists())
    }

    @Test
    fun deleteModelFile_gltfBundle_deletesWholeFolder() {
        val root = modelsRoot()
        val bundle = File(root, "bridge").apply { mkdirs() }
        val gltf = File(bundle, "bridge.gltf").apply { writeText("{}") }
        File(bundle, "bridge.bin").writeText("b")
        File(bundle, "textures").mkdirs()
        File(bundle, "textures/wood.png").writeText("p")
        val sibling = File(root, "bridge_1").apply { mkdirs() }
        File(sibling, "bridge.gltf").writeText("{}")

        assertTrue(ModelFileCleaner.deleteModelFile(gltf.absolutePath, root))
        assertFalse(bundle.exists())
        assertTrue(sibling.exists())
        assertTrue(root.exists())
    }

    @Test
    fun deleteModelFile_outsideModelsRoot_isNeverDeleted() {
        val root = modelsRoot()
        val outside = tmp.newFile("elsewhere.glb")
        assertFalse(ModelFileCleaner.deleteModelFile(outside.absolutePath, root))
        assertTrue(outside.exists())

        // Path traversal out of the models folder is refused too.
        val sneaky = File(root, "../elsewhere2.glb")
        File(root.parentFile, "elsewhere2.glb").writeText("x")
        assertFalse(ModelFileCleaner.deleteModelFile(sneaky.path, root))
        assertTrue(File(root.parentFile, "elsewhere2.glb").exists())
    }

    @Test
    fun deleteModelFile_rootItself_isNotDeleted() {
        val root = modelsRoot()
        File(root, "a.glb").writeText("x")
        assertFalse(ModelFileCleaner.deleteModelFile(root.absolutePath, root))
        assertTrue(File(root, "a.glb").exists())
    }

    @Test
    fun deleteModelFile_missingFile_returnsFalseGracefully() {
        val root = modelsRoot()
        assertFalse(ModelFileCleaner.deleteModelFile(File(root, "nope.glb").absolutePath, root))
    }

    @Test
    fun deleteModelFile_nullOrBlank_isNoOp() {
        val root = modelsRoot()
        assertFalse(ModelFileCleaner.deleteModelFile(null, root))
        assertFalse(ModelFileCleaner.deleteModelFile("", root))
        assertFalse(ModelFileCleaner.deleteModelFile("   ", root))
    }

    @Test
    fun deletionTarget_mapsFilesToFileOrBundle() {
        val root = modelsRoot()
        assertEquals(File(root, "a.glb").canonicalFile,
            ModelFileCleaner.deletionTarget(File(root, "a.glb"), root))
        assertEquals(File(root, "b").canonicalFile,
            ModelFileCleaner.deletionTarget(File(root, "b/sub/c.png"), root))
        assertNull(ModelFileCleaner.deletionTarget(root, root))
        assertNull(ModelFileCleaner.deletionTarget(root.parentFile!!, root))
    }

    @Test
    fun deleteThumbnailFile_deletesExistingFile() {
        val f = File.createTempFile("thumb_test", ".png")
        assertTrue(f.exists())
        ModelFileCleaner.deleteThumbnailFile(f.absolutePath)
        assertFalse("Thumbnail should be gone after deletion", f.exists())
    }

    @Test
    fun deleteThumbnailFile_nullBlankOrMissing_isNoOpAndDoesNotThrow() {
        // None of these should throw.
        ModelFileCleaner.deleteThumbnailFile(null)
        ModelFileCleaner.deleteThumbnailFile("")
        ModelFileCleaner.deleteThumbnailFile(
            File(System.getProperty("java.io.tmpdir"), "missing_${System.nanoTime()}.png").absolutePath
        )
    }
}
