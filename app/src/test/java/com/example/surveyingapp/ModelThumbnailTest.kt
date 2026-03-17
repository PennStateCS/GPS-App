package com.example.surveyingapp

import com.example.surveyingapp.domain.model.Model
// thumbnailFileExists is an extension on Model; no top-level import needed
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class ModelThumbnailTest {
    @Test
    fun thumbnailFileExists_detects_existing_file() {
        val tmp = File.createTempFile("thumb_test", ".png")
        tmp.writeText("dummy")
        try {
            val model = Model(
                id = "t1",
                name = "Test",
                fileName = "model.obj",
                filePath = "/tmp/model.obj",
                fileSize = 123L,
                dateAdded = System.currentTimeMillis(),
                description = null,
                fileType = com.example.surveyingapp.domain.model.FileType.MESH_MODEL,
                mimeType = null,
                checksum = null,
                lastModified = null,
                thumbnailFileName = tmp.name,
                thumbnailFilePath = tmp.absolutePath,
                projectId = null,
                surveyDate = null,
                coordinateCount = null,
                boundingBox = null,
                isValid = true,
                validationErrors = emptyList(),
                isImported = false,
                importDate = null,
                tags = emptySet(),
                category = com.example.surveyingapp.domain.model.FileCategory.OTHER,
                isReadOnly = false,
                createdBy = null,
                lastAccessedDate = null
            )

            assertTrue(model.thumbnailFileExists())
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun thumbnailFileExists_detects_missing_file() {
        val model = Model(
            id = "t2",
            name = "Test2",
            fileName = "model.obj",
            filePath = "/tmp/model2.obj",
            fileSize = 123L,
            dateAdded = System.currentTimeMillis(),
            description = null,
            fileType = com.example.surveyingapp.domain.model.FileType.MESH_MODEL,
            mimeType = null,
            checksum = null,
            lastModified = null,
            thumbnailFileName = "nope.png",
            thumbnailFilePath = "/no/such/path/nope.png",
            projectId = null,
            surveyDate = null,
            coordinateCount = null,
            boundingBox = null,
            isValid = true,
            validationErrors = emptyList(),
            isImported = false,
            importDate = null,
            tags = emptySet(),
            category = com.example.surveyingapp.domain.model.FileCategory.OTHER,
            isReadOnly = false,
            createdBy = null,
            lastAccessedDate = null
        )

        assertFalse(model.thumbnailFileExists())
    }
}
