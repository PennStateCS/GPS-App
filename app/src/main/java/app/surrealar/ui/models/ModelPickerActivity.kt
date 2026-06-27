package app.surrealar.ui.models

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import app.surrealar.databinding.ActivityModelPickerBinding
import app.surrealar.domain.repository.ModelRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import app.surrealar.domain.model.Model
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Picker screen for choosing a model to link to a coordinate; returns the selected model id.
 */
@AndroidEntryPoint
class ModelPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_SELECTED_MODEL_ID = "selected_model_id"
        const val EXTRA_SELECTED_MODEL_NAME = "selected_model_name"
        const val EXTRA_SELECTED_THUMBNAIL_PATH = "selected_thumbnail_path"

        fun newIntent(context: Context, title: String? = null): Intent =
            Intent(context, ModelPickerActivity::class.java).apply {
                if (title != null) putExtra(EXTRA_TITLE, title)
            }
    }

    private lateinit var binding: ActivityModelPickerBinding
    private lateinit var adapter: ModelPickerAdapter

    @Inject lateinit var repository: ModelRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Select a Model"
        setupToolbar(title)
        setupRecyclerView()
        setupSwipeRefresh()
        observeModels()
    }

    private fun setupToolbar(title: String) {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = title
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun setupRecyclerView() {
        adapter = ModelPickerAdapter { model -> returnSelectedModel(model) }
        binding.recyclerModels.layoutManager = GridLayoutManager(this, calculateSpanCount())
        binding.recyclerModels.adapter = adapter
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshModels.setColorSchemeResources(
            android.R.color.holo_purple,
            android.R.color.holo_blue_dark
        )
        binding.swipeRefreshModels.setOnRefreshListener {
            if (adapter.itemCount > 0) {
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }

            binding.swipeRefreshModels.isRefreshing = false
        }
    }

    private fun observeModels() {
        lifecycleScope.launch {
            try {
                repository.getAllModels().collect { models ->
                    adapter.submitList(models)
                    val empty = models.isEmpty()
                    binding.swipeRefreshModels.visibility = if (empty) View.GONE else View.VISIBLE
                    binding.layoutEmptyState.visibility = if (empty) View.VISIBLE else View.GONE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ModelPicker", "Failed to load models", e)
            }
        }
    }

    private fun returnSelectedModel(model: Model) {
        val data = Intent().apply {
            putExtra(EXTRA_SELECTED_MODEL_ID, model.id)
            putExtra(EXTRA_SELECTED_MODEL_NAME, model.name)
            putExtra(EXTRA_SELECTED_THUMBNAIL_PATH, model.thumbnailFilePath)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    private fun calculateSpanCount(): Int {
        val cardCellWidthDp = 197 // 185dp card + 6dp left padding + 6dp right padding
        val dm = resources.displayMetrics
        val availableWidthDp = (dm.widthPixels / dm.density).toInt() - 32 // 16dp padding each side
        return maxOf(2, availableWidthDp / cardCellWidthDp)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::adapter.isInitialized) adapter.cleanup()
    }
}
