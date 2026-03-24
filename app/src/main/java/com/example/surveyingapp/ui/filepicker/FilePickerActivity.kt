package com.example.surveyingapp.ui.filepicker

import android.util.Log
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.surveyingapp.databinding.ActivityFilePickerBinding
import java.io.File

class FilePickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_URI = "file_uri"
        const val EXTRA_FILE_EXTENSIONS = "file_extensions"
        const val EXTRA_TITLE = "title"
        const val EXTRA_FILTER_MODE = "filter_mode"

        const val FILTER_MODE_MODELS = "models"
        const val FILTER_MODE_IMPORT_DATA = "import_data"
        const val FILTER_MODE_CUSTOM = "custom"
        const val FILTER_MODE_FOLDER_SELECT = "folder_select"
    }

    private lateinit var binding: ActivityFilePickerBinding
    private lateinit var adapter: FilePickerAdapter
    private var currentDirectory: File? = null
    private var allowedExtensions: Array<String> = arrayOf()
    private var filterMode: String = FILTER_MODE_CUSTOM

    // SAF system file picker — shows device storage, Google Drive, OneDrive, SD card,
    // USB drives, and any other registered document provider automatically.
    private val safPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) { /* URI may not support persistable perms */ }
                returnSelectedFile(uri)
            }
        } else {
            // User cancelled the SAF picker — nothing to show, so close this activity too.
            if (filterMode != FILTER_MODE_FOLDER_SELECT) {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        filterMode = intent.getStringExtra(EXTRA_FILTER_MODE) ?: FILTER_MODE_CUSTOM
        allowedExtensions = getExtensionsForMode(filterMode)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getDefaultTitleForMode(filterMode)

        setupToolbar(title)
        setupRecyclerView()
        setupSelectFolderButton()

        // Go straight to the system file picker — no intermediate screen needed.
        // If the mode is folder-select we still need the RecyclerView UI, so keep
        // the old flow for that mode only.
        if (filterMode == FILTER_MODE_FOLDER_SELECT) {
            loadStorageOptions()
        } else {
            openSafFilePicker()
        }
    }

    private fun getExtensionsForMode(filterMode: String): Array<String> = when (filterMode) {
        FILTER_MODE_MODELS      -> arrayOf(".glb", ".gltf")
        FILTER_MODE_IMPORT_DATA -> arrayOf(".json", ".csv")
        FILTER_MODE_CUSTOM      -> intent.getStringArrayExtra(EXTRA_FILE_EXTENSIONS) ?: arrayOf()
        else -> arrayOf()
    }

    private fun getDefaultTitleForMode(filterMode: String): String = when (filterMode) {
        FILTER_MODE_MODELS      -> "Select 3D Model"
        FILTER_MODE_IMPORT_DATA -> "Select Data File"
        FILTER_MODE_FOLDER_SELECT -> "Select Folder"
        else -> "Select File"
    }

    private fun getEmptyMessageForMode(): String = when (filterMode) {
        FILTER_MODE_MODELS      -> "No .glb or .gltf model files found in this directory"
        FILTER_MODE_IMPORT_DATA -> "No .json or .csv files found in this directory"
        FILTER_MODE_FOLDER_SELECT -> "No folders found in this directory"
        else -> "No matching files found in this directory"
    }

    private fun setupToolbar(title: String) {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = title
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    private fun setupRecyclerView() {
        adapter = FilePickerAdapter(
            onFileClick = { file ->
                if (file.isDirectory) loadDirectory(file) else selectFile(file)
            },
            onBackClick = { goBack() },
            onBrowseClick = { openSafFilePicker() }
        )
        binding.recyclerFiles.layoutManager = LinearLayoutManager(this)
        binding.recyclerFiles.adapter = adapter
    }

    private fun setupSelectFolderButton() {
        if (filterMode == FILTER_MODE_FOLDER_SELECT) {
            binding.btnSelectFolder.visibility = View.VISIBLE
            binding.btnSelectFolder.setOnClickListener { selectCurrentFolder() }
        } else {
            binding.btnSelectFolder.visibility = View.GONE
        }
    }

    /**
     * Shows a single "Browse files…" entry that opens the SAF system picker.
     * The SAF picker lists all document providers the device has: local storage,
     * Downloads, Google Drive, OneDrive, Dropbox, SD card, USB drives, etc.
     * No separate cloud-app detection is needed.
     */
    private fun loadStorageOptions() {
        binding.textCurrentPath.text = "Choose a file location"
        adapter.submitList(listOf(FileItem.BrowseItem))
        binding.textEmptyMessage.visibility = View.GONE
    }

    /**
     * Opens ACTION_OPEN_DOCUMENT with the correct MIME types.
     * The system file manager handles all storage providers natively.
     */
    private fun openSafFilePicker() {
        val mimeTypes = getMimeTypesForMode(filterMode)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimeTypes.size == 1) mimeTypes[0] else "*/*"
            if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            safPickerLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("FilePicker", "No file manager available", e)
            Toast.makeText(this, "No file manager available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getMimeTypesForMode(mode: String): Array<String> = when (mode) {
        FILTER_MODE_MODELS -> arrayOf(
            "model/gltf-binary",       // .glb  official IANA
            "model/gltf+json",         // .gltf official IANA
            "application/octet-stream" // fallback for unrecognised MIME
        )
        FILTER_MODE_IMPORT_DATA -> arrayOf(
            "application/json",
            "text/csv",
            "text/comma-separated-values"
        )
        FILTER_MODE_CUSTOM -> {
            val exts = intent.getStringArrayExtra(EXTRA_FILE_EXTENSIONS) ?: emptyArray()
            if (exts.isEmpty()) arrayOf("*/*") else exts.map { extToMime(it) }.toTypedArray()
        }
        else -> arrayOf("*/*")
    }

    private fun extToMime(ext: String): String = when (ext.lowercase().trimStart('.')) {
        "json" -> "application/json"
        "csv"  -> "text/csv"
        "glb"  -> "model/gltf-binary"
        "gltf" -> "model/gltf+json"
        "txt"  -> "text/plain"
        "pdf"  -> "application/pdf"
        else   -> "*/*"
    }

    private fun loadDirectory(directory: File) {
        try {
            currentDirectory = directory
            binding.textCurrentPath.text = directory.absolutePath

            val allFiles: Array<File> = directory.listFiles() ?: run {
                Log.w("FilePicker", "listFiles() null: ${directory.absolutePath}")
                Toast.makeText(this, "Cannot read this directory", Toast.LENGTH_SHORT).show()
                goBack()
                return
            }

            val items = mutableListOf<FileItem>()
            if (directory.parent != null) items.add(FileItem.BackItem)

            allFiles.filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedBy { it.name.lowercase() }
                .forEach { items.add(FileItem.DirectoryItem(it)) }

            allFiles.filter { file ->
                file.isFile && !file.name.startsWith(".") && (
                    allowedExtensions.isEmpty() ||
                    allowedExtensions.any { ext -> file.name.lowercase().endsWith(ext.lowercase()) }
                )
            }
                .sortedBy { it.name.lowercase() }
                .forEach { items.add(FileItem.RegularFileItem(it)) }

            adapter.submitList(items)

            if (items.none { it !is FileItem.BackItem }) {
                binding.textEmptyMessage.visibility = View.VISIBLE
                binding.textEmptyMessage.text = getEmptyMessageForMode()
            } else {
                binding.textEmptyMessage.visibility = View.GONE
            }

        } catch (e: SecurityException) {
            Log.e("FilePicker", "Access denied: ${directory.absolutePath}", e)
            Toast.makeText(this, "Access denied to this directory", Toast.LENGTH_SHORT).show()
            goBack()
        }
    }

    private fun goBack() {
        currentDirectory?.parent?.let { loadDirectory(File(it)) }
            ?: loadStorageOptions() // back to root screen if no parent
    }

    private fun selectFile(file: File) {
        setResult(Activity.RESULT_OK, Intent().apply { data = Uri.fromFile(file) })
        finish()
    }

    private fun selectCurrentFolder() {
        currentDirectory?.let {
            setResult(Activity.RESULT_OK, Intent().apply { data = Uri.fromFile(it) })
            finish()
        }
    }

    private fun returnSelectedFile(uri: Uri) {
        setResult(Activity.RESULT_OK, Intent().apply { data = uri })
        finish()
    }
}
