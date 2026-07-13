package com.example.surveyingapp.ui.models

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
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
import com.example.surveyingapp.ui.viewpoints.SimpleCoordinatesAdapter
import com.google.android.filament.*
import com.google.android.filament.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ModelViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelViewerBinding
    private lateinit var surfaceView: SurfaceView
    // Nullable until initialization completes on the main thread after async setup
    private var newModelViewer: ModelViewer? = null
    // Make choreographer nullable and guard calls (safer across lifecycle transitions)
    private var choreographer: Choreographer? = null

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
    private var indirectLightEnabled = true
    //private var sunLightEntity: Int = 0

    // Controls menu toggle
    private var controlsExpanded = false

    // Gizmo setup
    private var gizmoEntity: Int = 0
    private var gizmoVertexBuffer: VertexBuffer? = null
    private var gizmoIndexBuffer: IndexBuffer? = null
    private var gizmoVisible = true

    private var offsetMarkerEntity: Int = 0
    private var offsetMarkerVertexBuffer: VertexBuffer? = null
    private var offsetMarkerIndexBuffer: IndexBuffer? = null
    private var offsetMarkerVisible = false

    // fixes a crash when material isn't unloaded properly (onDestroy)
    private var gizmoMaterial: Material? = null
    private var gizmoMaterialInstance: MaterialInstance? = null

    // New camera setup
    private val orbitTarget = FloatArray(3)
    private var orbitDistance = 2.5f
    private var orbitYawDeg = 45.0
    private var orbitPitchDeg = 18.0

    private var orbitManipulator: Manipulator? = null
    private var grabPixelAccum = 0.0



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

        private const val ROTATION_SPEED_PER_PXL_RADIANS = 0.01f
        private const val AUTO_ROTATE_DEG_PER_FRAME = 0.5 // Change this to make the rotation go faster or slower

        private val AUTO_ROTATE_PXLS_PER_FRAME = Math.toRadians(AUTO_ROTATE_DEG_PER_FRAME) / ROTATION_SPEED_PER_PXL_RADIANS

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

            AlertDialog.Builder(this)
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
        binding.btnControlsExpand.setOnClickListener {
            if (controlsExpanded)
            {
                collapseControls()
            }
            else
            {
                expandControls()
            }

            controlsExpanded = !controlsExpanded
        }

        binding.btnResetRotation.setOnClickListener { clickedResetView() }
        binding.btnAutoRotate.setOnClickListener { clickedAutoRotate() }
        binding.btnToggleLighting.setOnClickListener { clickedToggleIndirectLight() }
        binding.btnCaptureThumbnail.setOnClickListener { onCaptureThumbnailClicked() }
        binding.btnModelDiagnostics.setOnClickListener { clickedModelDiagnosticsButton() }
        binding.btnToggleAxis.setOnClickListener { clickedToggleGizmo() }
        binding.btnToggleOffset.setOnClickListener { clickedToggleOffsetMarker() }

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

            // Control our own camera
            updateOrbitTargetFromAsset(viewer)
            applyOrbitManipulator(viewer)

            // Create axis at origin
            createAxisGizmo(viewer)

            // Create the origin offset marker
            createOffsetMarker(viewer)

            // Snapshot the unit-cube transform so rotation is always composed on top of it.
            viewer.asset?.let { asset ->
                val tm = viewer.engine.transformManager
                val base = FloatArray(16)
                tm.getTransform(tm.getInstance(asset.root), base)
                baseTransform = base
            }

            hasAppliedThumbnailFraming = true
            indirectLightEnabled = true

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

    private fun applyOrbitManipulator(viewer: ModelViewer, isThumbnailFraming: Boolean = false) {
        // Filament does not have a native way to set up an orbit camera that does not
        // rely on viewer.transformToUnitCube(). Normally, the camera always assumes
        // that the model is already transformed, but we want the model to be accurate to
        // its original position so that we can determine if a model is off-center for AR.

        // Therefore, the only way I found to move the orbit camera was to tap into the fields
        // itself via reflection, set them to public, then replace the "manipulators" with our own.
        // Veryyyyy hacky but maybe there is a better solution Google will give us down the line.

        // See: https://github.com/google/filament/issues/4318
        //      https://github.com/google/filament/discussions/7747
        //      https://stackoverflow.com/questions/79888650/how-to-show-a-model3d-pose-in-a-sceneview-in-android-studio

        val width = if (::surfaceView.isInitialized && surfaceView.width > 0) surfaceView.width else 1
        val height = if (::surfaceView.isInitialized && surfaceView.height > 0) surfaceView.height else 1

        val pitchRad = Math.toRadians(orbitPitchDeg)
        val yawRad = Math.toRadians(orbitYawDeg)

        val cp = kotlin.math.cos(pitchRad).toFloat()
        val sp = kotlin.math.sin(pitchRad).toFloat()
        val sy = kotlin.math.sin(yawRad).toFloat()
        val cy = kotlin.math.cos(yawRad).toFloat()

        val tx = orbitTarget[0]
        val ty = orbitTarget[1]
        val tz = orbitTarget[2]
        val ex = tx + orbitDistance * cp * sy
        val ey = ty + orbitDistance * sp
        val ez = tz + orbitDistance * cp * cy

        val manipulator = Manipulator.Builder()
            .viewport(width, height)
            .targetPosition(tx, ty, tz)
            .orbitHomePosition(ex, ey, ez)
            .upVector(0f, 1f, 0f)
            .orbitSpeed(ROTATION_SPEED_PER_PXL_RADIANS, ROTATION_SPEED_PER_PXL_RADIANS)
            .build(Manipulator.Mode.ORBIT)

        try {
            val mvClass = ModelViewer::class.java

            // cameraManipulator handles the orbit camera
            // gestureDetector handles touch input for the cameraManipulator
            val mField = mvClass.getDeclaredField("cameraManipulator").apply { isAccessible = true }
            val gField = mvClass.getDeclaredField("gestureDetector").apply { isAccessible = true }

            mField.set(viewer, manipulator)
            gField.set(viewer, GestureDetector(surfaceView, manipulator))
            orbitManipulator = manipulator

        } catch (t: Throwable) {
            Log.e("ModelViewerActivity", "Failed to install orbit manipulator", t)
        }

        if (isThumbnailFraming) {

            viewer.camera.lookAt(
                ex.toDouble(),
                ey.toDouble(),
                ez.toDouble(),
                tx.toDouble(),
                ty.toDouble(),
                tz.toDouble(),
                0.0,
                1.0,
                0.0
            )

            // If shown when actually viewing the model, causes stretched model bug
            viewer.camera.setProjection(
                35.0,
                (width / height).toDouble(),
                0.05,
                100.0,
                Camera.Fov.VERTICAL
            )

        }

    }

    private fun createGizmoMaterial(engine: Engine): MaterialInstance {
        val buffer = loadAsset("unlit.filamat")!!

        val mat = Material.Builder()
            .payload(buffer, buffer.remaining())
            .build(engine)

        // Create instance for cleanup later
        gizmoMaterial = mat

        val instance = mat.createInstance()
        gizmoMaterialInstance = instance

        return instance
    }

    // Appends a box with a solid color to the shared vertex/index lists
    // Originally a part of createAxisGizmo, now reused
    // 8 verts, 36 indices
    private fun appendBox(
        vertexList: MutableList<Float>,
        indexList: MutableList<Short>,
        baseIndex: Short,
        minX: Float, maxX: Float,
        minY: Float, maxY: Float,
        minZ: Float, maxZ: Float,
        r: Float, g: Float, b: Float
    ): Short {

        val verts = arrayOf(
            floatArrayOf(minX, minY, minZ),
            floatArrayOf(maxX, minY, minZ),
            floatArrayOf(maxX, maxY, minZ),
            floatArrayOf(minX, maxY, minZ),
            floatArrayOf(minX, minY, maxZ),
            floatArrayOf(maxX, minY, maxZ),
            floatArrayOf(maxX, maxY, maxZ),
            floatArrayOf(minX, maxY, maxZ)
        )

        for (v in verts) {
            vertexList.add(v[0])
            vertexList.add(v[1])
            vertexList.add(v[2])
            vertexList.add(r)
            vertexList.add(g)
            vertexList.add(b)
        }

        val inds = shortArrayOf(
            0, 1, 2,  0, 2, 3,   // front
            4, 6, 5,  4, 7, 6,   // back
            0, 4, 5,  0, 5, 1,   // bottom
            3, 2, 6,  3, 6, 7,   // top
            0, 3, 7,  0, 7, 4,   // left
            1, 5, 6,  1, 6, 2    // right
        )

        for (i in inds) {
            indexList.add((baseIndex + i).toShort())
        }

        return (baseIndex + 8).toShort()
    }

    // Originally a part of createAxisGizmo, now reused
    private fun buildColoredRenderable(
        engine: Engine,
        vertices: FloatArray,
        indices: ShortArray,
        material: MaterialInstance,
        scale: Float = 1f,
        position: FloatArray? = null
    ): Triple<Int, VertexBuffer, IndexBuffer> {
        val vertexBuffer = VertexBuffer.Builder()
            .vertexCount(vertices.size / 6)
            .bufferCount(1)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION, 0,
                VertexBuffer.AttributeType.FLOAT3, 0, 24
            )
            .attribute(
                VertexBuffer.VertexAttribute.COLOR, 0,
                VertexBuffer.AttributeType.FLOAT3, 12, 24
            )
            .build(engine)
        vertexBuffer.setBufferAt(engine, 0, FloatBuffer.wrap(vertices))

        val indexBuffer = IndexBuffer.Builder()
            .indexCount(indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        indexBuffer.setBuffer(engine, ShortBuffer.wrap(indices))

        val entity = EntityManager.get().create()

        RenderableManager.Builder(1)
            .geometry(
                0,
                RenderableManager.PrimitiveType.TRIANGLES,
                vertexBuffer,
                indexBuffer
            )
            .material(0, material)
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .build(engine, entity)

        // Attach a transform = translate(position) * scale(scale): a local point p maps to
        // position + scale * p, so the shape scales about its own local origin, then moves into
        // place. Column-major 4x4; only touched when it differs from identity.
        if (scale != 1f || position != null) {
            val transform = FloatArray(16)
            transform[0] = scale
            transform[5] = scale
            transform[10] = scale
            transform[15] = 1f
            if (position != null) {
                transform[12] = position[0]
                transform[13] = position[1]
                transform[14] = position[2]
            }
            val tm = engine.transformManager
            if (!tm.hasComponent(entity)) tm.create(entity)
            tm.setTransform(tm.getInstance(entity), transform)
        }

        return Triple(entity, vertexBuffer, indexBuffer)
    }

    // Uniform scale that makes origin markers track the model's size, using the largest
    // bounding-box half-extent so the gizmo/marker stay proportional across big and small models.
    private fun boundingBoxScale(viewer: ModelViewer): Float {
        val h = viewer.asset?.boundingBox?.halfExtent ?: return 1f
        return maxOf(abs(h[0]), abs(h[1]), abs(h[2])).coerceAtLeast(0.01f)
    }

    private fun createAxisGizmo(viewer: ModelViewer) {
        val engine = viewer.engine

        val thickness = 0.03f
        val half = thickness / 2f

        val vertexList = mutableListOf<Float>()
        val indexList = mutableListOf<Short>()
        var baseIndex: Short = 0


        // X axis: red
        baseIndex = appendBox(vertexList, indexList, baseIndex, 0f, 1f, -half, half, -half, half, 1f, 0f, 0f)

        // Y axis: green
        baseIndex = appendBox(vertexList, indexList, baseIndex, -half, half, 0f, 1f, -half, half, 0f, 1f, 0f)

        // Z axis: blue (last box — return value unused)
        appendBox(vertexList, indexList, baseIndex, -half, half, -half, half, 0f, 1f, 0f, 0f, 1f)

        val material = createGizmoMaterial(engine)

        // Scale the rods to the model so the axes stay proportional
        val (entity, vertexBuffer, indexBuffer) = buildColoredRenderable(
            engine, vertexList.toFloatArray(), indexList.toShortArray(), material,
            scale = boundingBoxScale(viewer)
        )

        gizmoVertexBuffer = vertexBuffer
        gizmoIndexBuffer = indexBuffer

        viewer.scene.addEntity(entity)
        gizmoEntity = entity
    }

    private fun createOffsetMarker(viewer: ModelViewer) {
        val engine = viewer.engine
        val center = viewer.asset?.boundingBox?.center ?: return

        // Reuse the gizmo's unlit vertex-color material
        // May change later for axis gizmo?
        val material = gizmoMaterialInstance ?: return

        val half = 0.05f
        val vertexList = mutableListOf<Float>()
        val indexList = mutableListOf<Short>()

        appendBox(
            vertexList, indexList, 0,
            -half, half, -half, half, -half, half,
            1f, 0.5f, 0f
        )

        val (entity, vertexBuffer, indexBuffer) = buildColoredRenderable(
            engine, vertexList.toFloatArray(), indexList.toShortArray(), material,
            scale = boundingBoxScale(viewer),
            position = center
        )
        offsetMarkerVertexBuffer = vertexBuffer
        offsetMarkerIndexBuffer = indexBuffer
        offsetMarkerEntity = entity
    }

    private fun updateOrbitTargetFromAsset(viewer: ModelViewer) {
        val asset = viewer.asset ?: return
        val box = asset.boundingBox

        val c = box.center
        val h = box.halfExtent

        orbitTarget[0] = c[0]
        orbitTarget[1] = c[1]
        orbitTarget[2] = c[2]

        val radius = maxOf(h[0], h[1], h[2]).coerceAtLeast(0.01f)
        orbitDistance = radius * 3.0f
    }

    // ── Frame loop ────────────────────────────────────────────────────────────

    private val frameCallback = object : Choreographer.FrameCallback {
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

                 if (autoRotate) {
                     // Since Filament is kinda barebones, we simulate touches to make
                     // a working auto rotate feature based on the camera instead of rotating
                     // the entire model itself (which caused issues in earlier builds).
                     // The manipulator has functions that allows us to do this, although
                     // this means any input is overriden if interacting with the surface view

                     orbitManipulator?.let { m ->
                         grabPixelAccum += AUTO_ROTATE_PXLS_PER_FRAME
                         val pixels = grabPixelAccum.toInt()
                         if (pixels != 0) {
                             m.grabBegin(0, 0, false)
                             m.grabUpdate(pixels, 0)
                             m.grabEnd()
                             grabPixelAccum -= pixels.toDouble()
                         }
                     }
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
        binding.btnCaptureThumbnail.setImageResource(R.drawable.ic_refresh_24_white)
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
                binding.btnCaptureThumbnail.setImageResource(R.drawable.ic_camera_white)
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
                    binding.btnCaptureThumbnail.setImageResource(R.drawable.ic_camera_white)
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

    private fun expandControls() {
        Log.d("ModelViewerActivity", "expandControls: showing controls")
        binding.btnControlsExpand.setImageResource(R.drawable.ic_zoom_out)
        binding.btnAutoRotate.visibility = View.VISIBLE
        binding.btnToggleLighting.visibility = View.VISIBLE
        binding.btnResetRotation.visibility = View.VISIBLE
        binding.btnModelDiagnostics.visibility = View.VISIBLE
        binding.btnToggleAxis.visibility = View.VISIBLE
        binding.btnToggleOffset.visibility = View.VISIBLE
    }

    private fun collapseControls() {
        Log.d("ModelViewerActivity", "collapseControls: hiding controls")
        binding.btnControlsExpand.setImageResource(R.drawable.ic_zoom_in)
        binding.btnAutoRotate.visibility = View.INVISIBLE
        binding.btnToggleLighting.visibility = View.INVISIBLE
        binding.btnResetRotation.visibility = View.INVISIBLE
        binding.btnModelDiagnostics.visibility = View.INVISIBLE
        binding.btnToggleAxis.visibility = View.INVISIBLE
        binding.btnToggleOffset.visibility = View.INVISIBLE
    }

    private fun clickedResetView() {
        if (autoRotate) {
            autoRotate = false
            binding.btnAutoRotate.text = getString(R.string.auto_rotate)
        }
        grabPixelAccum = 0.0

        val manip = orbitManipulator ?: return
        manip.jumpToBookmark(manip.homeBookmark) // homeBookmark has original position, very useful!
    }

    private fun clickedAutoRotate() {
        autoRotate = !autoRotate
        binding.btnAutoRotate.text = if (autoRotate) "Stop Rotation" else getString(R.string.auto_rotate)
        grabPixelAccum = 0.0
        Log.d("ModelViewerActivity", "autoRotate set to $autoRotate")
    }

    private fun clickedToggleIndirectLight() {
        val viewer = newModelViewer ?: return
        if (viewer.scene.indirectLight == null) return  // no IBL to toggle

        indirectLightEnabled = !indirectLightEnabled
        if (indirectLightEnabled) {
            viewer.scene.indirectLight?.intensity = 50_000f
            binding.btnToggleLighting.text = getString(R.string.lighting_on)
        } else {
            viewer.scene.indirectLight?.intensity = 0f
            binding.btnToggleLighting.text = getString(R.string.lighting_off)
        }
        Log.d("ModelViewerActivity", "dynamicLighting set to $indirectLightEnabled")
    }

    private fun clickedToggleGizmo() {
        val viewer = newModelViewer ?: return
        if (gizmoEntity == 0) return  // gizmo not built yet

        gizmoVisible = !gizmoVisible
        if (gizmoVisible) {
            viewer.scene.addEntity(gizmoEntity)
            binding.btnToggleAxis.text = getString(R.string.axis_on)
        } else {
            viewer.scene.removeEntity(gizmoEntity)
            binding.btnToggleAxis.text = getString(R.string.axis_off)
        }
        Log.d("ModelViewerActivity", "gizmoVisible set to $gizmoVisible")
    }

    private fun clickedToggleOffsetMarker() {
        val viewer = newModelViewer ?: return
        if (offsetMarkerEntity == 0) return  // marker not built yet

        offsetMarkerVisible = !offsetMarkerVisible
        if (offsetMarkerVisible) {
            viewer.scene.addEntity(offsetMarkerEntity)
            binding.btnToggleOffset.text = getString(R.string.offset_on)
        } else {
            viewer.scene.removeEntity(offsetMarkerEntity)
            binding.btnToggleOffset.text = getString(R.string.offset_off)
        }
        Log.d("ModelViewerActivity", "offsetMarkerVisible set to $offsetMarkerVisible")
    }

    private fun clickedModelDiagnosticsButton() {
        val asset = newModelViewer?.asset
        if (asset == null) {
            Toast.makeText(this, R.string.model_diag_not_ready, Toast.LENGTH_SHORT).show()
            return
        }

        val diagnostics = computeModelDiagnostics(asset.boundingBox)

        try {
            ModelDiagnosticsDialogFragment.newInstance(diagnostics).show(supportFragmentManager, "ModelDiagnosticsDialog")
        } catch (e: Exception) {
            Log.e("ModelViewerActivity", "Failed to show diagnostics dialog", e)
        }
    }

    private fun computeModelDiagnostics(box: Box): ModelDiagnostics {
        val center = box.center
        val halfExtent = box.halfExtent

        // coordinates for glb is:
        // Y-up
        // X = width
        // Y = height
        // Z = depth
        val width = 2f * abs(halfExtent[0])
        val height = 2f * abs(halfExtent[1])
        val depth = 2f * abs(halfExtent[2])

        val fileSizeBytes = intent.getStringExtra(EXTRA_MODEL_PATH)?.let { File(it).length() } ?: 0L

        // Distance from the model's local origin (0,0,0) to the center
        val originOffset = sqrt(
            center[0] * center[0] + center[1] * center[1] + center[2] * center[2]
        )

        // Axis-aligned bounds of the model in its local space
        val minX = center[0] - halfExtent[0]; val maxX = center[0] + halfExtent[0]
        val minY = center[1] - halfExtent[1]; val maxY = center[1] + halfExtent[1]
        val minZ = center[2] - halfExtent[2]; val maxZ = center[2] + halfExtent[2]

        // Shortest distance from the local origin to the model's surface (0 if inside the box)
        val originDistanceFromModel = distanceFromOriginToBox(minX, maxX, minY, maxY, minZ, maxZ)

        return ModelDiagnostics(
            fileSizeBytes = fileSizeBytes,
            widthMeters = width,
            depthMeters = depth,
            heightMeters = height,
            originOffsetMeters = originOffset,
            originDescription = describeOriginLocation(minY, maxY, originDistanceFromModel),
            verticalPlacement = describeVerticalPlacement(minY, height),
            arReadiness = assessArReadiness(originOffset, minY, width, height, depth)
        )
    }

    private fun distanceFromOriginToBox(
        minX: Float, maxX: Float,
        minY: Float, maxY: Float,
        minZ: Float, maxZ: Float
    ): Float {

        val dx = maxOf(minX, 0f, -maxX)
        val dy = maxOf(minY, 0f, -maxY)
        val dz = maxOf(minZ, 0f, -maxZ)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun describeOriginLocation(minY: Float, maxY: Float, distanceFromModel: Float): String {
        val span = maxY - minY
        val category = if (span <= 0f) {
            "Undetermined"
        }
        else {
            val t = (0f - minY) / span // 0 = base, 1 = top

            // switch case!!!
            when {
                t < -0.05f || t > 1.05f -> "Outside the model bounds"
                t < 0.2f -> "Near base of model"
                t > 0.8f -> "Near top of model"
                t in 0.4f..0.6f -> "Near center of model"
                t < 0.5f -> "Below center of model"
                else -> "Above center of model"
            }
        }

        if (distanceFromModel <= 1e-4f) {
            return "$category (origin is inside the model)"
        }
        else {
            return String.format(Locale.US, "%s (%.2f m from the model)", category, distanceFromModel)
        }
    }

    private fun describeVerticalPlacement(minY: Float, height: Float): String {
        val tolerance = maxOf(0.02f, height * 0.02f)
        val distance = abs(minY)

        // switch case
        when {
            distance <= tolerance -> return "Model sits on the anchor point"
            minY > 0f -> return String.format(Locale.US, "Model floats %.2f m above the anchor point", distance)
            else -> return String.format(Locale.US, "Model sinks %.2f m below the anchor point", distance)
        }
    }

    // Might need to change later
    private fun assessArReadiness(
        originOffset: Float,
        minY: Float,
        width: Float,
        height: Float,
        depth: Float
    ): String {
        val largestDim = maxOf(width, height, depth)
        if (largestDim <= 0f) return "Unknown"

        val tolerance = maxOf(0.02f, height * 0.02f)
        val sitsOnAnchor = abs(minY) <= tolerance
        val offsetRatio = originOffset / largestDim

        // AR models are typically between a few centimeters and a few meters across
        // Context on what AR models are being used is unknown
        val plausibleScale = largestDim in 0.05f..10f

        when {
            sitsOnAnchor && offsetRatio < 0.1f && plausibleScale -> return "Good"
            offsetRatio < 0.35f && plausibleScale -> return "Fair"
            else -> return "Needs adjustment"
        }
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
        choreographer = Choreographer.getInstance()
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

            if (gizmoEntity != 0) {
                viewer.scene.removeEntity(gizmoEntity)
                viewer.engine.destroyEntity(gizmoEntity)
                EntityManager.get().destroy(gizmoEntity)
                gizmoEntity = 0
            }

            if (offsetMarkerEntity != 0) {
                viewer.scene.removeEntity(offsetMarkerEntity)
                viewer.engine.destroyEntity(offsetMarkerEntity)
                EntityManager.get().destroy(offsetMarkerEntity)
                offsetMarkerEntity = 0
            }

            // NEW
            // Note: the offset marker shares gizmoMaterialInstance, so it is destroyed once here.
            gizmoMaterialInstance?.let { viewer.engine.destroyMaterialInstance(it) }
            gizmoMaterialInstance = null
            gizmoMaterial?.let { viewer.engine.destroyMaterial(it) }
            gizmoMaterial = null
            gizmoVertexBuffer?.let { viewer.engine.destroyVertexBuffer(it) }
            gizmoVertexBuffer = null
            gizmoIndexBuffer?.let { viewer.engine.destroyIndexBuffer(it) }
            gizmoIndexBuffer = null
            offsetMarkerVertexBuffer?.let { viewer.engine.destroyVertexBuffer(it) }
            offsetMarkerVertexBuffer = null
            offsetMarkerIndexBuffer?.let { viewer.engine.destroyIndexBuffer(it) }
            offsetMarkerIndexBuffer = null

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
