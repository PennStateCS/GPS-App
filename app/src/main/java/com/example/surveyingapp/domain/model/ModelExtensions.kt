package com.example.surveyingapp.domain.model

import java.io.File

// Pure JVM model extension (no Android dependencies). Checks whether the thumbnail file referenced
// by the model exists on disk. Safe to use in unit tests without Robolectric.
fun Model.thumbnailFileExists(): Boolean {
    val path = this.thumbnailFilePath ?: return false
    return try {
        val file = File(path)
        file.exists() && file.isFile
    } catch (e: Exception) {
        false
    }
}
