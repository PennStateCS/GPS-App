package app.surrealar.ui.filepicker

import java.io.File

sealed class FileItem {
    object BackItem : FileItem()
    /** Single entry shown on the root screen — tapping it launches the SAF system picker. */
    object BrowseItem : FileItem()
    data class DirectoryItem(val directory: File) : FileItem()
    data class RegularFileItem(val file: File) : FileItem()
}
