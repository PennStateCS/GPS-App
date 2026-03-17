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
import java.io.File
import java.io.FileOutputStream
import com.example.surveyingapp.data.local.db.AppDatabase
import com.example.surveyingapp.data.repository.impl.ModelRepositoryImpl


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

    //Returns bitmap image as thumbnail 200x200
    private fun createModelThumbnail() {
        // Guard: surface must be valid before PixelCopy can capture it
        val surface = surfaceView.holder?.surface
        if (surface == null || !surface.isValid) {
            Log.w("ModelViewerActivity", "createModelThumbnail: surface not valid yet, skipping")
            thumbnailCallbackRunning = false
            return
        }

        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val handler = surfaceView.handler

        PixelCopy.request(surfaceView, bitmap, { copyResult ->
            if (copyResult == PixelCopy.SUCCESS) {
                // Save the bitmap to internal storage or cache
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val modelBaseName = getModelFileNameWithoutExtension() ?: return@launch

                        // Ensure thumbnails directory exists
                        val thumbsDir = File(filesDir, "thumbnails")
                        if (!thumbsDir.exists()) thumbsDir.mkdirs()

                        // Compose thumbnail filename from model filename
                        val safeBase = modelBaseName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                        val thumbFileName = "${safeBase}_thumb.png"
                        val thumbFile = File(thumbsDir, thumbFileName)

                        FileOutputStream(thumbFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            out.flush()
                        }

                        // Update the model in the DB with thumbnail info
                        try {
                            val db = AppDatabase.getDatabase(applicationContext)
                            val repo = ModelRepositoryImpl(db.modelDao())

                            // Find existing model by fileName matching
                            val modelFileName = intent.getStringExtra(EXTRA_MODEL_PATH)?.substringAfterLast('/')
                            if (modelFileName != null) {
                                val matched = repo.getModelByFileName(modelFileName)

                                if (matched != null) {
                                    val updated = matched.copy(
                                        thumbnailFileName = thumbFileName,
                                        thumbnailFilePath = thumbFile.absolutePath
                                    )
                                    repo.updateModel(updated)
                                } else {
                                    // No match found; nothing to update
                                    Log.w("ModelViewerActivity", "No matching model found to update thumbnail for $modelFileName")
                                }
                            }

                        } catch (e: Exception) {
                            Log.e("ModelViewerActivity", "Failed to update model thumbnail in DB", e)
                        }

                        // mark thumbnailExists on main thread
                        withContext(Dispatchers.Main) {
                            thumbnailExists = true
                        }

                    } catch (e: Exception) {
                        Log.e("ModelViewerActivity", "Failed to save thumbnail", e)
                    }
                }

            } else {
                Log.e("ModelViewerActivity", "Failed to capture thumbnail: $copyResult")
                thumbnailCallbackRunning = false  // allow retry on next frame
            }
        }, handler)

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
        // Do NOT post thumbnailCallback here directly - the frameCallback will
        // schedule it once the model is loaded and the surface is valid.
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
