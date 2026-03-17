package com.example.surveyingapp.domain.model

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

// Extension to provide a Uri for thumbnail. Uses FileProvider if file is inside app-specific storage;
// otherwise uses Uri.fromFile as a fallback. Adjust authority to match manifest FileProvider entry.
fun Model.getThumbnailUri(context: Context): Uri? {
    val path = this.thumbnailFilePath ?: return null
    val file = File(path)
    if (!file.exists()) return null

    // If a FileProvider is configured, prefer that for sharing; otherwise return file:// Uri
    return try {
        // Replace with your app's FileProvider authority if defined in Manifest
        val authority = context.packageName + ".fileprovider"
        FileProvider.getUriForFile(context, authority, file)
    } catch (e: IllegalArgumentException) {
        Uri.fromFile(file)
    } catch (e: Exception) {
        // Fallback
        Uri.fromFile(file)
    }
}

// Lightweight check to use in unit tests (no Android dependencies): checks path exists on JVM.
fun Model.thumbnailFileExists(): Boolean {
    val path = this.thumbnailFilePath ?: return false
    return try {
        val file = File(path)
        file.exists() && file.isFile
    } catch (e: Exception) {
        false
    }
}

