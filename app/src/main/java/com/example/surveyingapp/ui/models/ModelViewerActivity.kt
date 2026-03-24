package com.example.surveyingapp.ui.models

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MenuItem
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.surveyingapp.data.local.db.AppDatabase
import com.example.surveyingapp.data.repository.impl.ModelRepositoryImpl
import com.example.surveyingapp.databinding.ActivityModelViewerBinding
import com.google.android.filament.*
import com.google.android.filament.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class ModelViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelViewerBinding
    private lateinit var surfaceView: SurfaceView
    private lateinit var newModelViewer: ModelViewer
    private lateinit var choreographer: android.view.Choreographer

    private var thumbnailExists = false
    private var thumbnailCaptureScheduled = false
    private var modelReadyForThumbnail = false
    private var framesAfterLoad = 0
    private var captureOnly = false

    private var autoRotate = false

    companion object {
        private const val EXTRA_MODEL_PATH = "model_path"
        private const val EXTRA_MODEL_NAME = "model_name"
        /** When true the activity renders the model, captures the thumbnail, then finishes itself. */
        const val EXTRA_CAPTURE_ONLY = "capture_only"

        // Number of rendered frames to wait after model load before capturing thumbnail.
        private const val FRAMES_TO_SETTLE = 60

        // Capture size for thumbnails.
        private const val THUMBNAIL_SIZE = 256


        // How many PixelCopy attempts before giving up.
        private const val CAPTURE_MAX_ATTEMPTS = 20

        // Delay between attempts (ms).
        private const val CAPTURE_RETRY_DELAY_MS = 250L

        fun newIntent(context: Context, modelPath: String, modelName: String): Intent {
            Utils.init()
            return Intent(context, ModelViewerActivity::class.java).apply {
                putExtra(EXTRA_MODEL_PATH, modelPath)
                putExtra(EXTRA_MODEL_NAME, modelName)
            }
        }

        /** Creates an intent that only captures a thumbnail then finishes automatically. */
        fun newCaptureIntent(context: Context, modelPath: String, modelName: String): Intent {
            Utils.init()
            return Intent(context, ModelViewerActivity::class.java).apply {
                putExtra(EXTRA_MODEL_PATH, modelPath)
                putExtra(EXTRA_MODEL_NAME, modelName)
                putExtra(EXTRA_CAPTURE_ONLY, true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        captureOnly = intent.getBooleanExtra(EXTRA_CAPTURE_ONLY, false)

        if (captureOnly) {
            // Hide all UI — we just need the SurfaceView for on-screen render; offscreen thumb is headless.
            binding.toolbar.visibility = android.view.View.INVISIBLE
            binding.btnResetRotation.visibility = android.view.View.INVISIBLE
            binding.btnAutoRotate.visibility = android.view.View.INVISIBLE
        } else {
            binding.btnResetRotation.setOnClickListener { clickedResetView() }
            binding.btnAutoRotate.setOnClickListener { clickedAutoRotate() }
        }

        thumbnailExists = modelHasThumbnail()

        if (!captureOnly) setupToolbar()
        setupModelViewer()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: "3D Model"
        supportActionBar?.title = modelName
    }

    private var hasAppliedThumbnailFraming = false

    private fun setupModelViewer() {
        surfaceView = binding.modelSurface
        newModelViewer = ModelViewer(surfaceView)

        // Wire touch handling and satisfy accessibility by calling performClick().
        surfaceView.setOnTouchListener { v, event ->
            val handled = newModelViewer.onTouch(v, event)
            if (handled) v.performClick()
            handled
        }

        // No skybox — transparent background so the model renders with alpha channel.
        newModelViewer.scene.skybox = null

        // Set renderer clear color to fully transparent black.
        val opts = newModelViewer.renderer.clearOptions
        opts.clearColor[0] = 0f
        opts.clearColor[1] = 0f
        opts.clearColor[2] = 0f
        opts.clearColor[3] = 0f
        opts.clear = true
        newModelViewer.renderer.clearOptions = opts

        val indirectLightFile = loadAsset("ktx/test.ktx")
        if (indirectLightFile != null) {
            val indirectLighting = KTX1Loader.createIndirectLight(newModelViewer.engine, indirectLightFile)
            indirectLighting.indirectLight?.intensity = 50_000f
            newModelViewer.scene.indirectLight = indirectLighting.indirectLight
        }

        lifecycleScope.launch {
            binding.progressLoading.visibility = android.view.View.VISIBLE

            val modelBuffer = withContext(Dispatchers.IO) { loadGlb() }
            if (modelBuffer == null) {
                binding.progressLoading.visibility = android.view.View.GONE
                return@launch
            }

            newModelViewer.loadModelGlb(modelBuffer)
            newModelViewer.transformToUnitCube()

            // Stable camera pose so thumbnails are deterministic.
            applyThumbnailCameraFraming(newModelViewer.view.camera)
            hasAppliedThumbnailFraming = true

            modelReadyForThumbnail = true
            framesAfterLoad = 0   // reset so the frame callback counts real rendered frames
            binding.progressLoading.visibility = android.view.View.GONE
            // The frame callback counts up to FRAMES_TO_SETTLE real rendered frames
            // before calling scheduleThumbnailCapture() — works for both captureOnly and normal mode.
        }
    }

    private fun applyThumbnailCameraFraming(cam: Camera?) {
        try {
            if (cam == null) {
                Log.w("ModelViewerActivity", "Thumb: camera is null - cannot apply framing")
                return
            }

            cam.lookAt(
                0.9, 0.6, 2.2,
                0.0, 0.0, 0.0,
                0.0, 1.0, 0.0
            )
            cam.setProjection(
                35.0,
                /* aspect = */ 1.0,
                0.05,
                50.0,
                Camera.Fov.VERTICAL
            )
            Log.d("ModelViewerActivity", "Thumb: applied camera framing")
        } catch (e: Exception) {
            Log.w("ModelViewerActivity", "Thumb: failed to apply camera framing", e)
        }
    }

    // ── Frame loop ────────────────────────────────────────────────────────────

    private val frameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            newModelViewer.render(frameTimeNanos)

            if (modelReadyForThumbnail && !thumbnailExists && !thumbnailCaptureScheduled) {
                framesAfterLoad++
                if (framesAfterLoad >= FRAMES_TO_SETTLE && hasAppliedThumbnailFraming) {
                    scheduleThumbnailCapture()
                }
            }

            choreographer.postFrameCallback(this)
        }
    }

    // ── Thumbnail capture (OFFSCREEN Filament) ───────────────────────────────

    private fun scheduleThumbnailCapture() {
        thumbnailCaptureScheduled = true
        Log.d("ModelViewerActivity", "Thumb: scheduling thumbnail capture")
        tryCaptureThumbnail(attempt = 1)
    }

    private fun tryCaptureThumbnail(attempt: Int) {
        if (attempt > CAPTURE_MAX_ATTEMPTS) {
            Log.e("ModelViewerActivity", "Thumb: giving up after $CAPTURE_MAX_ATTEMPTS attempts (no usable frame)")
            thumbnailCaptureScheduled = false
            if (captureOnly) finish()
            return
        }

        Log.d("ModelViewerActivity", "Thumb: capture attempt=$attempt")

        // Wait a bit so the SurfaceView has a queued frame. PixelCopy will return ERROR_SOURCE_NO_DATA (3)
        // if nothing has been drawn yet.
        Handler(Looper.getMainLooper()).postDelayed(
            Runnable {
                // PixelCopy has an API-24 overload that accepts a SurfaceView. Using that avoids
                // the API-34 overload resolution (PixelCopy.Request, Executor) that some IDEs
                // may try to pick.
                val w = surfaceView.width
                val h = surfaceView.height
                if (w <= 0 || h <= 0) {
                    Log.w("ModelViewerActivity", "Thumb: surfaceView not laid out yet (w=$w h=$h); retrying")
                    tryCaptureThumbnail(attempt + 1)
                    return@Runnable
                }

                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

                try {
                    PixelCopy.request(
                        surfaceView,
                        bmp,
                        { result ->
                            if (result != PixelCopy.SUCCESS) {
                                Log.e("ModelViewerActivity", "Thumb: PixelCopy failed: $result")
                                bmp.recycle()
                                tryCaptureThumbnail(attempt + 1)
                                return@request
                            }

                            lifecycleScope.launch(Dispatchers.Default) {
                                // Sanity check: if every sampled pixel is all-white or all-black
                                // the surface hasn't rendered the model yet — retry.
                                val hasContent = run {
                                    val cx = bmp.width / 2
                                    val cy = bmp.height / 2
                                    var found = false
                                    outer@ for (dy in -30..30 step 10) {
                                        for (dx in -30..30 step 10) {
                                            val px = bmp.getPixel(
                                                (cx + dx).coerceIn(0, bmp.width - 1),
                                                (cy + dy).coerceIn(0, bmp.height - 1)
                                            )
                                            val r = (px shr 16) and 0xFF
                                            val g = (px shr 8) and 0xFF
                                            val b = px and 0xFF
                                            // A pixel that is not pure black and not pure white
                                            // indicates actual model content.
                                            val isBlack = r < 10 && g < 10 && b < 10
                                            val isWhite = r > 245 && g > 245 && b > 245
                                            if (!isBlack && !isWhite) {
                                                found = true
                                                break@outer
                                            }
                                        }
                                    }
                                    found
                                }

                                if (!hasContent) {
                                    bmp.recycle()
                                    Log.w("ModelViewerActivity", "Thumb: no model content detected yet. Retrying…")
                                    withContext(Dispatchers.Main) {
                                        tryCaptureThumbnail(attempt + 1)
                                    }
                                    return@launch
                                }

                                val rawThumb = createSquareThumbnail(bmp, THUMBNAIL_SIZE)
                                bmp.recycle()

                                withContext(Dispatchers.Main) {
                                    saveThumbnail(rawThumb)
                                }
                            }
                        },
                        Handler(Looper.getMainLooper())
                    )
                } catch (t: Throwable) {
                    Log.e("ModelViewerActivity", "Thumb: PixelCopy threw; retrying", t)
                    bmp.recycle()
                    tryCaptureThumbnail(attempt + 1)
                }
            },
            CAPTURE_RETRY_DELAY_MS
        )
    }

    /**
     * Center-crops the captured bitmap to a square and scales it to [outSize]×[outSize].
     * The background is whatever Filament rendered (white), so no cutout is needed.
     */
    private fun createSquareThumbnail(bmp: Bitmap, outSize: Int): Bitmap {
        val size = minOf(bmp.width, bmp.height)
        val x = (bmp.width - size) / 2
        val y = (bmp.height - size) / 2

        val cropped = Bitmap.createBitmap(bmp, x, y, size, size)
        val scaled = Bitmap.createScaledBitmap(cropped, outSize, outSize, true)
        if (cropped !== scaled) cropped.recycle()
        return scaled
    }


    private fun saveThumbnail(bitmap: android.graphics.Bitmap) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val modelBaseName = getModelFileNameWithoutExtension() ?: return@launch
                val thumbsDir = File(filesDir, "thumbnails")
                if (!thumbsDir.exists()) thumbsDir.mkdirs()

                val safeBase = modelBaseName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                val thumbFileName = "${safeBase}_thumb.png"
                val thumbFile = File(thumbsDir, thumbFileName)

                FileOutputStream(thumbFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.flush()
                }
                bitmap.recycle()

                // Evict stale cache entry if present
                com.example.surveyingapp.ui.viewpoints.SimpleCoordinatesAdapter
                    .evictThumbnail(thumbFile.absolutePath)

                // Update Room database record with thumbnail info
                try {
                    val db = AppDatabase.getDatabase(applicationContext)
                    val repo = ModelRepositoryImpl(db.modelDao())
                    val modelFileName = intent.getStringExtra(EXTRA_MODEL_PATH)
                        ?.substringAfterLast('/')
                        ?.substringAfterLast('\\')
                    if (modelFileName != null) {
                        val matched = repo.getModelByFileName(modelFileName)
                        if (matched != null) {
                            repo.updateModel(
                                matched.copy(
                                    thumbnailFileName = thumbFileName,
                                    thumbnailFilePath = thumbFile.absolutePath
                                )
                            )
                        } else {
                            Log.w("ModelViewerActivity", "No model found for $modelFileName")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ModelViewerActivity", "Failed to update DB thumbnail", e)
                }

                withContext(Dispatchers.Main) {
                    thumbnailExists = true
                    thumbnailCaptureScheduled = false
                    Log.d("ModelViewerActivity", "Thumbnail saved OK: $thumbFileName")
                    if (captureOnly) {
                        finish()
                    }
                }
            } catch (e: Exception) {
                Log.e("ModelViewerActivity", "Failed to save thumbnail", e)
                thumbnailCaptureScheduled = false
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun loadGlb(): ByteBuffer? {
        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: return null
        return loadFile(modelPath)
    }

    private fun loadFile(filePath: String): ByteBuffer? {
        val file = File(filePath)
        if (!file.exists()) return null
        val buffer = ByteBuffer.allocateDirect(file.length().toInt())
        file.inputStream().use { buffer.put(it.readBytes()) }
        buffer.rewind()
        return buffer
    }

    private fun loadIndirectLightKtx(filePath: String): ByteBuffer? = loadAsset(filePath)

    private fun loadAsset(filePath: String): ByteBuffer? {
        val bytes = assets.open(filePath).use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size)
        buffer.put(bytes); buffer.rewind()
        return buffer
    }

    private fun getModelFileNameWithoutExtension(): String? =
        intent.getStringExtra(EXTRA_MODEL_PATH)
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')

    private fun modelHasThumbnail(): Boolean {
        val fileName = getModelFileNameWithoutExtension() ?: return false
        val safeBase = fileName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val thumbFile = File(filesDir, "thumbnails/${safeBase}_thumb.png")
        return thumbFile.exists()
    }

    private fun clickedResetView() {
        Log.d("ModelViewerActivity", "Resetting model view")
    }

    private fun clickedAutoRotate() {
        autoRotate = !autoRotate
        Log.d("ModelViewerActivity", "autoRotate set to $autoRotate")
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish(); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        choreographer = android.view.Choreographer.getInstance()
        choreographer.postFrameCallback(frameCallback)
    }

    override fun onPause() {
        super.onPause()
        choreographer.removeFrameCallback(frameCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        choreographer.removeFrameCallback(frameCallback)
    }
}
