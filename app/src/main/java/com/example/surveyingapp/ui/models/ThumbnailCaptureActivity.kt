package com.example.surveyingapp.ui.models

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.surveyingapp.data.local.db.AppDatabase
import com.example.surveyingapp.data.repository.impl.ModelRepositoryImpl
import com.google.android.filament.Camera
import com.google.android.filament.Skybox
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * An invisible activity that renders a GLB model off-screen, captures a thumbnail and finishes.
 *
 * It uses a transparent/translucent theme so the user sees nothing. The SurfaceView is
 * given a small fixed size (256×256) and positioned off-screen via layout params so it is
 * never visible even if translucency fails.
 */
class ThumbnailCaptureActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ThumbCaptureActivity"

        private const val EXTRA_MODEL_PATH = "model_path"
        private const val EXTRA_MODEL_NAME = "model_name"
        private const val EXTRA_MODEL_FILE_NAME = "model_file_name"

        private const val THUMB_SIZE = 256
        private const val FRAMES_TO_SETTLE = 60
        private const val CAPTURE_MAX_ATTEMPTS = 30
        private const val CAPTURE_RETRY_DELAY_MS = 100L

        fun start(context: Context, modelPath: String, modelName: String, modelFileName: String) {
            Utils.init()
            val intent = Intent(context, ThumbnailCaptureActivity::class.java).apply {
                putExtra(EXTRA_MODEL_PATH, modelPath)
                putExtra(EXTRA_MODEL_NAME, modelName)
                putExtra(EXTRA_MODEL_FILE_NAME, modelFileName)
                // Keep activity stack intact — don't show in recents, start without animation.
                addFlags(
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_HISTORY
                )
            }
            context.startActivity(intent)
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private lateinit var surfaceView: SurfaceView
    private var modelViewer: ModelViewer? = null
    private var choreographer: Choreographer? = null
    private var frameCallback: Choreographer.FrameCallback? = null

    private var modelPath = ""
    private var modelName = ""
    private var modelFileName = ""

    private var framesRendered = 0
    private var captureScheduled = false
    private var modelReady = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: run { finish(); return }
        modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: "model"
        modelFileName = intent.getStringExtra(EXTRA_MODEL_FILE_NAME) ?: File(modelPath).name

        // The window must remain composited by the GPU (so PixelCopy works), but we
        // move it entirely off-screen so the user never sees the black SurfaceView.
        // Setting alpha=0 or visibility=GONE breaks PixelCopy (all-black frames).
        // FLAG_LAYOUT_NO_LIMITS allows the window position to exceed screen boundaries.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.setLayout(THUMB_SIZE, THUMB_SIZE)
        // Move the window far off-screen (negative coords) so it is invisible to the user
        // while still being composited by the GPU compositor (required for PixelCopy).
        val lp = window.attributes
        lp.x = -10_000
        lp.y = -10_000
        window.attributes = lp

        // Use a small SurfaceView as our rendering target.
        // Force exact pixel dimensions so Filament's viewport is always square.
        surfaceView = SurfaceView(this).apply {
            layoutParams = ViewGroup.LayoutParams(THUMB_SIZE, THUMB_SIZE)
        }
        setContentView(surfaceView, ViewGroup.LayoutParams(THUMB_SIZE, THUMB_SIZE))

        // Wait until the SurfaceView is laid out before initialising Filament so
        // we can be sure the surface is exactly THUMB_SIZE × THUMB_SIZE.
        surfaceView.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            Log.d(TAG, "SurfaceView laid out: ${w}x${h}")
        }

        initFilament()
    }

    private fun initFilament() {
        try {
            val viewer = ModelViewer(surfaceView)
            modelViewer = viewer

            // White background.
            viewer.scene.skybox = Skybox.Builder().build(viewer.engine)
            viewer.scene.skybox?.setColor(1f, 1f, 1f, 1f)

            try {
                val bytes = assets.open("ktx/test.ktx").use { it.readBytes() }
                val buf = ByteBuffer.allocateDirect(bytes.size)
                buf.put(bytes); buf.rewind()
                val il = KTX1Loader.createIndirectLight(viewer.engine, buf)
                il.indirectLight?.intensity = 50_000f
                viewer.scene.indirectLight = il.indirectLight
            } catch (e: Exception) {
                Log.w(TAG, "Could not load indirect light: ${e.message}")
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val glbFile = File(modelPath)
                if (!glbFile.exists()) {
                    Log.e(TAG, "GLB file not found: $modelPath")
                    withContext(Dispatchers.Main) { finish() }
                    return@launch
                }
                val buf = ByteBuffer.allocateDirect(glbFile.length().toInt())
                glbFile.inputStream().use { buf.put(it.readBytes()) }
                buf.rewind()

                withContext(Dispatchers.Main) {
                    viewer.loadModelGlb(buf)
                    viewer.transformToUnitCube()
                    applyCamera(viewer)
                    modelReady = true
                    framesRendered = 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Filament init failed", e)
            finish()
        }
    }

    private fun applyCamera(viewer: ModelViewer) {
        try {
            // Force the Filament viewport to be exactly square so the aspect ratio
            // of the captured thumbnail is always 1:1, regardless of what the
            // window manager reports for the surface size.
            viewer.view.viewport = com.google.android.filament.Viewport(
                0, 0, THUMB_SIZE, THUMB_SIZE
            )

            val cam = viewer.view.camera ?: run {
                Log.w(TAG, "Camera is null - skipping framing")
                return
            }
            // aspect = width / height = 1.0 for a square thumbnail
            cam.setProjection(35.0, 1.0, 0.05, 50.0, Camera.Fov.VERTICAL)
            cam.lookAt(0.9, 0.6, 2.2, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        } catch (e: Exception) {
            Log.w(TAG, "Camera setup failed: ${e.message}")
        }
    }

    // ── Frame loop ────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        val ch = Choreographer.getInstance()
        choreographer = ch
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                modelViewer?.render(frameTimeNanos)
                if (modelReady && !captureScheduled) {
                    framesRendered++
                    if (framesRendered >= FRAMES_TO_SETTLE) {
                        captureScheduled = true
                        tryCapture(1)
                        return  // stop posting; capture will call finish()
                    }
                }
                ch.postFrameCallback(this)
            }
        }
        frameCallback = cb
        ch.postFrameCallback(cb)
    }

    override fun onPause() {
        super.onPause()
        frameCallback?.let { choreographer?.removeFrameCallback(it) }
    }

    // ── PixelCopy ─────────────────────────────────────────────────────────────

    private fun tryCapture(attempt: Int) {
        if (attempt > CAPTURE_MAX_ATTEMPTS) {
            Log.e(TAG, "Giving up after $CAPTURE_MAX_ATTEMPTS attempts")
            finishWithCleanup()
            return
        }
        Log.d(TAG, "Capture attempt=$attempt")

        Handler(Looper.getMainLooper()).postDelayed({
            val w = surfaceView.width; val h = surfaceView.height
            if (w <= 0 || h <= 0) {
                Log.w(TAG, "Surface not laid out (w=$w h=$h); retrying")
                tryCapture(attempt + 1)
                return@postDelayed
            }

            // Always capture a square region matching Filament's viewport.
            // The surface may be larger than THUMB_SIZE due to window chrome or
            // display scaling — clamp to the actual surface size so the Rect is valid.
            val captureSize = minOf(w, h, THUMB_SIZE)
            val srcRect = android.graphics.Rect(0, 0, captureSize, captureSize)
            Log.d(TAG, "PixelCopy srcRect=$srcRect surface=${w}x${h}")

            // Allocate bitmap at exactly captureSize×captureSize (square) — no scaling needed.
            val bmp = Bitmap.createBitmap(captureSize, captureSize, Bitmap.Config.ARGB_8888)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    @Suppress("DEPRECATION")
                    PixelCopy.request(
                        surfaceView, srcRect, bmp,
                        { result ->
                            if (result != PixelCopy.SUCCESS) {
                                Log.w(TAG, "PixelCopy failed: $result; retrying")
                                bmp.recycle()
                                tryCapture(attempt + 1)
                                return@request
                            }
                            analyzeAndSave(bmp, attempt)
                        },
                        Handler(Looper.getMainLooper())
                    )
                } else {
                    // API < 26: fall back to drawing the SurfaceView into a canvas
                    bmp.recycle()
                    Log.w(TAG, "API < 26, PixelCopy unavailable; giving up")
                    finishWithCleanup()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "PixelCopy threw; retrying", t)
                bmp.recycle()
                tryCapture(attempt + 1)
            }
        }, CAPTURE_RETRY_DELAY_MS)
    }

    private fun analyzeAndSave(bmp: Bitmap, attempt: Int) {
        lifecycleScope.launch(Dispatchers.Default) {
            // Check if the frame contains any content other than pure black.
            // We accept pure white (the Filament skybox background) as a valid frame.
            // We only reject frames where every sampled pixel is pitch-black (R=0,G=0,B=0)
            // which means the GPU surface hasn't rendered yet.
            val hasAnyNonBlackPixel = run {
                var found = false
                outer@ for (y in 0 until bmp.height step 16) {
                    for (x in 0 until bmp.width step 16) {
                        val px = bmp.getPixel(x, y)
                        val r = (px shr 16) and 0xFF
                        val g = (px shr 8) and 0xFF
                        val b = px and 0xFF
                        if (r > 5 || g > 5 || b > 5) {
                            found = true
                            break@outer
                        }
                    }
                }
                found
            }

            if (!hasAnyNonBlackPixel) {
                bmp.recycle()
                Log.w(TAG, "Frame is all black; retrying (attempt=$attempt)")
                withContext(Dispatchers.Main) { tryCapture(attempt + 1) }
                return@launch
            }

            Log.d(TAG, "Frame has content, saving thumbnail")
            // The bitmap is already square (captureSize x captureSize).
            // Scale up to THUMB_SIZE only if we captured at a smaller size.
            val thumb = if (bmp.width == THUMB_SIZE && bmp.height == THUMB_SIZE) {
                bmp
            } else {
                val scaled = Bitmap.createScaledBitmap(bmp, THUMB_SIZE, THUMB_SIZE, true)
                bmp.recycle()
                scaled
            }
            saveThumbnail(thumb)
        }
    }


    private fun saveThumbnail(bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val thumbsDir = File(filesDir, "thumbnails")
                if (!thumbsDir.exists()) thumbsDir.mkdirs()

                val safeBase = modelFileName
                    .substringBeforeLast(".")
                    .replace(Regex("[^A-Za-z0-9_.-]"), "_")
                val thumbFileName = "${safeBase}_thumb.png"
                val thumbFile = File(thumbsDir, thumbFileName)

                FileOutputStream(thumbFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.flush()
                }
                bitmap.recycle()

                try {
                    com.example.surveyingapp.ui.viewpoints.SimpleCoordinatesAdapter
                        .evictThumbnail(thumbFile.absolutePath)
                } catch (e: Exception) { /* best effort */ }

                try {
                    val db = AppDatabase.getDatabase(applicationContext)
                    val repo = ModelRepositoryImpl(db.modelDao())
                    val matched = repo.getModelByFileName(modelFileName)
                    if (matched != null) {
                        repo.updateModel(
                            matched.copy(
                                thumbnailFileName = thumbFileName,
                                thumbnailFilePath = thumbFile.absolutePath
                            )
                        )
                        Log.d(TAG, "Thumbnail saved and DB updated: $thumbFileName")
                    } else {
                        Log.w(TAG, "No DB record found for: $modelFileName")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "DB update failed", e)
                }

                withContext(Dispatchers.Main) { finishWithCleanup() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save thumbnail", e)
                withContext(Dispatchers.Main) { finishWithCleanup() }
            }
        }
    }

    private fun finishWithCleanup() {
        frameCallback?.let { choreographer?.removeFrameCallback(it) }
        frameCallback = null
        try { modelViewer?.destroyModel() } catch (e: Exception) { }
        modelViewer = null
        // No animation on finish.
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        frameCallback?.let { choreographer?.removeFrameCallback(it) }
        try { modelViewer?.destroyModel() } catch (e: Exception) { }
        super.onDestroy()
    }
}

