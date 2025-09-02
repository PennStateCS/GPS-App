package com.example.surveyingapp.ui.models

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.surveyingapp.databinding.FragmentModelsBinding
import com.example.surveyingapp.domain.model.Model
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

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
        setupFab()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = ModelsAdapter(
            onDeleteClick = { model ->
                // Show confirmation dialog before deleting
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Delete Model")
                    .setMessage("Are you sure you want to delete '${model.name}'?")
                    .setPositiveButton("Delete") { _, _ ->
                        viewModel.deleteModel(model)
                        // Delete the physical file
                        try {
                            File(model.filePath).delete()
                        } catch (e: Exception) {
                            // File deletion failed, but continue with database deletion
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
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

        binding.recyclerModels.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerModels.adapter = adapter
    }

    private fun setupFab() {
        binding.fabAddModel.setOnClickListener {
            openFilePicker()
        }
    }

    private fun observeViewModel() {
        // Observe models list
        viewModel.allModels.observe(viewLifecycleOwner) { models ->
            adapter.submitList(models)

            // Show/hide empty state
            if (models.isEmpty()) {
                binding.recyclerModels.visibility = View.GONE
                binding.layoutEmptyState.visibility = View.VISIBLE
            } else {
                binding.recyclerModels.visibility = View.VISIBLE
                binding.layoutEmptyState.visibility = View.GONE
            }
        }

        // Observe loading state
        lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                binding.progressLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
            }
        }

        // Observe status messages
        lifecycleScope.launch {
            viewModel.statusMessage.collect { message ->
                if (message != null) {
                    binding.textStatusMessage.text = message
                    binding.textStatusMessage.visibility = View.VISIBLE
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

                    // Hide status message after 3 seconds
                    binding.textStatusMessage.postDelayed({
                        binding.textStatusMessage.visibility = View.GONE
                        viewModel.clearStatusMessage()
                    }, 3000)
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
            val inputStream = contentResolver.openInputStream(uri)

            if (inputStream != null) {
                // Get file info
                val cursor = contentResolver.query(uri, null, null, null, null)
                var fileName = "model.glb"
                var fileSize = 0L

                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)

                        if (nameIndex >= 0) fileName = it.getString(nameIndex) ?: "model.glb"
                        if (sizeIndex >= 0) fileSize = it.getLong(sizeIndex)
                    }
                }

                // Validate file extension
                if (!fileName.lowercase().endsWith(".glb")) {
                    Toast.makeText(requireContext(), "Please select a .glb file", Toast.LENGTH_LONG).show()
                    return
                }

                // Copy file to app's internal storage
                val modelsDir = File(requireContext().filesDir, "models")
                if (!modelsDir.exists()) {
                    modelsDir.mkdirs()
                }

                var targetFile = File(modelsDir, fileName)
                var counter = 1
                while (targetFile.exists()) {
                    val nameWithoutExt = fileName.substringBeforeLast(".")
                    val ext = fileName.substringAfterLast(".")
                    targetFile = File(modelsDir, "${nameWithoutExt}_${counter}.$ext")
                    counter++
                }

                FileOutputStream(targetFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }

                // Get model name from user
                showAddModelDialog(fileName.substringBeforeLast("."), targetFile.name, targetFile.absolutePath, fileSize)

                inputStream.close()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to import model: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showAddModelDialog(defaultName: String, fileName: String, filePath: String, fileSize: Long) {
        val dialog = AddModelDialogFragment.newInstance(defaultName, fileName, filePath, fileSize) { name, description ->
            viewModel.addModel(name, fileName, filePath, fileSize, description)
        }
        dialog.show(parentFragmentManager, "AddModelDialog")
    }

    private fun showEditModelDialog(model: Model) {
        val dialog = EditModelDialogFragment.newInstance(model) { modelId, name, description ->
            viewModel.editModel(modelId, name, description)
        }
        dialog.show(parentFragmentManager, "EditModelDialog")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
