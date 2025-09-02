package com.example.surveyingapp.ui.filepicker

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
    }

    private lateinit var binding: ActivityFilePickerBinding
    private lateinit var adapter: FilePickerAdapter
    private var currentDirectory: File? = null
    private var allowedExtensions: Array<String> = arrayOf()
    private var isInCloudMode = false

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
        allowedExtensions = intent.getStringArrayExtra(EXTRA_FILE_EXTENSIONS) ?: arrayOf(".glb")
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Select File"

        setupToolbar(title)
        setupRecyclerView()
        loadStorageOptions()
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

    private fun loadStorageOptions() {
        binding.textCurrentPath.text = "Select Storage Location"

        val items = mutableListOf<FileItem>()

        // Add cloud storage options if apps are available
        if (isAppInstalled("com.google.android.apps.docs")) {
            items.add(FileItem.GoogleDriveItem)
        }

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

            // Add directories first
            directory.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
                items.add(FileItem.DirectoryItem(dir))
            }

            // Add files with allowed extensions
            directory.listFiles()?.filter { file ->
                file.isFile && allowedExtensions.any { ext ->
                    file.name.lowercase().endsWith(ext.lowercase())
                }
            }?.sortedBy { it.name }?.forEach { file ->
                items.add(FileItem.RegularFileItem(file))
            }

            adapter.submitList(items)

            if (items.isEmpty() || (items.size == 1 && items[0] is FileItem.BackItem)) {
                binding.textEmptyMessage.visibility = View.VISIBLE
                binding.textEmptyMessage.text = "No .glb files found in this directory"
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

    private fun returnSelectedFile(uri: Uri) {
        val resultIntent = Intent().apply {
            data = uri
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}
