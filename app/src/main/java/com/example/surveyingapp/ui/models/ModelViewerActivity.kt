package com.example.surveyingapp.ui.models

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.view.SurfaceView
import com.example.surveyingapp.databinding.ActivityModelViewerBinding
import com.google.android.filament.Skybox
import com.google.android.filament.utils.Utils
import com.google.android.filament.utils.ModelViewer
import java.nio.ByteBuffer
import android.view.Choreographer
import android.view.PixelCopy
import androidx.lifecycle.lifecycleScope
import com.google.android.filament.utils.KTX1Loader
import java.nio.Buffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class ModelViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelViewerBinding
    private lateinit var surfaceView: SurfaceView
    private lateinit var newModelViewer: ModelViewer
    private lateinit var choreographer: Choreographer

    private var thumbnailExists = false
    private var thumbnailCallbackRunning = false
    private var modelReadyForThumbnail = false

    private var autoRotate = false

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

        binding.btnResetRotation.setOnClickListener {
            clickedResetView()
        }

        binding.btnAutoRotate.setOnClickListener {
            clickedAutoRotate()
        }

        thumbnailExists = modelHasThumbnail()

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
        val indirectLighting = KTX1Loader.createIndirectLight(newModelViewer.engine, indirectLightFile)
        indirectLighting.indirectLight?.intensity = 50_000f
        newModelViewer.scene.indirectLight = indirectLighting.indirectLight

        lifecycleScope.launch {

            binding.progressLoading.visibility = android.view.View.VISIBLE

            // load the glb file
            val modelBuffer = withContext(Dispatchers.IO) {
                loadGlb()   // reads file into ByteBuffer
            }

            if (modelBuffer == null) {
                //showPlaceholder()
                return@launch
            }


            newModelViewer.loadModelGlb(modelBuffer)
            newModelViewer.transformToUnitCube()
            modelReadyForThumbnail = true
            binding.progressLoading.visibility = android.view.View.GONE

        }
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

    private fun clickedResetView() {
        Log.d("ModelViewerActivity", "Resetting model view")

        // find a way to reset the model using newModelEngine.scene.transformManager
        // and quaternions
    }

    private fun clickedAutoRotate() {
        autoRotate = !autoRotate

        Log.d("ModelViewerActivity", "autoRotate set to $autoRotate")

        // implement auto-rotate functionality, maybe through frameCallback?
    }

    private fun getModelFileNameWithoutExtension(): String? {
        return intent.getStringExtra(EXTRA_MODEL_PATH)
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
    }

    private fun modelHasThumbnail(): Boolean {
        // check if the model has a thumbnail under the model_images folder in assets
        // currently stored as the same name as the model file, but as .png

        val fileName = getModelFileNameWithoutExtension()

        if (assets.list("model_images")?.contains("${fileName}.png") == true) {
            return true
        }

        return false
    }

    private fun createModelThumbnail() {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        PixelCopy.request(surfaceView, bitmap, { copyResult ->
            if (copyResult == PixelCopy.SUCCESS) {
                // Save the bitmap to internal storage or cache

            } else {
                Log.e("ModelViewerActivity", "Failed to capture thumbnail: $copyResult")
            }
        }, surfaceView.handler)

    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            // autoRotate code should run here

            newModelViewer.render(frameTimeNanos)

            // Run for one frame after model is loaded
            if (modelReadyForThumbnail && !thumbnailExists && !thumbnailCallbackRunning) {
                thumbnailCallbackRunning = true
                choreographer.postFrameCallback(thumbnailCallback)
            }

            choreographer.postFrameCallback(this)

        }
    }

    private val thumbnailCallback = Choreographer.FrameCallback {
        createModelThumbnail()
        thumbnailExists = true
        thumbnailCallbackRunning = false
    }

    override fun onResume() {
        super.onResume()
        choreographer = Choreographer.getInstance()
        choreographer.postFrameCallback(frameCallback)
        choreographer.postFrameCallback(thumbnailCallback)
    }

    override fun onPause() {
        super.onPause()
        choreographer.removeFrameCallback(frameCallback)
        choreographer.removeFrameCallback(thumbnailCallback)
    }

    // TODO: clean up after ourselves
    override fun onDestroy() {
        super.onDestroy()
        choreographer.removeFrameCallback(frameCallback)
        choreographer.removeFrameCallback(thumbnailCallback)

    }

}
