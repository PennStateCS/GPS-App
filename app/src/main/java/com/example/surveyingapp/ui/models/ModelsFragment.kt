package com.example.surveyingapp.ui.models

import android.app.Activity
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.example.surveyingapp.databinding.FragmentModelsBinding
import com.example.surveyingapp.domain.model.Model
import com.example.surveyingapp.util.GlbGeoreferenceReprojector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class ModelsFragment : Fragment() {

    private var _binding: FragmentModelsBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: ModelsViewModel
    private lateinit var adapter: ModelsAdapter

    // File picker for selecting .glb files
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleSelectedFile(uri)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentModelsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this)[ModelsViewModel::class.java]
        setupRecyclerView()
        setupSwipeRefresh()
        setupFab()
        observeViewModel()
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshModels.setColorSchemeResources(
            android.R.color.holo_purple,
            android.R.color.holo_blue_dark
        )
        binding.swipeRefreshModels.setOnRefreshListener {
            // Force every visible card to rebind so thumbnails are reloaded from disk
            if (adapter.itemCount > 0) {
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
            // Stop the spinner once the rebind is dispatched
            binding.swipeRefreshModels.isRefreshing = false
        }
    }

    private fun setupRecyclerView() {
        adapter = ModelsAdapter(
            onDeleteClick = { model ->
                lifecycleScope.launch {
                    val linkedCount = try {
                        viewModel.linkedCoordinateCount(model.id)
                    } catch (e: Exception) {
                        -1
                    }
                    when {
                        linkedCount < 0 -> {
                            // DB error — fail safe, do not allow delete
                            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle("Error")
                                .setMessage("Could not verify coordinate associations. Please try again.")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                        linkedCount > 0 -> {
                            val noun = if (linkedCount == 1) "coordinate" else "coordinates"
                            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle("Cannot Delete")
                                .setMessage(
                                    "'${model.name}' is used as an icon on $linkedCount $noun. " +
                                    "Remove those associations on the View Coordinates page before deleting."
                                )
                                .setPositiveButton("OK", null)
                                .show()
                        }
                        else -> {
                            // No coordinates reference this model — safe to delete
                            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setTitle("Delete Model")
                                .setMessage("Are you sure you want to delete '${model.name}'?")
                                .setPositiveButton("Delete") { _, _ ->
                                    viewModel.deleteModel(model)
                                    // Delete the physical file
                                    try {
                                        File(model.filePath).delete()
                                    } catch (e: Exception) {
                                        Log.w("ModelsFragment", "File deletion failed for ${model.filePath}: ${e.message}")
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                }
            },
            onEditClick = { model ->
                showEditModelDialog(model)
            },
            onModelClick = { model ->
                // Launch 3D model viewer
                val intent = ModelViewerActivity.newIntent(
                    requireContext(),
                    model.filePath,
                    model.name
                )
                startActivity(intent)
            }
        )

        binding.recyclerModels.layoutManager = GridLayoutManager(requireContext(), calculateSpanCount())
        binding.recyclerModels.adapter = adapter
    }


    private fun calculateSpanCount(): Int {
        val cardCellWidthDp = 197 // 185dp card + 6dp left padding + 6dp right padding
        val dm = resources.displayMetrics
        val availableWidthDp = (dm.widthPixels / dm.density).toInt() - 32 // subtract 16dp padding each side
        return maxOf(2, availableWidthDp / cardCellWidthDp)
    }

    private fun setupFab() {
        binding.fabAddModel.setOnClickListener {
            openFilePicker()
        }
    }

    private fun observeViewModel() {
        // Observe models list
        viewModel.allModels.observe(viewLifecycleOwner) { models ->
            val previousCount = adapter.currentList.size
            adapter.submitList(models) {
                // Scroll to top only when a new model was added (not on edits/thumbnail updates).
                // New models are inserted at index 0 because the DB orders by dateAdded DESC.
                if (models.size > previousCount) {
                    binding.recyclerModels.scrollToPosition(0)
                }
            }

            // Show/hide empty state
            if (models.isEmpty()) {
                binding.swipeRefreshModels.visibility = View.GONE
                binding.layoutEmptyState.visibility = View.VISIBLE
            } else {
                binding.swipeRefreshModels.visibility = View.VISIBLE
                binding.layoutEmptyState.visibility = View.GONE
            }
        }


        // Observe loading state and status messages scoped to the view's lifecycle
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isLoading.collect { isLoading ->
                        binding.progressLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.statusMessage.collect { message ->
                        if (message != null) {
                            binding.textStatusMessage.text = message
                            binding.textStatusMessage.visibility = View.VISIBLE

                            binding.textStatusMessage.postDelayed({
                                if (_binding != null) {
                                    binding.textStatusMessage.visibility = View.GONE
                                    viewModel.clearStatusMessage()
                                }
                            }, 3000)
                        }
                    }
                }
            }
        }
    }

    private fun openFilePicker() {
        // Use custom file picker for guaranteed landscape orientation
        val intent = Intent(requireContext(), com.example.surveyingapp.ui.filepicker.FilePickerActivity::class.java).apply {
            putExtra(com.example.surveyingapp.ui.filepicker.FilePickerActivity.EXTRA_FILTER_MODE, com.example.surveyingapp.ui.filepicker.FilePickerActivity.FILTER_MODE_MODELS)
            putExtra(com.example.surveyingapp.ui.filepicker.FilePickerActivity.EXTRA_TITLE, "Select 3D Model File")
        }
        filePickerLauncher.launch(intent)
    }


    private fun handleSelectedFile(uri: Uri) {
        try {
            val contentResolver = requireContext().contentResolver

            // Resolve display name and file size from content resolver (works for SAF,
            // Google Drive, OneDrive, and local content:// URIs on all API levels)
            var fileName = "model.glb"
            var fileSize = 0L

            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (nameIndex >= 0) fileName = cursor.getString(nameIndex) ?: fileName
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) fileSize = cursor.getLong(sizeIndex)
                }
            }

            // Fall back to extracting name from the URI path if the cursor gave nothing useful
            if (fileName == "model.glb" && uri.path != null) {
                val fromPath = uri.path!!.substringAfterLast('/')
                if (fromPath.isNotBlank()) fileName = fromPath
            }

            Log.d("ModelsFragment", "Selected URI: $uri  fileName: $fileName  size: $fileSize")

            // Validate file extension — accept .glb and .gltf
            val lowerName = fileName.lowercase()
            if (!lowerName.endsWith(".glb") && !lowerName.endsWith(".gltf")) {
                Toast.makeText(requireContext(), "Please select a .glb or .gltf file", Toast.LENGTH_LONG).show()
                return
            }

            // Open the stream — this works for all URI schemes including content:// from SAF
            val inputStream = contentResolver.openInputStream(uri)
                ?: run {
                    Toast.makeText(requireContext(), "Cannot open file", Toast.LENGTH_LONG).show()
                    return
                }

            // If fileSize is still 0, try openAssetFileDescriptor as a fallback
            if (fileSize == 0L) {
                try {
                    contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                        if (afd.length != AssetFileDescriptor.UNKNOWN_LENGTH) fileSize = afd.length
                    }
                } catch (e: Exception) {
                    Log.w("ModelsFragment", "Could not determine file size: ${e.message}")
                }
            }

            viewLifecycleOwner.lifecycleScope.launch {
                val targetFile = withContext(Dispatchers.IO) {
                    copyModelToInternalStorage(uri, fileName, inputStream)
                }
                if (targetFile == null) {
                    Toast.makeText(requireContext(), "Failed to import model file", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // Georeferenced GLBs (WGS84 coordinates baked into vertex positions) are
                // unrenderable as-is — a sub-pixel sliver parked far from the origin. Reproject
                // to a local metric frame off the main thread so the thumbnail, 3D viewer, and AR
                // can all display the model. Ordinary models are detected as non-georeferenced and
                // left untouched.
                val embedded = withContext(Dispatchers.IO) {
                    runCatching { GlbGeoreferenceReprojector.reprojectIfGeoreferenced(targetFile) }.getOrNull()
                }
                // File may have been rewritten by reprojection; use its current size.
                val finalSize = targetFile.length()

                // Prompt user for name/description
                showAddModelDialog(
                    fileName.substringBeforeLast("."),
                    targetFile.name,
                    targetFile.absolutePath,
                    finalSize,
                    embedded
                )
            }

        } catch (e: Exception) {
            Log.e("ModelsFragment", "Failed to import model", e)
            Toast.makeText(requireContext(), "Failed to import model: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyModelToInternalStorage(sourceUri: Uri, fileName: String, inputStream: java.io.InputStream): File? {
        val modelsDir = File(requireContext().filesDir, "models").also { it.mkdirs() }
        val lowerName = fileName.lowercase()

        return if (lowerName.endsWith(".gltf")) {
            val bundleDir = uniqueModelBundleDir(modelsDir, fileName.substringBeforeLast("."))
            val targetGltf = File(bundleDir, fileName)

            inputStream.use { input ->
                FileOutputStream(targetGltf).use { output -> input.copyTo(output) }
            }

            val resourceUris = parseGltfResourceUris(targetGltf)
            val sourceFile = resolveRealFileFromUri(sourceUri)
            if (resourceUris.isNotEmpty() && sourceFile != null) {
                copyGltfResources(sourceFile.parentFile, bundleDir, resourceUris)
            }

            targetGltf
        } else {
            var targetFile = File(modelsDir, fileName)
            var counter = 1
            while (targetFile.exists()) {
                val nameWithoutExt = fileName.substringBeforeLast(".")
                val ext = fileName.substringAfterLast(".")
                targetFile = File(modelsDir, "${nameWithoutExt}_${counter}.$ext")
                counter++
            }

            inputStream.use { input ->
                FileOutputStream(targetFile).use { output -> input.copyTo(output) }
            }
            targetFile
        }
    }

    private fun uniqueModelBundleDir(modelsDir: File, baseName: String): File {
        val safe = baseName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        var dir = File(modelsDir, safe)
        var counter = 1
        while (dir.exists()) {
            dir = File(modelsDir, "${safe}_$counter")
            counter++
        }
        dir.mkdirs()
        return dir
    }

    private fun parseGltfResourceUris(gltfFile: File): Set<String> {
        return try {
            val text = gltfFile.readText()
            Regex("\"uri\"\\s*:\\s*\"([^\"]+)\"")
                .findAll(text)
                .map { it.groupValues[1] }
                .filterNot { it.startsWith("data:") || it.startsWith("http://") || it.startsWith("https://") }
                .map { URLDecoder.decode(it.substringBefore('#').substringBefore('?'), StandardCharsets.UTF_8.name()) }
                .toSet()
        } catch (e: Exception) {
            Log.w("ModelsFragment", "Failed to parse glTF resource list", e)
            emptySet()
        }
    }

    private fun resolveRealFileFromUri(uri: Uri): File? {
        return try {
            requireContext().contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val fdPath = File("/proc/self/fd/${pfd.fd}")
                val canonical = fdPath.canonicalPath
                val resolved = File(canonical)
                if (resolved.exists()) resolved else null
            }
        } catch (e: Exception) {
            Log.w("ModelsFragment", "Could not resolve source file for URI: $uri", e)
            null
        }
    }

    private fun copyGltfResources(sourceRoot: File?, targetRoot: File, resources: Set<String>) {
        if (sourceRoot == null || !sourceRoot.exists()) return

        resources.forEach { relative ->
            try {
                val source = File(sourceRoot, relative).canonicalFile
                if (!source.path.startsWith(sourceRoot.canonicalPath) || !source.exists()) {
                    Log.w("ModelsFragment", "Missing glTF sidecar resource: $relative")
                    return@forEach
                }
                val target = File(targetRoot, relative).canonicalFile
                if (!target.path.startsWith(targetRoot.canonicalPath)) {
                    Log.w("ModelsFragment", "Skipping unsafe glTF resource path: $relative")
                    return@forEach
                }
                target.parentFile?.mkdirs()
                source.inputStream().use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                Log.w("ModelsFragment", "Failed to copy glTF resource: $relative", e)
            }
        }
    }

    private fun showAddModelDialog(
        defaultName: String,
        fileName: String,
        filePath: String,
        fileSize: Long,
        embedded: com.example.surveyingapp.domain.model.EmbeddedModelLocation? = null
    ) {
        val dialog = AddModelDialogFragment.newInstance(defaultName, fileName, filePath, fileSize) { name, description ->
            viewModel.addModel(
                name, fileName, filePath, fileSize, description,
                embeddedLatitude = embedded?.latitude,
                embeddedLongitude = embedded?.longitude,
                embeddedAltitudeM = embedded?.altitudeMeters
            ) { _ ->
                // Open the viewer so the user can rotate the model and tap "Capture Thumbnail"
                //val intent = ModelViewerActivity.newCaptureModeIntent(requireContext(), filePath, name)
                //startActivity(intent)

                ThumbnailCaptureActivity.start(requireContext(), filePath, name, fileName)
            }
        }
        dialog.show(parentFragmentManager, "AddModelDialog")
    }

    private fun showEditModelDialog(model: Model) {
        val dialog = EditModelDialogFragment.newInstance(model) { modelId, name, description ->
            viewModel.editModel(modelId, name, description)
        }
        dialog.show(parentFragmentManager, "EditModelDialog")
    }

    override fun onResume() {
        super.onResume()
        // DiffUtil cannot detect that a thumbnail file was overwritten at the same path.
        // Evict cached thumbnails on resume so the next bind picks up any updated files.
        if (::adapter.isInitialized && adapter.itemCount > 0) {
            adapter.evictAndRefreshThumbnails()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::adapter.isInitialized) adapter.cleanup()
        _binding = null
    }
}
