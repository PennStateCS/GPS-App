package app.surrealar.ui.models

import app.surrealar.util.diagnostics.LogPrivacy
import android.app.Activity
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Bundle
import app.surrealar.util.DevLog
import app.surrealar.util.DiagnosticsLogger
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import app.surrealar.data.files.ModelFileCleaner
import androidx.lifecycle.Lifecycle
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import app.surrealar.databinding.FragmentModelsBinding
import app.surrealar.domain.model.Model
import app.surrealar.util.GlbGeoreferenceReprojector
import io.github.pennstatecs.geokit.models.gltf.GltfSidecars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Imported-models management screen: lists models from `ModelsViewModel` and offers add/edit/delete
 * and 3D preview. Operates on model metadata and files via the repositories.
 */
@AndroidEntryPoint
class ModelsFragment : Fragment() {

    private var _binding: FragmentModelsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ModelsViewModel by viewModels()
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
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        -1   // the ViewModel already logs query failures; -1 = fail safe below
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
                                    // ViewModel deletes the DB row + thumbnail, then the model
                                    // file/bundle once the row is gone (via ModelFileCleaner).
                                    viewModel.deleteModel(model)
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
                    model.name,
                    modelId = model.id
                )
                startActivity(intent)
            },
            onReadinessClick = { model ->
                // Readiness chip: open the viewer with the checklist already up
                startActivity(ModelViewerActivity.newIntent(
                    requireContext(), model.filePath, model.name,
                    modelId = model.id, openReadiness = true
                ))
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
                // Runs after async diffing: the view may already be gone.
                if (models.size > previousCount) {
                    _binding?.recyclerModels?.scrollToPosition(0)
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


        // "N linked" badges follow the coordinates table
        viewModel.linkedCounts.observe(viewLifecycleOwner) { counts ->
            adapter.setLinkedCounts(counts)
        }

        // Readiness chips follow both the models and the coordinates tables
        viewModel.readiness.observe(viewLifecycleOwner) { verdicts ->
            adapter.setReadiness(verdicts)
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
        val intent = Intent(requireContext(), app.surrealar.ui.filepicker.FilePickerActivity::class.java).apply {
            putExtra(app.surrealar.ui.filepicker.FilePickerActivity.EXTRA_FILTER_MODE, app.surrealar.ui.filepicker.FilePickerActivity.FILTER_MODE_MODELS)
            putExtra(app.surrealar.ui.filepicker.FilePickerActivity.EXTRA_TITLE, "Select 3D Model File")
        }
        filePickerLauncher.launch(intent)
    }


    private fun handleSelectedFile(uri: Uri) {
        importOp = null
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
            // The provider's name is used as a file name in models/: never as a path.
            val reportedName = fileName
            fileName = ModelFileIo.safeImportName(fileName)
            // One op= id for every line of this import (diagnostics only).
            val op = app.surrealar.util.diagnostics.DiagIds.next(app.surrealar.util.diagnostics.DiagIds.OP)
            importOp = op
            if (fileName != reportedName) {
                DiagnosticsLogger.w("MODEL", "Model import name sanitized: path characters removed op=$op file=${LogPrivacy.file(fileName)}")
            }

            DevLog.d("ModelsFragment") { "Selected URI: $uri  fileName: $fileName  size: $fileSize" }
            DiagnosticsLogger.i("MODEL",
                "Model import started op=$op file=${LogPrivacy.file(fileName)} sizeBytes=$fileSize uriScheme=${uri.scheme}")

            // Validate file extension — accept .glb and .gltf
            val lowerName = fileName.lowercase()
            if (!lowerName.endsWith(".glb") && !lowerName.endsWith(".gltf")) {
                DiagnosticsLogger.w("MODEL", "Model import rejected: unsupported file type op=$op file=${LogPrivacy.file(fileName)} expected=[glb,gltf]")
                Toast.makeText(requireContext(), "Please select a .glb or .gltf file", Toast.LENGTH_LONG).show()
                return
            }

            // Open the stream — this works for all URI schemes including content:// from SAF
            val inputStream = contentResolver.openInputStream(uri)
                ?: run {
                    DiagnosticsLogger.w("MODEL", "Model import failed: cannot open input stream op=$op file=${LogPrivacy.file(fileName)}")
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
                    // Expected for some providers: the copied size is checked after the copy instead.
                    DevLog.d("ModelsFragment") { "Could not determine file size: ${e.javaClass.simpleName}" }
                }
            }

            // Very large models run tablets out of memory when loaded — refuse them up front.
            if (ModelFileIo.isTooLarge(fileSize)) {
                runCatching { inputStream.close() }
                rejectTooLarge(fileName, fileSize, op)
                return
            }

            val filesDir = requireContext().filesDir   // read now: the copy runs off the main thread
            viewLifecycleOwner.lifecycleScope.launch {
                // The try/catch around launch never saw failures inside it: a stream dropped by a
                // cloud provider or a full disk crashed the app. Fail into the "target null" path.
                val targetFile = withContext(Dispatchers.IO) {
                    runCatching { copyModelToInternalStorage(uri, fileName, inputStream, filesDir) }
                        .onFailure { DiagnosticsLogger.e("MODEL", "Model file copy failed op=$op file=${LogPrivacy.file(fileName)}", it) }
                        .getOrNull()
                }
                if (targetFile == null) {
                    // The copy failure was logged (with the exception) by onFailure above.
                    Toast.makeText(requireContext(), "Failed to import model file", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // The provider may not have reported a size; check what was actually copied
                // (the whole bundle for a .gltf import).
                val modelsRoot = File(filesDir, ModelFileCleaner.MODELS_DIR_NAME)
                val copiedSize = withContext(Dispatchers.IO) {
                    val bundle = targetFile.parentFile
                    if (bundle != null && bundle.canonicalFile != modelsRoot.canonicalFile) {
                        bundle.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    } else {
                        targetFile.length()
                    }
                }
                if (ModelFileIo.isTooLarge(copiedSize)) {
                    withContext(Dispatchers.IO) {
                        ModelFileCleaner.deleteModelFile(targetFile.absolutePath, modelsRoot)
                    }
                    rejectTooLarge(fileName, copiedSize, op)
                    return@launch
                }

                // Georeferenced GLBs (WGS84 coordinates baked into vertex positions) are
                // unrenderable as-is — a sub-pixel sliver parked far from the origin. Reproject
                // to a local metric frame off the main thread so the thumbnail, 3D viewer, and AR
                // can all display the model. Ordinary models are detected as non-georeferenced and
                // left untouched.
                val embedded = withContext(Dispatchers.IO) {
                    runCatching { GlbGeoreferenceReprojector.reprojectIfGeoreferenced(targetFile) }
                        .onFailure { DiagnosticsLogger.w("MODEL", "Model reprojection failed: model kept as imported " +
                            "op=$op file=${LogPrivacy.file(targetFile.name)}", it) }
                        .getOrNull()
                }
                // File may have been rewritten by reprojection; use its current size.
                val finalSize = targetFile.length()
                DiagnosticsLogger.i("MODEL", "Model file copied op=$op file=${LogPrivacy.file(targetFile.name)} " +
                    "sizeBytes=$finalSize dir=models georeferenced=${embedded != null}")

                // Prompt user for name/description — once the screen is started again (the copy and
                // reprojection can take seconds; showing a dialog after the state is saved throws).
                viewLifecycleOwner.withStarted { showAddModelDialog(
                    fileName.substringBeforeLast("."),
                    targetFile.name,
                    targetFile.absolutePath,
                    finalSize,
                    embedded
                ) }
            }

        } catch (e: Exception) {
            DiagnosticsLogger.e("MODEL", "Model import failed" + (importOp?.let { " op=$it" } ?: ""), e)
            Toast.makeText(requireContext(), "Failed to import model: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** op= of the import being handled (diagnostics only), for the catch-all failure line. */
    private var importOp: String? = null

    private fun rejectTooLarge(fileName: String, sizeBytes: Long, op: String) {
        val sizeMb = String.format(java.util.Locale.US, "%.0f", sizeBytes / (1024.0 * 1024.0))
        DiagnosticsLogger.w("MODEL", "Model import rejected: file too large op=$op file=${LogPrivacy.file(fileName)} " +
            "sizeBytes=$sizeBytes limitBytes=${ModelFileIo.MAX_IMPORT_BYTES}")
        val ctx = context ?: return
        Toast.makeText(
            ctx,
            "This model is too large ($sizeMb MB). The limit is ${ModelFileIo.maxImportLabel()}.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun copyModelToInternalStorage(sourceUri: Uri, fileName: String, inputStream: java.io.InputStream, filesDir: File): File? {
        val modelsDir = File(filesDir, "models").also { it.mkdirs() }
        // Second guard behind safeImportName: refuse anything that would land outside models/.
        require(ModelFileIo.isInside(modelsDir, File(modelsDir, fileName))) { "Unsafe model file name" }
        val lowerName = fileName.lowercase()

        return if (lowerName.endsWith(".gltf")) {
            val bundleDir = uniqueModelBundleDir(modelsDir, fileName.substringBeforeLast("."))
            val targetGltf = File(bundleDir, fileName)

            try {
                inputStream.use { input ->
                    FileOutputStream(targetGltf).use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                bundleDir.deleteRecursively()   // no half-copied model left behind
                throw e
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

            try {
                inputStream.use { input ->
                    FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                targetFile.delete()             // no half-copied model left behind
                throw e
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
            GltfSidecars.resourcePaths(gltfFile.readText()).getOrThrow()
        } catch (e: Exception) {
            DiagnosticsLogger.w("MODEL", "Model glTF resource listing failed: sidecar files not copied file=${LogPrivacy.file(gltfFile.name)}", e)
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
            // glTF sidecar resources cannot be copied without the source dir — surface in export.
            DiagnosticsLogger.w("MODEL", "Model glTF sidecar URI resolution failed", e)
            null
        }
    }

    private fun copyGltfResources(sourceRoot: File?, targetRoot: File, resources: Set<String>) {
        if (sourceRoot == null || !sourceRoot.exists()) return

        // Per resource: logcat detail only; one summary line for the export.
        val report = GltfSidecars.copy(sourceRoot, targetRoot, resources)
        report.missing.forEach { relative -> DevLog.d("ModelsFragment") { "Missing glTF sidecar resource: $relative" } }
        report.unsafe.forEach { relative -> DevLog.d("ModelsFragment") { "Skipping unsafe glTF resource path: $relative" } }
        report.failed.forEach { relative -> DevLog.d("ModelsFragment") { "Failed to copy glTF resource: $relative" } }
        if (report.problems > 0) {
            DiagnosticsLogger.w("MODEL", "Model glTF sidecar copy incomplete: model may render without some textures " +
                "total=${resources.size} missing=${report.missing.size} unsafe=${report.unsafe.size} failed=${report.failed.size}",
                report.firstError)
        }
    }

    private fun showAddModelDialog(
        defaultName: String,
        fileName: String,
        filePath: String,
        fileSize: Long,
        embedded: app.surrealar.domain.model.EmbeddedModelLocation? = null
    ) {
        val dialog = AddModelDialogFragment.newInstance(defaultName, fileName, filePath, fileSize) { name, description ->
            viewModel.addModel(
                name, fileName, filePath, fileSize, description,
                embeddedLatitude = embedded?.latitude,
                embeddedLongitude = embedded?.longitude,
                embeddedAltitudeM = embedded?.altitudeMeters
            ) { modelId ->
                // Log readiness for the report; warn now if the model will likely fail in AR.
                val appCtx = context?.applicationContext
                viewModel.checkReadinessAfterImport(modelId) { modelName ->
                    appCtx?.let {
                        Toast.makeText(it, "‘$modelName’ may not work in AR — open it to see why",
                            Toast.LENGTH_LONG).show()
                    }
                }

                // Open the viewer so the user can rotate the model and tap "Capture Thumbnail"
                //val intent = ModelViewerActivity.newCaptureModeIntent(requireContext(), filePath, name)
                //startActivity(intent)

                // The add can finish after the user left this screen.
                val ctx = context ?: return@addModel
                ThumbnailCaptureActivity.start(ctx, filePath, name, fileName)
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
