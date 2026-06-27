package app.surrealar.ui.viewcoordinates

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.surrealar.R
import app.surrealar.data.export.CsvExporter
import app.surrealar.data.export.GeoJsonExporter
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.gnss.model.Fix
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import java.io.File

@AndroidEntryPoint
class ViewCoordinatesFragment : Fragment() {

    companion object {
        private const val TAG = "ViewCoordinatesFragment"
    }

    @Inject
    lateinit var fixSwitchboard: FixSwitchboard

    private var recyclerView: RecyclerView? = null
    private var exportFab: FloatingActionButton? = null
    private var coordinatesAdapter: CoordinatesAdapter? = null
    private var currentFixes: List<Fix> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_view_coordinates, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViews(view)
        setupRecyclerView()
        observeCoordinates()
    }

    private fun setupViews(view: View) {
        recyclerView = view.findViewById(R.id.coordinates_recycler_view)
        exportFab = view.findViewById(R.id.export_fab)

        exportFab?.setOnClickListener {
            showExportDialog()
        }
    }

    private fun setupRecyclerView() {
        coordinatesAdapter = CoordinatesAdapter()
        recyclerView?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = coordinatesAdapter
        }
    }

    private fun observeCoordinates() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                fixSwitchboard.fixes.collect { fix ->
                    currentFixes = currentFixes + fix
                    coordinatesAdapter?.updateFixes(currentFixes.takeLast(100))
                }
            }
        }
    }

    private fun showExportDialog() {
        if (currentFixes.isEmpty()) {
            Toast.makeText(requireContext(), "No coordinates to export", Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf("Export as CSV", "Export as GeoJSON")

        AlertDialog.Builder(requireContext())
            .setTitle("Export Coordinates")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportAsCsv()
                    1 -> exportAsGeoJson()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportAsCsv() {
        lifecycleScope.launch {
            try {
                val csvExporter = CsvExporter()
                val file = csvExporter.exportToCsv(requireContext(), currentFixes)
                shareFile(file)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportAsGeoJson() {
        lifecycleScope.launch {
            try {
                val geoJsonExporter = GeoJsonExporter()
                val file = geoJsonExporter.exportToGeoJson(requireContext(), currentFixes)
                shareFile(file)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun shareFile(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = when (file.extension.lowercase()) {
                    "csv" -> "text/csv"
                    "geojson", "json" -> "application/json"
                    else -> "application/octet-stream"
                }
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Surveying Data Export")
                putExtra(Intent.EXTRA_TEXT, "Exported surveying coordinates from SurReal AR")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "Share coordinates"))

            Toast.makeText(
                requireContext(),
                "File exported: ${file.name}",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Failed to share file: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
