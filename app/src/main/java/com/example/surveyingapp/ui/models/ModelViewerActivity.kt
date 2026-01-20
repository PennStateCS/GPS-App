package com.example.surveyingapp.ui.models

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.SurfaceView
import com.example.surveyingapp.R
import com.example.surveyingapp.databinding.ActivityModelViewerBinding
import com.google.android.filament.Skybox
import com.google.android.filament.utils.Utils
import com.google.android.filament.utils.ModelViewer
import java.nio.ByteBuffer
import android.view.Choreographer
import com.google.android.filament.IndirectLight
import com.google.android.filament.utils.KTX1Loader
import java.nio.Buffer

class ModelViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelViewerBinding
    private lateinit var surfaceView: SurfaceView
    private lateinit var newModelViewer: ModelViewer
    private lateinit var choreographer: Choreographer

    companion object {
        private const val EXTRA_MODEL_PATH = "model_path"
        private const val EXTRA_MODEL_NAME = "model_name"

        fun newIntent(context: Context, modelPath: String, modelName: String): Intent {
            Utils.init()

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

        setupModelViewer()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: "3D Model"
        supportActionBar?.title = modelName
    }

    private fun setupModelViewer() {
        surfaceView = binding.modelSurface
        newModelViewer = ModelViewer(surfaceView)
        surfaceView.setOnTouchListener(newModelViewer)

        // create a skybox
        newModelViewer.scene.skybox = Skybox.Builder().build(newModelViewer.engine)
        newModelViewer.scene.skybox?.setColor(1f,1f,1f,1f)


        val indirectLightFile = loadIndirectLightKtx("ktx/test.ktx") as Buffer
        Log.d("ModelViewerActivity", "Indirect light file size: ${indirectLightFile.remaining()} bytes")
        val indirectLighting = KTX1Loader.createIndirectLight(newModelViewer.engine, indirectLightFile)
        indirectLighting.indirectLight?.intensity = 50_000f
        newModelViewer.scene.indirectLight = indirectLighting.indirectLight

        // load the glb file
        val model = loadGlb()

        if (model == null) {
            showPlaceholder()
            return
        }

        newModelViewer.loadModelGlb(model)
        newModelViewer.transformToUnitCube()
    }

    private fun loadGlb(): ByteBuffer? {
        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: return null

        return loadFile(modelPath)
    }

    private fun loadFile(filePath: String): ByteBuffer? {
        val file = java.io.File(filePath)

        if (file.exists()) {
            val buffer = ByteBuffer.allocateDirect(file.length().toInt())
            file.inputStream().use { input ->
                val bytes = input.readBytes()
                buffer.put(bytes)
                buffer.rewind()
            }

            return buffer
        }

        return null
    }

    private fun loadIndirectLightKtx(filePath: String): ByteBuffer? {
        return loadAsset(filePath)
    }

    private fun loadAsset(filePath: String): ByteBuffer? {
        val asset = assets.open(filePath)

        asset.use { input ->
            val bytes = input.readBytes()
            val buffer = ByteBuffer.allocateDirect(bytes.size)
            buffer.put(bytes)
            buffer.rewind()
            return buffer
        }
    }

    private fun showPlaceholder() {
        // Hide all model viewing components and show placeholder message
        binding.placeholderView.visibility = android.view.View.VISIBLE
        binding.controlsContainer.visibility = android.view.View.GONE
        binding.progressLoading.visibility = android.view.View.GONE

        binding.textError.visibility = android.view.View.VISIBLE
        binding.textError.text = intent.getStringExtra(EXTRA_MODEL_PATH) ?: "No path found?"
        //binding.textError.text = "3D Model viewing is temporarily disabled.\nThis feature will be added back later."
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

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            newModelViewer.render(frameTimeNanos)
            choreographer.postFrameCallback(this)
        }
    }

    override fun onResume() {
        super.onResume()
        choreographer = Choreographer.getInstance()
        choreographer.postFrameCallback(frameCallback)
    }

    override fun onPause() {
        super.onPause()
        choreographer.removeFrameCallback(frameCallback)
    }

    // TODO: clean up after ourselves
    override fun onDestroy() {
        super.onDestroy()
        choreographer.removeFrameCallback(frameCallback)

    }

}
