package com.example.surveyingapp.ui.models

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import android.content.Context
import android.content.Intent
import com.example.surveyingapp.R
import com.example.surveyingapp.databinding.ActivityModelViewerBinding

class ModelViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelViewerBinding

    companion object {
        private const val EXTRA_MODEL_PATH = "model_path"
        private const val EXTRA_MODEL_NAME = "model_name"

        fun newIntent(context: Context, modelPath: String, modelName: String): Intent {
            return Intent(context, ModelViewerActivity::class.java).apply {
                putExtra(EXTRA_MODEL_PATH, modelPath)
                putExtra(EXTRA_MODEL_NAME, modelName)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityModelViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        showPlaceholder()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: "3D Model"
        supportActionBar?.title = modelName
    }

    private fun showPlaceholder() {
        // Hide all model viewing components and show placeholder message
        binding.sceneView.visibility = android.view.View.GONE
        binding.controlsContainer.visibility = android.view.View.GONE
        binding.progressLoading.visibility = android.view.View.GONE

        binding.textError.visibility = android.view.View.VISIBLE
        binding.textError.text = "3D Model viewing is temporarily disabled.\nThis feature will be added back later."
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
