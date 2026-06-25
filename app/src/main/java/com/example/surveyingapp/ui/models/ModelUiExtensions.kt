package com.example.surveyingapp.ui.models

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.surveyingapp.domain.model.Model
import java.io.File

// Android-specific UI helper for resolving a thumbnail Uri. Moved out of the domain model package
// so domain stays free of Android platform dependencies. Uses FileProvider if the file is inside
// app-specific storage; otherwise falls back to Uri.fromFile. Adjust authority to match the
// manifest FileProvider entry.
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
