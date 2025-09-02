package com.example.surveyingapp.ui.filepicker

import java.io.File

sealed class FileItem {
    object BackItem : FileItem()
    object GoogleDriveItem : FileItem()
    object OneDriveItem : FileItem()
    object LocalStorageItem : FileItem()
    data class DirectoryItem(val directory: File) : FileItem()
    data class RegularFileItem(val file: File) : FileItem()
}
