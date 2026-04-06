package com.example.surveyingapp.ui.models

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MenuItem
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.example.surveyingapp.data.local.db.AppDatabase
import com.example.surveyingapp.data.repository.impl.ModelRepositoryImpl
import com.example.surveyingapp.databinding.ActivityModelViewerBinding
import com.example.surveyingapp.R
import com.google.android.filament.*
import com.google.android.filament.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import android.opengl.Matrix as GlMatrix

class ModelViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelViewerBinding
    private lateinit var surfaceView: SurfaceView
    // Nullable until initialization completes on the main thread after async setup
    private var newModelViewer: ModelViewer? = null
    // Make choreographer nullable and guard calls (safer across lifecycle transitions)
    private var choreographer: android.view.Choreographer? = null

    private var thumbnailExists = false
    private var thumbnailCaptureScheduled = false
    private var modelReadyForThumbnail = false
    private var framesAfterLoad = 0

    private var autoRotate = false

    // Euler rotation angles (degrees) driven by SeekBars and auto-rotate.
    private var rotX = 0f
    private var rotY = 0f
    private var rotZ = 0f

    // The unit-cube base transform stored after transformToUnitCube(); rotation is composed on top.
    private var baseTransform: FloatArray? = null

    // Dynamic lighting: a directional sunlight toggled by the user.
    private var dynamicLightingEnabled = true
    //private var sunLightEntity: Int = 0

    companion object {
        private const val EXTRA_MODEL_PATH = "model_path"
        private const val EXTRA_MODEL_NAME = "model_name"

        private const val FRAMES_TO_SETTLE = 60
        private const val THUMBNAIL_SIZE = 256
        private const val CAPTURE_MAX_ATTEMPTS = 20
        private const val CAPTURE_RETRY_DELAY_MS = 250L

        // Minimum nanoseconds between consecutive renders. Set to ~33_333_333ns (30 FPS) to
        // reduce CPU on slower devices / emulators and avoid skipped-frame churn.
        private const val MIN_RENDER_INTERVAL_NS = 33_333_333L

        fun newIntent(context: Context, modelPath: String, modelName: String): Intent {
            Utils.init()
            return Intent(context, ModelViewerActivity::class.java).apply {
                putExtra(EXTRA_MODEL_PATH, modelPath)
                putExtra(EXTRA_MODEL_NAME, modelName)
            }
        }
    }

    // Track when we last rendered to enforce MIN_RENDER_INTERVAL_NS.
    private var lastRenderTimeNs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check if the device supports Filament for 3D rendering; if not, show an error message and skip setup.
        if (!supportsFilamentViewer()) {
            binding.textError.visibility = View.VISIBLE
            binding.progressLoading.visibility = View.GONE

            android.app.AlertDialog.Builder(this)
                .setTitle("Device Not Supported")
                .setMessage("This device does not support the required OpenGL ES 3.0 for 3D rendering. Please try again on a different device.")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    finish()
                }
                .setCancelable(false)
                .show()

            return
        }

        // We assume OpenGL is supported past this point

        // Check if a thumbnail already exists for this model for later use
        thumbnailExists = modelHasThumbnail()

        binding.progressLoading.visibility = View.VISIBLE
        setupToolbar()

        // Set name/path of 3d model on the UI
        binding.textModelName.text = intent.getStringExtra(EXTRA_MODEL_NAME) ?: "3D Model";
        binding.textModelFilename.text = intent.getStringExtra(EXTRA_MODEL_PATH)?.substringAfterLast('/')?.substringAfterLast('\\') ?: "Unknown file";

        // Set up button click listeners
        binding.btnResetRotation.setOnClickListener { clickedResetView() }
        binding.btnAutoRotate.setOnClickListener { clickedAutoRotate() }
        binding.btnToggleLighting.setOnClickListener { clickedToggleIndirectLight() }
        binding.btnCaptureThumbnail.setOnClickListener { onCaptureThumbnailClicked() }

        // Load the model viewer surface view
        if (!setupModelViewer())
        {
            Log.e("ModelViewerActivity", "Something went wrong while loading the model viewer.")
            binding.textError.visibility = View.VISIBLE
            return
        }
    }

    private fun supportsFilamentViewer(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return am.deviceConfigurationInfo.reqGlEsVersion >= 0x30000
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private var hasAppliedThumbnailFraming = false

    private fun setupModelViewer(): Boolean {
        surfaceView = binding.modelSurface

        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
        val modelFile = modelPath?.let { File(it) }

        if (modelFile == null || !modelFile.exists()) {
            return false
        }

        lifecycleScope.launch {
            // ── All heavy work off the main thread ──────────────────────────
            val modelBuffer = withContext(Dispatchers.IO) { loadFile(modelFile.absolutePath) }
            val ktxBuffer   = withContext(Dispatchers.IO) { loadAsset("ktx/test.ktx") }

            // Guard: if the activity was destroyed while IO was in flight, abort.
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) return@launch

            if (modelBuffer == null) {
                return@launch
            }

            // ModelViewer must be created on the main thread (it attaches to the SurfaceView).
            val viewer = ModelViewer(surfaceView)

            Log.d("ModelViewerActivity", viewer.scene.indirectLight.toString())


            // Touch handler — use newModelViewer (nullable) rather than the closed-over
            // `viewer` so that clearing newModelViewer in onDestroy also cuts this path.
            surfaceView.setOnTouchListener { v, event ->
                val handled = newModelViewer?.onTouch(v, event) ?: false
                if (handled) v.performClick()
                handled
            }

            // Solid white background via a colour Skybox.
            // • skybox = null leaves the background undefined — Filament will not clear those
            //   pixels, so the previous frame bleeds through ("ghost image") as the model rotates.
            // • A colour Skybox is rendered as the background layer every frame, which is the
            //   correct way to get a solid-colour background in Filament.
            viewer.scene.skybox = Skybox.Builder()
                .color(1f, 1f, 1f, 1f)
                .build(viewer.engine)

            // Also properly re-assign clearOptions.
            // renderer.clearOptions returns a *copy* of the struct; mutating it with .apply
            // without re-assigning is a no-op — the renderer never sees the change.
            viewer.renderer.clearOptions = Renderer.ClearOptions().apply {
                clearColor[0] = 1f; clearColor[1] = 1f; clearColor[2] = 1f; clearColor[3] = 1f
                clear = true
            }

            // Indirect lighting (buffer already loaded off-thread)
            if (ktxBuffer != null) {
                val ibl = KTX1Loader.createIndirectLight(viewer.engine, ktxBuffer)
                ibl.indirectLight?.intensity = 50_000f
                viewer.scene.indirectLight = ibl.indirectLight
            }

            // Load model geometry. .gltf needs a sidecar resolver for texture/bin files.
            val loaded = loadModelIntoViewer(viewer, modelFile, modelBuffer)
            if (!loaded) {
                binding.progressLoading.visibility = View.GONE
                binding.textError?.visibility = View.VISIBLE
                return@launch
            }
            viewer.transformToUnitCube()

            // Snapshot the unit-cube transform so rotation is always composed on top of it.
            viewer.asset?.let { asset ->
                val tm = viewer.engine.transformManager
                val base = FloatArray(16)
                tm.getTransform(tm.getInstance(asset.root), base)
                baseTransform = base
            }

            applyThumbnailCameraFraming(viewer.view.camera)
            hasAppliedThumbnailFraming = true


            // Create a directional sun light so the user can toggle it on/off.
            // Done here (after the engine is live) to avoid a use-before-init crash.

            // We can't use sun here becuase IndirectLight takes priority in terms of a light source
//            val sun = EntityManager.get().create()
//            LightManager.Builder(LightManager.Type.DIRECTIONAL)
//                .color(1.0f, 0.98f, 0.95f)   // slightly warm white
//                .intensity(100_000f)
//                .direction(0.0f,-1.0f,-0.5f)
//                .castShadows(true)
//                .build(viewer.engine, sun)
//            viewer.scene.addEntity(sun)
//            sunLightEntity = sun
            dynamicLightingEnabled = true



            newModelViewer = viewer
            modelReadyForThumbnail = true
            framesAfterLoad = 0
        }

        return true
    }

    private fun loadModelIntoViewer(viewer: ModelViewer, modelFile: File, modelBuffer: ByteBuffer): Boolean {
        return try {
            val lower = modelFile.name.lowercase()
            if (lower.endsWith(".gltf")) {
                val baseDir = modelFile.parentFile?.canonicalFile
                viewer.loadModelGltf(modelBuffer) { uriString ->
                    resolveGltfResource(baseDir, uriString)
                }
            } else {
                viewer.loadModelGlb(modelBuffer)
            }
            true
        } catch (e: Exception) {
            Log.e("ModelViewerActivity", "Failed to load model: ${modelFile.absolutePath}", e)
            false
        }
    }

    private fun resolveGltfResource(baseDir: File?, uriString: String): ByteBuffer {
        if (baseDir == null || uriString.startsWith("data:")) {
            return ByteBuffer.allocateDirect(0)
        }
        return try {
            val decoded = Uri.decode(uriString).substringBefore('#').substringBefore('?')
            val candidate = File(baseDir, decoded).canonicalFile
            if (!candidate.path.startsWith(baseDir.path) || !candidate.exists()) {
                Log.w("ModelViewerActivity", "Missing glTF resource: $uriString")
                return ByteBuffer.allocateDirect(0)
            }
            loadFile(candidate.absolutePath) ?: ByteBuffer.allocateDirect(0)
        } catch (e: Exception) {
            Log.w("ModelViewerActivity", "Failed to resolve glTF resource: $uriString", e)
            ByteBuffer.allocateDirect(0)
        }
    }

    private fun applyThumbnailCameraFraming(cam: Camera?) {
        try {
            if (cam == null) return
            val aspect = if (::surfaceView.isInitialized && surfaceView.height > 0) {
                surfaceView.width.toDouble() / surfaceView.height.toDouble()
            } else {
                1.0
            }
            cam.lookAt(0.9, 0.6, 2.2, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
            // Use the actual surface aspect ratio for on-screen preview; forcing 1.0
            // stretches models on wide screens. Thumbnail capture still crops to square.
            cam.setProjection(35.0, aspect, 0.05, 50.0, Camera.Fov.VERTICAL)
        } catch (e: Exception) {
            Log.w("ModelViewerActivity", "Thumb: failed to apply camera framing", e)
        }
    }

    // ── Frame loop ────────────────────────────────────────────────────────────

    private val frameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            // Throttle rendering if frames arrive too quickly; this prevents hammering the
            // main thread on slow devices/emulators and reduces skipped-frame warnings.
            val last = lastRenderTimeNs
            if (last != 0L) {
                val dt = frameTimeNanos - last
                if (dt < MIN_RENDER_INTERVAL_NS) {
                    // Post next frame and skip rendering this time.
                    choreographer?.postFrameCallback(this)
                    return
                }
            }
             val viewer = newModelViewer
             if (viewer != null) {
                 // Auto-rotate: advance Y angle and update SeekBar UI (fromUser=false → no recursion)
                 if (autoRotate) {
                     rotY = (rotY + 0.5f) % 360f
                     val yDeg = rotY.toInt()
                     //binding.seekRotationY.progress = yDeg
                     //binding.textRotationY.text = "${yDeg}°"
                     applyRotation()
                 }

                 viewer.render(frameTimeNanos)
                 lastRenderTimeNs = frameTimeNanos

                 if (framesAfterLoad < 2) {
                     framesAfterLoad++
                     if (framesAfterLoad == 2) {
                         binding.progressLoading.visibility = View.GONE
                     }
                 }
             }
             choreographer?.postFrameCallback(this)
         }
     }

    // ── Manual capture (user taps button) ────────────────────────────────────

    private fun onCaptureThumbnailClicked() {
        if (thumbnailCaptureScheduled) return
        binding.btnCaptureThumbnail.isEnabled = false
        binding.btnCaptureThumbnail.text = "Capturing…"
        scheduleThumbnailCapture(finishAfter = true)
    }

    // ── Thumbnail capture ─────────────────────────────────────────────────────

    private fun scheduleThumbnailCapture(finishAfter: Boolean) {
        thumbnailCaptureScheduled = true
        Log.d("ModelViewerActivity", "Thumb: scheduling thumbnail capture (finishAfter=$finishAfter)")
        tryCaptureThumbnail(attempt = 1, finishAfter = finishAfter)
    }

    private fun tryCaptureThumbnail(attempt: Int, finishAfter: Boolean) {
        if (attempt > CAPTURE_MAX_ATTEMPTS) {
            Log.e("ModelViewerActivity", "Thumb: giving up after $CAPTURE_MAX_ATTEMPTS attempts")
            thumbnailCaptureScheduled = false

            // Fallback: if on-screen PixelCopy failed repeatedly, delegate to the
            // off-screen ThumbnailCaptureActivity which uses a small hidden SurfaceView
            // and is more reliable for generating thumbnails.
            try {
                val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: ""
                val modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: "model"
                val modelFileName = intent.getStringExtra(EXTRA_MODEL_PATH)
                    ?.substringAfterLast('/')?.substringAfterLast('\\') ?: "model"

                Log.w("ModelViewerActivity", "Thumb: falling back to off-screen capture for $modelFileName")
                ThumbnailCaptureActivity.start(this, modelPath, modelName, modelFileName)
            } catch (e: Exception) {
                Log.e("ModelViewerActivity", "Failed to start ThumbnailCaptureActivity", e)

                binding.btnCaptureThumbnail.isEnabled = true
                binding.btnCaptureThumbnail.text = "Capture Thumbnail"
                Toast.makeText(this, "Capture failed — fallback invoked", Toast.LENGTH_SHORT).show()
            }

            if (finishAfter) finish()
            return
        }

        Log.d("ModelViewerActivity", "Thumb: capture attempt=$attempt")

        Handler(Looper.getMainLooper()).postDelayed({
            val w = surfaceView.width
            val h = surfaceView.height
            if (w <= 0 || h <= 0) {
                tryCaptureThumbnail(attempt + 1, finishAfter)
                return@postDelayed
            }

            // Read directly from the Filament SurfaceView's surface buffer.
            // Window-level PixelCopy (ofWindow) only captures the window layer, which
            // does NOT include the SurfaceView content — SurfaceView renders behind the
            // window by default, so window-level capture returns a white/blank frame.
            // Direct surface PixelCopy reads the actual Filament-rendered pixels.
            val srcRect = Rect(0, 0, w, h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                @Suppress("DEPRECATION")
                PixelCopy.request(surfaceView, srcRect, bmp, { result ->
                    if (result != PixelCopy.SUCCESS) {
                        Log.e("ModelViewerActivity", "Thumb: PixelCopy failed result=$result; retrying")
                        bmp.recycle()
                        tryCaptureThumbnail(attempt + 1, finishAfter)
                        return@request
                    }
                    processCapturedBitmap(bmp, attempt, finishAfter)
                }, Handler(Looper.getMainLooper()))
            } catch (t: Throwable) {
                Log.e("ModelViewerActivity", "Thumb: PixelCopy threw; retrying", t)
                bmp.recycle()
                tryCaptureThumbnail(attempt + 1, finishAfter)
            }
        }, CAPTURE_RETRY_DELAY_MS)
    }

    /** Checks the captured bitmap for model content, then generates and saves the thumbnail. */
    private fun processCapturedBitmap(bmp: Bitmap, attempt: Int, finishAfter: Boolean) {
        lifecycleScope.launch(Dispatchers.Default) {
            val cx = bmp.width / 2; val cy = bmp.height / 2
            var hasContent = false
            outer@ for (dy in -30..30 step 10) {
                for (dx in -30..30 step 10) {
                    val px = bmp.getPixel(
                        (cx + dx).coerceIn(0, bmp.width - 1),
                        (cy + dy).coerceIn(0, bmp.height - 1))
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    if (!(r > 245 && g > 245 && b > 245)) { hasContent = true; break@outer }
                }
            }
            if (!hasContent) {
                bmp.recycle()
                Log.w("ModelViewerActivity", "Thumb: all-white frame, retrying…")
                withContext(Dispatchers.Main) { tryCaptureThumbnail(attempt + 1, finishAfter) }
                return@launch
            }
            val thumb = createSquareThumbnail(bmp, THUMBNAIL_SIZE)
            bmp.recycle()
            withContext(Dispatchers.Main) { saveThumbnail(thumb, finishAfter) }
        }
    }

    private fun createSquareThumbnail(bmp: Bitmap, outSize: Int): Bitmap {
        val size = minOf(bmp.width, bmp.height)
        val x = (bmp.width - size) / 2
        val y = (bmp.height - size) / 2
        val cropped = Bitmap.createBitmap(bmp, x, y, size, size)
        val scaled = Bitmap.createScaledBitmap(cropped, outSize, outSize, true)
        if (cropped !== scaled) cropped.recycle()
        return scaled
    }

    private fun saveThumbnail(bitmap: Bitmap, finishAfter: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val modelBaseName = getModelFileNameWithoutExtension() ?: return@launch
                val thumbsDir = File(filesDir, "thumbnails").also { it.mkdirs() }
                val safeBase = modelBaseName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                val thumbFileName = "${safeBase}_thumb.png"
                val thumbFile = File(thumbsDir, thumbFileName)

                FileOutputStream(thumbFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.flush()
                }
                bitmap.recycle()

                com.example.surveyingapp.ui.viewpoints.SimpleCoordinatesAdapter
                    .evictThumbnail(thumbFile.absolutePath)

                try {
                    val db = AppDatabase.getDatabase(applicationContext)
                    val repo = ModelRepositoryImpl(db.modelDao())
                    val modelFileName = intent.getStringExtra(EXTRA_MODEL_PATH)
                        ?.substringAfterLast('/')?.substringAfterLast('\\')
                    if (modelFileName != null) {
                        val matched = repo.getModelByFileName(modelFileName)
                        if (matched != null) {
                            repo.updateModel(matched.copy(
                                thumbnailFileName = thumbFileName,
                                thumbnailFilePath = thumbFile.absolutePath
                            ))
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ModelViewerActivity", "Failed to update DB thumbnail", e)
                }

                withContext(Dispatchers.Main) {
                    thumbnailExists = true
                    thumbnailCaptureScheduled = false
                    Log.d("ModelViewerActivity", "Thumbnail saved OK: $thumbFileName  viewerAlive=${newModelViewer != null}  surfaceValid=${if (::surfaceView.isInitialized) surfaceView.holder.surface.isValid else false}")

                    Toast.makeText(this@ModelViewerActivity, "Thumbnail saved!", Toast.LENGTH_SHORT).show()
                    binding.btnCaptureThumbnail.text = "Capture Thumbnail"
                    binding.btnCaptureThumbnail.isEnabled = true
                    Log.d("ModelViewerActivity", "saveThumbnail: calling finish() (captureMode)")

                    if (finishAfter) {
                        Log.d("ModelViewerActivity", "saveThumbnail: calling finish() (finishAfter=true)")
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


    private fun loadFile(filePath: String): ByteBuffer? {
        val file = File(filePath)
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val buffer = ByteBuffer.allocateDirect(bytes.size)
        buffer.put(bytes)
        buffer.rewind()
        return buffer
    }

    private fun loadAsset(filePath: String): ByteBuffer? {
        return try {
            val bytes = assets.open(filePath).use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(bytes.size)
            buffer.put(bytes); buffer.rewind()
            buffer
        } catch (e: Exception) { null }
    }

    private fun getModelFileNameWithoutExtension(): String? =
        intent.getStringExtra(EXTRA_MODEL_PATH)
            ?.substringAfterLast('/')?.substringBeforeLast('.')

    private fun modelHasThumbnail(): Boolean {
        val fileName = getModelFileNameWithoutExtension() ?: return false
        val safeBase = fileName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(filesDir, "thumbnails/${safeBase}_thumb.png").exists()
    }

    private fun clickedResetView() {
        val viewer = newModelViewer ?: return

        // Stop auto-rotate
        autoRotate = false
        binding.btnAutoRotate.text = getString(R.string.auto_rotate)

        // Reset rotation angles and SeekBars
        rotX = 0f; rotY = 0f; rotZ = 0f
        //binding.seekRotationX.progress = 0; binding.textRotationX.text = "0°"
        //binding.seekRotationY.progress = 0; binding.textRotationY.text = "0°"
        //binding.seekRotationZ.progress = 0; binding.textRotationZ.text = "0°"

        // Re-centre model and re-snapshot base transform
        viewer.transformToUnitCube()
        viewer.asset?.let { asset ->
            val tm = viewer.engine.transformManager
            val base = FloatArray(16)
            tm.getTransform(tm.getInstance(asset.root), base)
            baseTransform = base
        }
        applyThumbnailCameraFraming(viewer.view.camera)
    }

    private fun clickedAutoRotate() {
        autoRotate = !autoRotate
        binding.btnAutoRotate.text = if (autoRotate) "Stop" else getString(R.string.auto_rotate)
        Log.d("ModelViewerActivity", "autoRotate set to $autoRotate")
    }

    private fun clickedToggleIndirectLight() {
        val viewer = newModelViewer ?: return
        if (viewer.scene.indirectLight == null) return  // no IBL to toggle

        dynamicLightingEnabled = !dynamicLightingEnabled
        if (dynamicLightingEnabled) {
            viewer.scene.indirectLight?.intensity = 50_000f
            binding.btnToggleLighting.text = getString(R.string.lighting_on)
        } else {
            viewer.scene.indirectLight?.intensity = 0f
            binding.btnToggleLighting.text = getString(R.string.lighting_off)
        }
        Log.d("ModelViewerActivity", "dynamicLighting set to $dynamicLightingEnabled")
    }

//    private fun clickedToggleLighting() {
//        val viewer = newModelViewer ?: return
//        val light = sunLightEntity
//        if (light == 0) return  // light not yet created (model still loading)
//
//        dynamicLightingEnabled = !dynamicLightingEnabled
//        if (dynamicLightingEnabled) {
//            viewer.scene.addEntity(light)
//            binding.btnToggleLighting.text = getString(R.string.lighting_on)
//        } else {
//            viewer.scene.removeEntity(light)
//            binding.btnToggleLighting.text = getString(R.string.lighting_off)
//        }
//        Log.d("ModelViewerActivity", "dynamicLighting set to $dynamicLightingEnabled")
//    }

    private fun setupRotationControls() {
        fun makeListener(
            setAngle: (Float) -> Unit,
            updateLabel: (Int) -> Unit
        ) = object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return   // ignore programmatic updates (e.g. from auto-rotate)
                setAngle(p.toFloat())
                updateLabel(p)
                applyRotation()
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        }

//        binding.seekRotationX.setOnSeekBarChangeListener(
//            makeListener({ v -> rotX = v }, { p -> binding.textRotationX.text = "${p}°" })
//        )
//        binding.seekRotationY.setOnSeekBarChangeListener(
//            makeListener({ v -> rotY = v }, { p -> binding.textRotationY.text = "${p}°" })
//        )
//        binding.seekRotationZ.setOnSeekBarChangeListener(
//            makeListener({ v -> rotZ = v }, { p -> binding.textRotationZ.text = "${p}°" })
//        )
    }

    /**
     * Composes Euler X→Y→Z rotations with the stored unit-cube base transform and
     * applies the result to the model's root entity.  Must be called on the main thread.
     */
    private fun applyRotation() {
        val viewer = newModelViewer ?: return
        val asset  = viewer.asset   ?: return
        val base   = baseTransform  ?: return

        // Build individual rotation matrices
        val rx = FloatArray(16).also { GlMatrix.setIdentityM(it, 0); GlMatrix.rotateM(it, 0, rotX, 1f, 0f, 0f) }
        val ry = FloatArray(16).also { GlMatrix.setIdentityM(it, 0); GlMatrix.rotateM(it, 0, rotY, 0f, 1f, 0f) }
        val rz = FloatArray(16).also { GlMatrix.setIdentityM(it, 0); GlMatrix.rotateM(it, 0, rotZ, 0f, 0f, 1f) }

        // Combine rotations: rx * ry * rz
        val temp = FloatArray(16)
        val rot  = FloatArray(16)
        GlMatrix.multiplyMM(temp, 0, rx, 0, ry, 0)
        GlMatrix.multiplyMM(rot,  0, temp, 0, rz, 0)

        // Apply on top of the base (unit-cube) transform: rot * base
        val combined = FloatArray(16)
        GlMatrix.multiplyMM(combined, 0, rot, 0, base, 0)

        val tm = viewer.engine.transformManager
        tm.setTransform(tm.getInstance(asset.root), combined)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("ModelViewerActivity", "onResume: isFinishing=$isFinishing")
        choreographer = android.view.Choreographer.getInstance()
        choreographer?.postFrameCallback(frameCallback)
    }

    override fun onStop() {
        super.onStop()
        Log.d("ModelViewerActivity", "onStop: isFinishing=$isFinishing viewerAlive=${newModelViewer != null} surfaceValid=${if (::surfaceView.isInitialized) surfaceView.holder.surface.isValid else false}")
    }

    override fun onPause() {
        super.onPause()
        Log.d("ModelViewerActivity", "onPause: isFinishing=$isFinishing viewerAlive=${newModelViewer != null} surfaceValid=${if (::surfaceView.isInitialized) surfaceView.holder.surface.isValid else false}")
        choreographer?.removeFrameCallback(frameCallback)
        choreographer = null

        if (isFinishing) @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("ModelViewerActivity", "onDestroy: isFinishing=$isFinishing viewerAlive=${newModelViewer != null}")

        choreographer?.removeFrameCallback(frameCallback)
        choreographer = null

        if (::surfaceView.isInitialized) {
            surfaceView.setOnTouchListener(null)
        }

        val viewer = newModelViewer
        newModelViewer = null   // null first — stops any further rendering use

        if (viewer == null) {
            Log.d("ModelViewerActivity", "onDestroy: viewer was null, nothing to destroy")
            return
        }

        val surfaceValid = ::surfaceView.isInitialized && surfaceView.holder.surface.isValid
        Log.d("ModelViewerActivity", "onDestroy: surfaceValid=$surfaceValid")

        // Destroy model assets immediately — safe while engine is still alive.
        try {
            // Remove and destroy the dynamic sun light before the model, so the
            // scene has no dangling references when destroyModel() runs.
//            if (sunLightEntity != 0) {
//                viewer.scene.removeEntity(sunLightEntity)
//                viewer.engine.lightManager.destroy(sunLightEntity)
//                EntityManager.get().destroy(sunLightEntity)
//                sunLightEntity = 0
//            }
            viewer.destroyModel()
            Log.d("ModelViewerActivity", "onDestroy: destroyModel OK")
        } catch (t: Throwable) {
            Log.w("ModelViewerActivity", "onDestroy: destroyModel threw", t)
        }

        // WHY DEFERRED:
        // When the surface is destroyed (between onPause and onStop), Filament's UiHelper
        // calls onDetachedFromSurface() which calls displayHelper.detach(). The DisplayHelper
        // posts a message to the main thread for final renderer cleanup. That message fires
        // ~5ms after onDestroy() returns — AFTER engine.destroy() if we called it here.
        // engine.destroy() calls Engine::shutdown(), so the deferred message then calls
        // flushAndWait() on a dead engine → SIGABRT.
        //
        // FIX: post engine.destroy() to the END of the main thread queue. Any messages
        // queued by Filament's displayHelper.detach() will process first (while the engine
        // is alive), then our destroy runs safely after them.
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                viewer.engine.flushAndWait()   // drain any remaining GPU commands
                Log.d("ModelViewerActivity", "deferred: flushAndWait OK")
                viewer.engine.destroy()
                Log.d("ModelViewerActivity", "deferred: engine.destroy OK")
            } catch (t: Throwable) {
                Log.w("ModelViewerActivity", "deferred: engine destroy threw", t)
            }
        }, 250)

        Log.d("ModelViewerActivity", "onDestroy: returning — engine.destroy() deferred 250ms")
    }
}
