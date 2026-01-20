package com.example.surveyingapp.ui.filepicker

import android.util.Log
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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

        // Filter modes
        const val FILTER_MODE_MODELS = "models"
        const val FILTER_MODE_IMPORT_DATA = "import_data"
        const val FILTER_MODE_CUSTOM = "custom"
        const val FILTER_MODE_FOLDER_SELECT = "folder_select"
    }

    private lateinit var binding: ActivityFilePickerBinding
    private lateinit var adapter: FilePickerAdapter
    private var currentDirectory: File? = null
    private var allowedExtensions: Array<String> = arrayOf()
    private var isInCloudMode = false
    private var filterMode: String = FILTER_MODE_CUSTOM

    // Cloud storage file picker launcher
    private val cloudFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                returnSelectedFile(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get parameters from intent
        filterMode = intent.getStringExtra(EXTRA_FILTER_MODE) ?: FILTER_MODE_CUSTOM
        allowedExtensions = getExtensionsForMode(filterMode)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getDefaultTitleForMode(filterMode)

        setupToolbar(title)
        setupRecyclerView()
        setupSelectFolderButton()
        loadStorageOptions()
    }

    private fun getExtensionsForMode(filterMode: String): Array<String> {
        return when (filterMode) {
            FILTER_MODE_MODELS -> arrayOf(".glb")
            FILTER_MODE_IMPORT_DATA -> arrayOf(".json", ".csv")
            FILTER_MODE_CUSTOM -> intent.getStringArrayExtra(EXTRA_FILE_EXTENSIONS) ?: arrayOf()
            else -> arrayOf()
        }
    }

    private fun getDefaultTitleForMode(filterMode: String): String {
        return when (filterMode) {
            FILTER_MODE_MODELS -> "Select 3D Model"
            FILTER_MODE_IMPORT_DATA -> "Select Data File"
            FILTER_MODE_CUSTOM -> "Select File"
            FILTER_MODE_FOLDER_SELECT -> "Select Folder"
            else -> "Select File"
        }
    }

    private fun getEmptyMessageForMode(): String {
        val filterMode = intent.getStringExtra(EXTRA_FILTER_MODE) ?: FILTER_MODE_CUSTOM
        return when (filterMode) {
            FILTER_MODE_MODELS -> "No .glb model files found in this directory"
            FILTER_MODE_IMPORT_DATA -> "No .json or .csv files found in this directory"
            FILTER_MODE_CUSTOM -> "No matching files found in this directory"
            FILTER_MODE_FOLDER_SELECT -> "No folders found in this directory"
            else -> "No files found in this directory"
        }
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
                if (file.isDirectory) {
                    loadDirectory(file)
                } else {
                    selectFile(file)
                }
            },
            onBackClick = {
                goBack()
            },
            onCloudStorageClick = { provider ->
                openCloudStorageProvider(provider)
            },
            onLocalStorageClick = {
                loadDirectory(Environment.getExternalStorageDirectory())
            }
        )
        binding.recyclerFiles.layoutManager = LinearLayoutManager(this)
        binding.recyclerFiles.adapter = adapter
    }

    private fun setupSelectFolderButton() {
        if (filterMode == FILTER_MODE_FOLDER_SELECT) {
            binding.btnSelectFolder.visibility = View.VISIBLE
            binding.btnSelectFolder.setOnClickListener {
                selectCurrentFolder()
            }
        } else {
            binding.btnSelectFolder.visibility = View.GONE
        }
    }

    private fun loadStorageOptions() {
        binding.textCurrentPath.text = "Select Storage Location"

        val items = mutableListOf<FileItem>()

        // Add cloud storage options if apps are available
        if (isAppInstalled("com.google.android.apps.docs")) {
            items.add(FileItem.GoogleDriveItem)
        }

        // Is the OneDrive app really required? We can just ask the OneDrive API for files
        // and fetch from there. We will need a registered app through Entra though.
        if (isAppInstalled("com.microsoft.skydrive")) {
            items.add(FileItem.OneDriveItem)
        }

        // Always add local storage option
        items.add(FileItem.LocalStorageItem)

        adapter.submitList(items)
        binding.textEmptyMessage.visibility = View.GONE
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun openCloudStorageProvider(provider: String) {
        val intent = when (provider) {
            "google_drive" -> Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                setPackage("com.google.android.apps.docs")
                putExtra(Intent.EXTRA_MIME_TYPES, allowedExtensions)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            "onedrive" -> Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                setPackage("com.microsoft.skydrive")
                putExtra(Intent.EXTRA_MIME_TYPES, allowedExtensions)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            else -> {
                Toast.makeText(this, "Unknown provider: $provider", Toast.LENGTH_SHORT).show()
                return
            }
        }

        try {
            cloudFilePickerLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to open $provider: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadDirectory(directory: File) {
        try {
            currentDirectory = directory
            binding.textCurrentPath.text = directory.absolutePath

            val items = mutableListOf<FileItem>()

            // Add back button if not in root
            if (directory.parent != null) {
                items.add(FileItem.BackItem)
            }

            // debug: print directory contents to log on for loop
            for (thing in directory.listFiles() ?: arrayOf()) {
                Log.d("FilePicker", "Found item: ${thing.name} (dir: ${thing.isDirectory})")
            }

            // Add directories first (excluding hidden folders that start with .)
            directory.listFiles()?.filter {
                it.isDirectory && !it.name.startsWith(".")
            }?.sortedBy { it.name }?.forEach { dir ->
                items.add(FileItem.DirectoryItem(dir))
            }

            // Add files with allowed extensions (excluding hidden files that start with .)
            directory.listFiles()?.filter { file ->
                file.isFile &&
                        !file.name.startsWith(".") &&
                        allowedExtensions.any { ext ->
                            file.name.lowercase().endsWith(ext.lowercase())
                        }
            }?.sortedBy { it.name }?.forEach { file ->
                items.add(FileItem.RegularFileItem(file))
            }

            adapter.submitList(items)

            if (items.isEmpty() || (items.size == 1 && items[0] is FileItem.BackItem)) {
                binding.textEmptyMessage.visibility = View.VISIBLE
                binding.textEmptyMessage.text = getEmptyMessageForMode()
            } else {
                binding.textEmptyMessage.visibility = View.GONE
            }

        } catch (e: SecurityException) {
            Toast.makeText(this, "Access denied to this directory", Toast.LENGTH_SHORT).show()
            goBack()
        }
    }

    private fun goBack() {
        currentDirectory?.parent?.let { parent ->
            loadDirectory(File(parent))
        }
    }

    private fun selectFile(file: File) {
        val resultIntent = Intent().apply {
            data = Uri.fromFile(file)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun selectCurrentFolder() {
        currentDirectory?.let { directory ->
            val resultIntent = Intent().apply {
                data = Uri.fromFile(directory)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun returnSelectedFile(uri: Uri) {
        val resultIntent = Intent().apply {
            data = uri
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}
