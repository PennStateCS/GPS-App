package com.example.surveyingapp.data.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelFileCleanerTest {

    @Test
    fun deleteModelFile_deletesExistingFile() {
        val f = File.createTempFile("model_test", ".glb")
        assertTrue(f.exists())
        assertTrue(ModelFileCleaner.deleteModelFile(f.absolutePath))
        assertFalse("File should be gone after deletion", f.exists())
    }

    @Test
    fun deleteModelFile_missingFile_returnsFalseGracefully() {
        val path = File(System.getProperty("java.io.tmpdir"), "does_not_exist_${System.nanoTime()}.glb").absolutePath
        assertFalse(ModelFileCleaner.deleteModelFile(path))
    }

    @Test
    fun deleteModelFile_nullOrBlank_isNoOp() {
        assertFalse(ModelFileCleaner.deleteModelFile(null))
        assertFalse(ModelFileCleaner.deleteModelFile(""))
        assertFalse(ModelFileCleaner.deleteModelFile("   "))
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
