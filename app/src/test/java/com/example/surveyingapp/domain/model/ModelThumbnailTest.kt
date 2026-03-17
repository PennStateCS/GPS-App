package com.example.surveyingapp.domain.model

import org.junit.Test
import org.junit.Assert.*
import java.io.File

class ModelThumbnailTest {
    @Test
    fun thumbnailExistsAndNotExists() {
        val temp = File.createTempFile("model_test_thumb", ".png")
        try {
            temp.writeText("dummy")

            val model = Model(
                id = "test-id",
                name = "Test Model",
                fileName = "test.obj",
                filePath = temp.parentFile?.absolutePath ?: temp.absolutePath,
                fileSize = 42L,
                dateAdded = System.currentTimeMillis(),
                description = null,

                // File metadata
                fileType = FileType.MESH_MODEL,
                mimeType = null,
                checksum = null,
                lastModified = null,

                // Thumbnail
                thumbnailFileName = temp.name,
                thumbnailFilePath = temp.absolutePath,

                // Survey-specific metadata
                projectId = null,
                surveyDate = null,
                coordinateCount = null,
                boundingBox = null,

                // File status and validation
                isValid = true,
                validationErrors = emptyList(),
                isImported = false,
                importDate = null,

                // File tags and categorization
                tags = emptySet(),
                category = FileCategory.OTHER,

                // Access control
                isReadOnly = false,
                createdBy = null,
                lastAccessedDate = null
            )

            assertTrue("thumbnailFileExists should be true when file exists", model.thumbnailFileExists())

            // Delete the temp file and ensure check updates
            assertTrue("failed to delete temp file", temp.delete())
            assertFalse("thumbnailFileExists should be false after deletion", model.thumbnailFileExists())
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}

