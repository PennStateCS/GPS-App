package com.example.surveyingapp.ui.filepicker

import android.util.Log
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
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
            // Most pickers put the URI in data; some API 24 devices put it in clipData
            val uri: Uri? = result.data?.data
                ?: result.data?.clipData?.getItemAt(0)?.uri

            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    // URI from ACTION_GET_CONTENT fallback doesn't support persistable perms — ignore
                    Log.d("FilePicker", "URI does not support persistable permission: $e")
                }
                returnSelectedFile(uri)
            } else {
                // No URI despite OK — treat as a cancel and return to our own landing screen so the
                // user keeps an obvious Browse/Cancel choice instead of being dropped or forced.
                if (filterMode != FILTER_MODE_FOLDER_SELECT) showLandingScreen()
            }
        } else {
            // Normal SAF cancellation (not an error). Return to the app's landing screen rather than
            // finishing, so the user can Browse again or Cancel/back to Models on their own terms.
            if (filterMode != FILTER_MODE_FOLDER_SELECT) showLandingScreen()
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
        setupCancelButton()

        // Folder-select keeps its directory-browsing UI. For normal file selection we now show an
        // app-controlled landing screen first (Browse + Cancel) — instead of auto-launching SAF —
        // so the user can clearly back out and return to the caller without picking a file.
        if (filterMode == FILTER_MODE_FOLDER_SELECT) {
            loadStorageOptions()
        } else {
            showLandingScreen()
        }

        // System back from the landing screen cancels cleanly and returns to the caller.
        onBackPressedDispatcher.addCallback(this) {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    private fun setupCancelButton() {
        binding.btnCancel.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    /**
     * App-controlled landing screen for normal (non-folder) file selection. Shows mode-specific
     * helper text plus a "Browse files…" row (opens the SAF picker) and an explicit Cancel button;
     * the toolbar back arrow and system back also cancel. Shown first, and again whenever the user
     * cancels SAF, so they are never forced to pick a file.
     */
    private fun showLandingScreen() {
        binding.textCurrentPath.text = landingInstructionForMode()
        binding.textPickerHelper.apply {
            text = "You can cancel and return without selecting a file."
            visibility = View.VISIBLE
        }
        binding.btnCancel.visibility = View.VISIBLE
        binding.textEmptyMessage.visibility = View.GONE
        adapter.submitList(listOf(FileItem.BrowseItem))
    }

    private fun landingInstructionForMode(): String = when (filterMode) {
        FILTER_MODE_MODELS ->
            "Choose a .glb or .gltf model from your device, cloud storage, SD card, or USB drive."
        FILTER_MODE_IMPORT_DATA ->
            "Choose a .json or .csv file from your device, cloud storage, SD card, or USB drive."
        else ->
            "Choose a file from your device, cloud storage, SD card, or USB drive."
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
     * Opens the system file picker using ACTION_OPEN_DOCUMENT (preferred — grants
     * persistent URI access) with a fallback to ACTION_GET_CONTENT for devices that
     * don't have a SAF-compatible file manager (can happen on API 24/25).
     */
    private fun openSafFilePicker() {
        val mimeTypes = getMimeTypesForMode(filterMode)

        // Build the intent — always use */* as the primary type when multiple MIME types
        // are needed; EXTRA_MIME_TYPES does the actual filtering. Using a specific type
        // as primary AND EXTRA_MIME_TYPES causes some API 24 file managers to show nothing.
        fun buildIntent(action: String) = Intent(action).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // Try ACTION_OPEN_DOCUMENT first (API 19+, but not all devices have a provider)
        val openDoc = buildIntent(Intent.ACTION_OPEN_DOCUMENT)
        val getContent = buildIntent(Intent.ACTION_GET_CONTENT)

        try {
            safPickerLauncher.launch(openDoc)
        } catch (e: android.content.ActivityNotFoundException) {
            // No document provider installed — fall back to GET_CONTENT which is handled
            // by the browser/gallery on older devices
            Log.w("FilePicker", "No ACTION_OPEN_DOCUMENT handler, falling back to GET_CONTENT", e)
            try {
                safPickerLauncher.launch(getContent)
            } catch (e2: android.content.ActivityNotFoundException) {
                Log.e("FilePicker", "No file picker available at all", e2)
                Toast.makeText(this, "No file manager found on this device", Toast.LENGTH_LONG).show()
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
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
        val uri = fileToUri(file)
        val resultIntent = Intent().apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun selectCurrentFolder() {
        currentDirectory?.let { dir ->
            val uri = fileToUri(dir)
            val resultIntent = Intent().apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    /**
     * Convert a File to a URI safe to pass across process boundaries on all API levels.
     * Uses FileProvider (content:// URI) on API 24+ to avoid FileUriExposedException.
     */
    private fun fileToUri(file: File): Uri {
        return try {
            FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            // Fall back to file:// if FileProvider is not configured for this path
            Log.w("FilePicker", "FileProvider failed, using file URI: ${e.message}")
            Uri.fromFile(file)
        }
    }

    private fun returnSelectedFile(uri: Uri) {
        setResult(Activity.RESULT_OK, Intent().apply { data = uri })
        finish()
    }
}
