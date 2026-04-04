package com.example.surveyingapp.ui.filepicker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.surveyingapp.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class FilePickerAdapter(
    private val onFileClick: (File) -> Unit,
    private val onBackClick: () -> Unit,
    private val onBrowseClick: () -> Unit
) : ListAdapter<FileItem, FilePickerAdapter.FileViewHolder>(FileDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file_picker, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconFile: ImageView = itemView.findViewById(R.id.icon_file)
        private val textFileName: TextView = itemView.findViewById(R.id.text_file_name)
        private val textFileInfo: TextView = itemView.findViewById(R.id.text_file_info)

        fun bind(item: FileItem) {
            when (item) {
                is FileItem.BackItem -> {
                    iconFile.setImageResource(R.drawable.ic_arrow_back)
                    textFileName.text = ".."
                    textFileInfo.text = "Go back"
                    itemView.setOnClickListener { onBackClick() }
                }
                is FileItem.BrowseItem -> {
                    iconFile.setImageResource(R.drawable.ic_folder)
                    textFileName.text = "Browse files…"
                    textFileInfo.text = "Pick from device, Google Drive, OneDrive, SD card, and more"
                    itemView.setOnClickListener { onBrowseClick() }
                }
                is FileItem.DirectoryItem -> {
                    iconFile.setImageResource(R.drawable.ic_folder)
                    textFileName.text = item.directory.name
                    textFileInfo.text = "Folder"
                    itemView.setOnClickListener { onFileClick(item.directory) }
                }
                is FileItem.RegularFileItem -> {
                    iconFile.setImageResource(R.drawable.ic_file)
                    textFileName.text = item.file.name
                    textFileInfo.text = formatFileInfo(item.file)
                    itemView.setOnClickListener { onFileClick(item.file) }
                }
            }
        }

        private fun formatFileInfo(file: File): String {
            val size = formatFileSize(file.length())
            val date = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(file.lastModified()))
            return "$size • $date"
        }

        private fun formatFileSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
                else -> "${bytes / (1024 * 1024 * 1024)} GB"
            }
        }
    }

    class FileDiffCallback : DiffUtil.ItemCallback<FileItem>() {
        override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
            return when {
                oldItem is FileItem.BackItem && newItem is FileItem.BackItem -> true
                oldItem is FileItem.DirectoryItem && newItem is FileItem.DirectoryItem ->
                    oldItem.directory.absolutePath == newItem.directory.absolutePath
                oldItem is FileItem.RegularFileItem && newItem is FileItem.RegularFileItem ->
                    oldItem.file.absolutePath == newItem.file.absolutePath
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem): Boolean {
            return oldItem == newItem
        }
    }
}
