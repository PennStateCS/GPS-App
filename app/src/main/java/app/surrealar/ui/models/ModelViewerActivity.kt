package app.surrealar.ui.models

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import app.surrealar.databinding.ActivityModelViewerBinding
import app.surrealar.R
import app.surrealar.domain.repository.ModelRepository
import app.surrealar.ui.models.readiness.Bounds
import app.surrealar.ui.models.readiness.FixAction
import app.surrealar.ui.models.readiness.ModelFacts
import app.surrealar.ui.models.readiness.ModelReadinessRepository
import app.surrealar.ui.models.readiness.ReadinessCheck
import app.surrealar.ui.models.readiness.ReadinessHost
import app.surrealar.ui.models.readiness.ReadinessStyle
import app.surrealar.ui.models.readiness.ReadinessUiState
import app.surrealar.ui.openinar.Vec3
import app.surrealar.ui.viewpoints.SimpleCoordinatesAdapter
import app.surrealar.util.DiagnosticsLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.google.android.filament.*
import com.google.android.filament.FilamentChannelDepth
import com.google.android.filament.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

@AndroidEntryPoint
class ModelViewerActivity : AppCompatActivity(), ReadinessHost {

    @Inject lateinit var modelRepository: ModelRepository
    @Inject lateinit var readinessRepository: ModelReadinessRepository

    // AR readiness checklist state (rendered by ModelDiagnosticsDialogFragment).
    private val _readinessState = MutableStateFlow(ReadinessUiState())
    override val readinessState: StateFlow<ReadinessUiState> = _readinessState.asStateFlow()
    private var readinessJob: Job? = null
    private val modelId: String? by lazy { intent.getStringExtra(EXTRA_MODEL_ID) }
    /** Filament could not load the file; readiness then reports loadedByRenderer = false. */
    private var rendererLoadFailed = false
    /** Open the checklist once the model has loaded (EXTRA_OPEN_READINESS, first launch only). */
    private var autoOpenReadinessPending = false

    private lateinit var binding: ActivityModelViewerBinding
    private lateinit var surfaceView: SurfaceView
    // Nullable until initialization completes on the main thread after async setup
    private var newModelViewer: ModelViewer? = null
    // Make choreographer nullable and guard calls (safer across lifecycle transitions)
    private var choreographer: Choreographer? = null

    private var thumbnailExists = false
    private var thumbnailCaptureScheduled = false
    private var modelReadyForThumbnail = false

    private var modelPresented = false
    private var presenceProbeInFlight = false
    private var presenceProbeAttempts = 0
    private var presenceProbeBitmap: Bitmap? = null
    private val presenceProbePixels = IntArray(PRESENCE_PROBE_SIZE * PRESENCE_PROBE_SIZE)
    private var viewerReadyAtMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

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
    private lateinit var controlsAdapter: ModelViewerControlsAdapter

    // Gizmo setup
    private var gizmoEntity: Int = 0
    private var gizmoVertexBuffer: VertexBuffer? = null
    private var gizmoIndexBuffer: IndexBuffer? = null
    private var gizmoVisible = false

    private var offsetMarkerEntity: Int = 0
    private var offsetMarkerVertexBuffer: VertexBuffer? = null
    private var offsetMarkerIndexBuffer: IndexBuffer? = null
    private var offsetMarkerVisible = false

    private var boundingBoxEntity: Int = 0
    private var boundingBoxVertexBuffer: VertexBuffer? = null
    private var boundingBoxIndexBuffer: IndexBuffer? = null
    private var boundingBoxVisible = false

    private var gridEntity: Int = 0
    private var gridVertexBuffer: VertexBuffer? = null
    private var gridIndexBuffer: IndexBuffer? = null
    private var gridVisible = false

    private var overlayAlwaysOnTop = true

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
        private const val EXTRA_MODEL_ID = "model_id"
        private const val EXTRA_OPEN_READINESS = "open_readiness"

        private const val FRAMES_TO_SETTLE = 60
        private const val THUMBNAIL_SIZE = 256
        private const val CAPTURE_MAX_ATTEMPTS = 20
        private const val CAPTURE_RETRY_DELAY_MS = 250L

        private const val PRESENCE_PROBE_SIZE = 64
        private const val PRESENCE_PROBE_STEP = 2
        private const val PRESENCE_MAX_PROBES = 40
        private const val REVEAL_TIMEOUT_MS = 10_000L

        private const val BLACK_LEVEL = 12
        // How far (0-255, any channel) a probe pixel must differ from the background to count
        // as model content.
        private const val BACKGROUND_TOLERANCE = 10

        // Viewer look: light-grey background, image-based light at full and "Disable Light" levels.
        private const val BACKGROUND_GREY = 0.93f
        private const val IBL_INTENSITY = 30_000f
        private const val IBL_DIM_INTENSITY = 8_000f

        // Minimum nanoseconds between consecutive renders. Set to ~33_333_333ns (30 FPS) to
        // reduce CPU on slower devices / emulators and avoid skipped-frame churn.
        private const val MIN_RENDER_INTERVAL_NS = 33_333_333L

        // Render channel for the overlay geometry (gizmo, origin marker, bounding box, grid)
        private const val DEFAULT_CHANNEL = 2
        private const val OVERLAY_CHANNEL = 3

        private const val ROTATION_SPEED_PER_PXL_RADIANS = 0.01f
        private const val AUTO_ROTATE_DEG_PER_FRAME = 0.5 // Change this to make the rotation go faster or slower

        private val AUTO_ROTATE_PXLS_PER_FRAME = Math.toRadians(AUTO_ROTATE_DEG_PER_FRAME) / ROTATION_SPEED_PER_PXL_RADIANS

        /**
         * [modelId] lets the readiness checklist include the points linking the model (and apply
         * placement fixes); [openReadiness] opens the checklist as soon as the model has loaded.
         */
        fun newIntent(
            context: Context,
            modelPath: String,
            modelName: String,
            modelId: String? = null,
            openReadiness: Boolean = false,
        ): Intent {
            Utils.init()
            return Intent(context, ModelViewerActivity::class.java).apply {
                putExtra(EXTRA_MODEL_PATH, modelPath)
                putExtra(EXTRA_MODEL_NAME, modelName)
                if (modelId != null) putExtra(EXTRA_MODEL_ID, modelId)
                putExtra(EXTRA_OPEN_READINESS, openReadiness)
            }
        }
    }

    // Track when we last rendered to enforce MIN_RENDER_INTERVAL_NS.
    private var lastRenderTimeNs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app.surrealar.ui.common.SystemBarInsets.apply(binding.root, binding.toolbar)   // edge-to-edge

        autoOpenReadinessPending = savedInstanceState == null &&
            intent.getBooleanExtra(EXTRA_OPEN_READINESS, false)

        // Check if the device supports Filament for 3D rendering; if not, show an error message and skip setup.
        if (!supportsFilamentViewer()) {
            showLoadFailure()

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
        val modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: "3D Model"
        binding.textModelName.text = modelName;
        binding.textModelFilename.text = intent.getStringExtra(EXTRA_MODEL_PATH)?.substringAfterLast('/')?.substringAfterLast('\\') ?: "Unknown file";

        // Tell the user which model the spinner is waiting on
        binding.textLoading.text = getString(R.string.now_loading_model, modelName)
        binding.textLoading.visibility = View.VISIBLE

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

        binding.btnCaptureThumbnail.setOnClickListener { onCaptureThumbnailClicked() }

        // Binding the new model viewer controls viewer
        controlsAdapter = ModelViewerControlsAdapter(this) { control ->
            when (control) {
                ModelViewerControl.RESET_ROTATION -> clickedResetView()
                ModelViewerControl.AUTO_ROTATE -> clickedAutoRotate()
                ModelViewerControl.LIGHTING -> clickedToggleIndirectLight()
                ModelViewerControl.DIAGNOSTICS -> clickedModelDiagnosticsButton()
                ModelViewerControl.AXIS -> clickedToggleGizmo()
                ModelViewerControl.OFFSET -> clickedToggleOffsetMarker()
                ModelViewerControl.BOUNDING_BOX -> clickedToggleBoundingBox()
                ModelViewerControl.GRID -> clickedToggleGridPlane()
                ModelViewerControl.OVERLAY_ON_TOP -> clickedToggleOverlayOnTop()
            }
        }

        binding.rvViewerControls.itemAnimator = null
        binding.rvViewerControls.adapter = controlsAdapter

        // Load the model viewer surface view
        if (!setupModelViewer())
        {
            Log.e("ModelViewerActivity", "Something went wrong while loading the model viewer.")
            showLoadFailure()
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
            // .gltf: read the .bin/texture files here too, so the loader callback doesn't hit
            // the disk on the main thread.
            val gltfResources = if (isGltf(modelFile)) {
                withContext(Dispatchers.IO) { preloadGltfResources(modelFile) }
            } else {
                null
            }

            // Guard: if the activity was destroyed while IO was in flight, abort.
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) return@launch

            if (modelBuffer == null) {
                Log.e("ModelViewerActivity", "Could not read model file: ${modelFile.absolutePath}")
                showLoadFailure()
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

            // PBR Neutral tone mapping keeps base colours close to what the author picked
            // (the default filmic curve washes out and shifts saturated colours).
            viewer.view.colorGrading = ColorGrading.Builder()
                .toneMapper(ToneMapper.PBRNeutral())   // Filament 1.81 name for PBRNeutralToneMapper
                .build(viewer.engine)

            // Solid light-grey background via a colour Skybox (pure white made light models
            // vanish into the background).
            // • skybox = null leaves the background undefined — Filament will not clear those
            //   pixels, so the previous frame bleeds through ("ghost image") as the model rotates.
            // • A colour Skybox is rendered as the background layer every frame, which is the
            //   correct way to get a solid-colour background in Filament.
            viewer.scene.skybox = Skybox.Builder()
                .color(BACKGROUND_GREY, BACKGROUND_GREY, BACKGROUND_GREY, 1f)
                .build(viewer.engine)

            // Also properly re-assign clearOptions.
            // renderer.clearOptions returns a *copy* of the struct; mutating it with .apply
            // without re-assigning is a no-op — the renderer never sees the change.
            val grey = BACKGROUND_GREY.toDouble()
            viewer.renderer.clearOptions = Renderer.ClearOptions().apply {
                clearColor = doubleArrayOf(grey, grey, grey, 1.0)
                clear = true
            }

            // Indirect lighting (buffer already loaded off-thread)
            if (ktxBuffer != null) {
                val ibl = KTX1Loader.createIndirectLight(viewer.engine, ktxBuffer)
                ibl.indirectLight?.intensity = IBL_INTENSITY
                viewer.scene.indirectLight = ibl.indirectLight
            }

            // Load model geometry. .gltf needs a sidecar resolver for texture/bin files.
            val loaded = loadModelIntoViewer(viewer, modelFile, modelBuffer, gltfResources)
            if (!loaded) {
                showLoadFailure()
                return@launch
            }

            // Control our own camera
            updateOrbitTargetFromAsset(viewer)
            applyOrbitManipulator(viewer)

            // Draw the overlays below on top of the model instead of inside it
            applyOverlayDepthClear(viewer, overlayAlwaysOnTop)

            // Create axis at origin
            createAxisGizmo(viewer)

            // Create the origin offset marker
            createOffsetMarker(viewer)

            // Create the model's bounding box wireframe
            createBoundingBox(viewer)

            // Create the ground grid plane beneath the model
            createGridPlane(viewer)

            // Snapshot the unit-cube transform so rotation is always composed on top of it.
            viewer.asset?.let { asset ->
                val tm = viewer.engine.transformManager
                val base = FloatArray(16)
                tm.getTransform(tm.getInstance(asset.root), base)
                baseTransform = base
            }

            hasAppliedThumbnailFraming = true
            indirectLightEnabled = true

            // Start counting seconds since load to prevent infinite loading
            viewerReadyAtMs = SystemClock.uptimeMillis()
            newModelViewer = viewer
            modelReadyForThumbnail = true

            // Evaluate readiness with the rendered bounds (also feeds the AR placement overlay).
            onViewerSettled()
        }

        return true
    }

    private fun isGltf(modelFile: File): Boolean = modelFile.name.lowercase().endsWith(".gltf")

    /**
     * Reads every external buffer/image the .gltf at [modelFile] references (same path-safety
     * rules as [resolveGltfResource]). Call off the main thread. Keys are the URIs as written
     * in the file, which is what the loader callback receives.
     */
    private fun preloadGltfResources(modelFile: File): Map<String, ByteBuffer> {
        return try {
            val baseDir = modelFile.parentFile?.canonicalFile
            GltfResources.externalResourceUris(modelFile.readText())
                .associateWith { uri -> resolveGltfResource(baseDir, uri) }
        } catch (t: Throwable) {
            Log.w("ModelViewerActivity", "Could not pre-read glTF resources", t)
            emptyMap()
        }
    }

    private fun loadModelIntoViewer(
        viewer: ModelViewer,
        modelFile: File,
        modelBuffer: ByteBuffer,
        gltfResources: Map<String, ByteBuffer>? = null
    ): Boolean {
        return try {
            if (isGltf(modelFile)) {
                val baseDir = modelFile.parentFile?.canonicalFile
                viewer.loadModelGltf(modelBuffer) { uriString ->
                    gltfResources?.get(uriString) ?: run {
                        // Not in the pre-read set (unusual reference) — read it now.
                        Log.w("ModelViewerActivity", "glTF resource not pre-read, loading on demand: $uriString")
                        resolveGltfResource(baseDir, uriString)
                    }
                }
            } else {
                viewer.loadModelGlb(modelBuffer)
            }
            true
        } catch (e: Throwable) {
            Log.e("ModelViewerActivity", "Failed to load model: ${modelFile.absolutePath}", e)
            DiagnosticsLogger.e("MODEL", "viewer load failed: ${e.javaClass.simpleName} ${e.message}")
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

    // Controls whether everything built by buildColoredRenderable draws on top of the model
    private fun applyOverlayDepthClear(viewer: ModelViewer, enabled: Boolean) {
        overlayAlwaysOnTop = try {
            FilamentChannelDepth.setChannelDepthClearEnabled(viewer.view, OVERLAY_CHANNEL, enabled)
            enabled
        } catch (t: Throwable) {
            Log.e("ModelViewerActivity", "Failed to set depth clear for overlay channel", t)
            false
        }

        controlsAdapter.setLabel(ModelViewerControl.OVERLAY_ON_TOP, getString(if (overlayAlwaysOnTop) R.string.overlay_top_on else R.string.overlay_top_off))
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

    // Originally a part of createAxisGizmo, now reused.
    // setting the channel allows for models to be rendered on top of other models in the scene
    private fun buildColoredRenderable(
        engine: Engine,
        vertices: FloatArray,
        indices: ShortArray,
        material: MaterialInstance,
        scale: Float = 1f,
        position: FloatArray? = null,
        channel: Int = OVERLAY_CHANNEL
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
            .channel(channel)
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

    private fun createBoundingBox(viewer: ModelViewer) {
        val engine = viewer.engine
        val box = viewer.asset?.boundingBox ?: return

        // Reuse the gizmo's unlit vertex-color material
        // May change later for axis gizmo?
        val material = gizmoMaterialInstance ?: return

        val center = box.center
        val halfExtent = box.halfExtent

        val minX = center[0] - abs(halfExtent[0])
        val maxX = center[0] + abs(halfExtent[0])

        val minY = center[1] - abs(halfExtent[1])
        val maxY = center[1] + abs(halfExtent[1])

        val minZ = center[2] - abs(halfExtent[2])
        val maxZ = center[2] + abs(halfExtent[2])

        // Thin rods sized proportional to the model, similar to axis gizmo
        val half = 0.015f * boundingBoxScale(viewer)

        // The color orange (for now, it is the same as origin offset)
        val r = 1f; val g = 0.5f; val b = 0f

        val vertexList = mutableListOf<Float>()
        val indexList = mutableListOf<Short>()
        var baseIndex: Short = 0

        // 4 edges running along X
        for (y in listOf(minY, maxY)) {
            for (z in listOf(minZ, maxZ)) {
                baseIndex = appendBox(vertexList, indexList, baseIndex,
                    minX, maxX, y - half, y + half, z - half, z + half, r, g, b)
            }
        }

        // 4 edges running along Y
        for (x in listOf(minX, maxX)) {
            for (z in listOf(minZ, maxZ)) {
                baseIndex = appendBox(vertexList, indexList, baseIndex,
                    x - half, x + half, minY, maxY, z - half, z + half, r, g, b)
            }
        }

        // 4 edges running along Z
        for (x in listOf(minX, maxX)) {
            for (y in listOf(minY, maxY)) {
                baseIndex = appendBox(vertexList, indexList, baseIndex,
                    x - half, x + half, y - half, y + half, minZ, maxZ, r, g, b)
            }
        }

        // Vertices are already in the asset's local space, so no extra scale/translate
        val (entity, vertexBuffer, indexBuffer) = buildColoredRenderable(
            engine, vertexList.toFloatArray(), indexList.toShortArray(), material
        )

        boundingBoxVertexBuffer = vertexBuffer
        boundingBoxIndexBuffer = indexBuffer
        boundingBoxEntity = entity
    }

    // There is no official way to create a grid plane on Filament, so we make our own
    // TODO: find a way to do this better, currently grid plane is "local" to the model
    private fun createGridPlane(viewer: ModelViewer) {
        val engine = viewer.engine
        val box = viewer.asset?.boundingBox ?: return

        // Reuse the gizmo's unlit vertex-color material
        // May change later for axis gizmo?
        val material = gizmoMaterialInstance ?: return

        val center = box.center
        val halfExtent = box.halfExtent

        val width = 2f * abs(halfExtent[0])   // X footprint
        val depth = 2f * abs(halfExtent[2])   // Z footprint
        val minY = center[1] - abs(halfExtent[1])   // model's lowest point -> grid height

        val scale = boundingBoxScale(viewer)
        val halfSize = (0.75f * maxOf(width, depth)).coerceAtLeast(scale)

        // Center the grid on the model's footprint (not origin offset)
        val minGX = center[0] - halfSize; val maxGX = center[0] + halfSize
        val minGZ = center[2] - halfSize; val maxGZ = center[2] + halfSize

        val divisions = 10
        val step = (2f * halfSize) / divisions

        // Thin rod for each line
        val half = 0.004f * scale

        // Mid grey - visible against the light-grey background
        val r = 0.5f; val g = 0.5f; val b = 0.5f

        val vertexList = mutableListOf<Float>()
        val indexList = mutableListOf<Short>()
        var baseIndex: Short = 0

        // Lines running along X (stepping across Z)
        for (i in 0..divisions) {
            val z = minGZ + i * step
            baseIndex = appendBox(vertexList, indexList, baseIndex,
                minGX, maxGX, minY - half, minY + half, z - half, z + half, r, g, b)
        }
        // Lines running along Z (stepping across X)
        for (i in 0..divisions) {
            val x = minGX + i * step
            baseIndex = appendBox(vertexList, indexList, baseIndex,
                x - half, x + half, minY - half, minY + half, minGZ, maxGZ, r, g, b)
        }

        // Vertices are already in the asset's local space, so no extra scale/translate
        val (entity, vertexBuffer, indexBuffer) = buildColoredRenderable(
            engine, vertexList.toFloatArray(), indexList.toShortArray(), material
        )
        gridVertexBuffer = vertexBuffer
        gridIndexBuffer = indexBuffer
        gridEntity = entity
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
            if (last != 0L && modelPresented) {
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

                 if (!modelPresented) {
                     updateLoadingState(viewer)
                 }
             }
             choreographer?.postFrameCallback(this)
         }
     }

    // ── Loading reveal ────────────────────────────────────────────────────────
    // Originally, I was gonna use FilamentAsset.popRenderables() to detect models,
    // but we get a nasty crash trying to do that (SIGABRT -> "jarray was NULL")


    private fun updateLoadingState(viewer: ModelViewer) {
        if (SystemClock.uptimeMillis() - viewerReadyAtMs > REVEAL_TIMEOUT_MS) {
            revealModel("timed out waiting for a painted frame")
            return
        }

        if (presenceProbeInFlight) return
        if (viewer.progress < 1f) return

        probeSurfaceForContent()
    }

    // Copies the surface into a small bitmap to find out whether Filament has actually put
    // anything on screen yet, we scale the surface down to make checking cheap
    private fun probeSurfaceForContent() {
        val w = surfaceView.width
        val h = surfaceView.height
        if (w <= 0 || h <= 0) return
        if (!surfaceView.holder.surface.isValid) return

        val probe = presenceProbeBitmap ?: Bitmap.createBitmap(PRESENCE_PROBE_SIZE, PRESENCE_PROBE_SIZE, Bitmap.Config.ARGB_8888).also { presenceProbeBitmap = it }

        presenceProbeInFlight = true
        presenceProbeAttempts++

        try {
            PixelCopy.request(surfaceView, probe, { result ->
                presenceProbeInFlight = false
                when {
                    result == PixelCopy.SUCCESS -> {
                        if (probeHasModelContent(probe)) {
                            revealModel("model content on surface")
                        } else if (presenceProbeAttempts >= PRESENCE_MAX_PROBES) {
                            revealModel("no content after $PRESENCE_MAX_PROBES probes")
                        }
                    }

                    // Filament has not queued a buffer yet, wait it out and try again
                    result == PixelCopy.ERROR_SOURCE_NO_DATA -> Unit

                    else -> {
                        Log.w("ModelViewerActivity", "Reveal probe failed: result = $result")
                        if (presenceProbeAttempts >= PRESENCE_MAX_PROBES) {
                            revealModel("PixelCopy kept failing (result=$result)")
                        }
                    }
                }
            }, mainHandler)
        } catch (t: Throwable) {
            presenceProbeInFlight = false
            Log.w("ModelViewerActivity", "Reveal probe threw", t)
            if (presenceProbeAttempts >= PRESENCE_MAX_PROBES) {
                revealModel("PixelCopy kept throwing")
            }
        }
    }

    // True once the frame holds something other than the background. The background colour
    // is read from the top-left corner (the model is framed in the centre); a black corner
    // means Filament hasn't painted the surface yet.
    private fun probeHasModelContent(bmp: Bitmap): Boolean {
        val size = PRESENCE_PROBE_SIZE
        if (bmp.width != size || bmp.height != size) return false

        bmp.getPixels(presenceProbePixels, 0, size, 0, 0, size, size)

        val bg = presenceProbePixels[0]
        val bgR = (bg shr 16) and 0xFF
        val bgG = (bg shr 8) and 0xFF
        val bgB = bg and 0xFF
        if (bgR <= BLACK_LEVEL && bgG <= BLACK_LEVEL && bgB <= BLACK_LEVEL) return false

        for (y in 0 until size step PRESENCE_PROBE_STEP) {
            val row = y * size
            for (x in 0 until size step PRESENCE_PROBE_STEP) {
                val px = presenceProbePixels[row + x]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF

                if (abs(r - bgR) > BACKGROUND_TOLERANCE ||
                    abs(g - bgG) > BACKGROUND_TOLERANCE ||
                    abs(b - bgB) > BACKGROUND_TOLERANCE) return true
            }
        }
        return false
    }

    private fun revealModel(reason: String) {
        if (modelPresented) return
        modelPresented = true

        binding.progressLoading.visibility = View.GONE
        binding.textLoading.visibility = View.GONE
        binding.loadingOverlay.visibility = View.GONE

        Log.d("ModelViewerActivity", "Revealing model because $reason (after $presenceProbeAttempts probe(s))")

        // Safe to drop only when no copy is reading it, otherwise onDestroy cleans up
        if (!presenceProbeInFlight) {
            presenceProbeBitmap?.recycle()
            presenceProbeBitmap = null
        }
    }

    // If the model fails to load for any reason
    private fun showLoadFailure() {
        modelPresented = true
        binding.progressLoading.visibility = View.GONE
        binding.textLoading.visibility = View.GONE
        binding.loadingOverlay.visibility = View.GONE
        binding.textError.visibility = View.VISIBLE

        // The checklist can still explain why (file facts only, loadedByRenderer = false).
        rendererLoadFailed = true
        onViewerSettled()
    }

    // ── AR readiness ──────────────────────────────────────────────────────────

    /** The model loaded (or failed to): evaluate readiness and honour EXTRA_OPEN_READINESS. */
    private fun onViewerSettled() {
        refreshReadiness()
        if (autoOpenReadinessPending) {
            autoOpenReadinessPending = false
            showReadinessDialog()
        }
    }

    private fun showReadinessDialog() {
        if (supportFragmentManager.isStateSaved) return
        if (supportFragmentManager.findFragmentByTag(ModelDiagnosticsDialogFragment.TAG) != null) return
        try {
            ModelDiagnosticsDialogFragment.newInstance()
                .show(supportFragmentManager, ModelDiagnosticsDialogFragment.TAG)
        } catch (e: Exception) {
            Log.e("ModelViewerActivity", "Failed to show readiness dialog", e)
        }
    }

    /** Filament's asset box (center ± half-extent) as model-local [Bounds]. */
    private fun Box.toReadinessBounds(): Bounds {
        val c = center
        val h = halfExtent
        return Bounds(
            Vec3(c[0] - abs(h[0]), c[1] - abs(h[1]), c[2] - abs(h[2])),
            Vec3(c[0] + abs(h[0]), c[1] + abs(h[1]), c[2] + abs(h[2])),
        )
    }

    /** Re-reads facts (links, file, rendered bounds) and re-evaluates; the dialog follows the state. */
    private fun refreshReadiness() {
        val path = intent.getStringExtra(EXTRA_MODEL_PATH) ?: return
        val name = intent.getStringExtra(EXTRA_MODEL_NAME) ?: "3D Model"
        val box = newModelViewer?.asset?.boundingBox
        val bounds = box?.toReadinessBounds()
        val loaded: Boolean? = when {
            box != null -> true
            rendererLoadFailed -> false
            else -> null
        }
        val details = box?.let { computeModelDiagnostics(it) }
        val id = modelId

        _readinessState.update { it.copy(loading = true) }
        readinessJob?.cancel()
        readinessJob = lifecycleScope.launch {
            var facts: ModelFacts? = null
            val report = runCatching {
                val f = id?.let { readinessRepository.facts(it, bounds, loaded) }
                    ?: readinessRepository.factsForFile(path, name, bounds, loaded)
                facts = f
                withContext(Dispatchers.Default) { readinessRepository.evaluate(f) }
            }.onFailure {
                Log.w("ModelViewerActivity", "Readiness evaluation failed", it)
            }.getOrNull()
            val f = facts
            _readinessState.value = ReadinessUiState(
                loading = false,
                report = report,
                details = details,
                hasModel = f != null && f.modelId.isNotEmpty(),
                applying = false,
            )
            onReadinessFacts(f)
        }
    }

    /** Hook for views that depend on the latest facts (the AR placement overlay). */
    private fun onReadinessFacts(facts: ModelFacts?) {
        // Filled in by the AR placement overlay.
    }

    override fun onReadinessFix(check: ReadinessCheck) {
        if (check.fixAction == FixAction.LINK_POINT) {
            Toast.makeText(this, R.string.readiness_link_point_hint, Toast.LENGTH_LONG).show()
            return
        }
        if (_readinessState.value.applying) return
        _readinessState.update { it.copy(applying = true) }
        lifecycleScope.launch {
            val names = runCatching { readinessRepository.applyFix(modelId, check) }
                .onFailure { Log.w("ModelViewerActivity", "Readiness fix failed", it) }
                .getOrNull()
            _readinessState.update { it.copy(applying = false) }
            when {
                names == null ->
                    Toast.makeText(this@ModelViewerActivity, R.string.readiness_fix_failed, Toast.LENGTH_SHORT).show()
                else -> {
                    Toast.makeText(this@ModelViewerActivity, ReadinessStyle.fixAppliedMessage(names), Toast.LENGTH_SHORT).show()
                    if (names.isNotEmpty()) {
                        DiagnosticsLogger.i("MODEL", "MODEL_READINESS_FIX check=${check.id} " +
                            "action=${check.fixAction} points=${names.size}")
                        refreshReadiness()
                    }
                }
            }
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
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                @Suppress("DEPRECATION")
                PixelCopy.request(surfaceView, bmp, { result ->
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
            // Background colour from the corner (the model is framed in the centre).
            val bg = bmp.getPixel(0, 0)
            val bgR = (bg shr 16) and 0xFF
            val bgG = (bg shr 8) and 0xFF
            val bgB = bg and 0xFF
            var hasContent = false
            outer@ for (dy in -30..30 step 10) {
                for (dx in -30..30 step 10) {
                    val px = bmp.getPixel(
                        (cx + dx).coerceIn(0, bmp.width - 1),
                        (cy + dy).coerceIn(0, bmp.height - 1))
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    if (abs(r - bgR) > BACKGROUND_TOLERANCE ||
                        abs(g - bgG) > BACKGROUND_TOLERANCE ||
                        abs(b - bgB) > BACKGROUND_TOLERANCE) { hasContent = true; break@outer }
                }
            }
            if (!hasContent) {
                bmp.recycle()
                Log.w("ModelViewerActivity", "Thumb: background-only frame, retrying…")
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

                app.surrealar.ui.viewpoints.SimpleCoordinatesAdapter
                    .evictThumbnail(thumbFile.absolutePath)

                try {
                    val repo = modelRepository
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


    /**
     * Reads [filePath] into a direct buffer without an extra heap copy. Returns null (the caller
     * shows the load-failure message) if the file is missing, unreadable, or too big for memory.
     */
    private fun loadFile(filePath: String): ByteBuffer? {
        val file = File(filePath)
        if (!file.exists()) return null
        return try {
            ModelFileIo.readToDirectBuffer(file)
        } catch (t: Throwable) {
            Log.e("ModelViewerActivity", "Could not read model file (${file.length()} bytes)", t)
            DiagnosticsLogger.e("MODEL", "viewer read failed sizeBytes=${file.length()}: " +
                "${t.javaClass.simpleName} ${t.message}")
            null
        }
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
        binding.cardViewerControls.visibility = View.VISIBLE
    }

    private fun collapseControls() {
        Log.d("ModelViewerActivity", "collapseControls: hiding controls")
        binding.btnControlsExpand.setImageResource(R.drawable.ic_zoom_in)
        binding.cardViewerControls.visibility = View.INVISIBLE
    }

    private fun clickedResetView() {
        if (autoRotate) {
            autoRotate = false
            controlsAdapter.setLabel(ModelViewerControl.AUTO_ROTATE, getString(R.string.auto_rotate))
        }
        grabPixelAccum = 0.0

        val manip = orbitManipulator ?: return
        manip.jumpToBookmark(manip.homeBookmark) // homeBookmark has original position, very useful!
    }

    private fun clickedAutoRotate() {
        autoRotate = !autoRotate
        controlsAdapter.setLabel(ModelViewerControl.AUTO_ROTATE, if (autoRotate) "Stop Rotation" else getString(R.string.auto_rotate))
        grabPixelAccum = 0.0
        Log.d("ModelViewerActivity", "autoRotate set to $autoRotate")
    }

    private fun clickedToggleIndirectLight() {
        val viewer = newModelViewer ?: return
        if (viewer.scene.indirectLight == null) return  // no IBL to toggle

        indirectLightEnabled = !indirectLightEnabled
        if (indirectLightEnabled) {
            viewer.scene.indirectLight?.intensity = IBL_INTENSITY
            controlsAdapter.setLabel(ModelViewerControl.LIGHTING, getString(R.string.lighting_on))
        } else {
            // Keep a dim ambient fill: at 0 every face the sun doesn't hit went pure black.
            viewer.scene.indirectLight?.intensity = IBL_DIM_INTENSITY
            controlsAdapter.setLabel(ModelViewerControl.LIGHTING, getString(R.string.lighting_off))
        }
        Log.d("ModelViewerActivity", "dynamicLighting set to $indirectLightEnabled")
    }

    private fun clickedToggleGizmo() {
        val viewer = newModelViewer ?: return
        if (gizmoEntity == 0) return  // gizmo not built yet

        gizmoVisible = !gizmoVisible
        if (gizmoVisible) {
            viewer.scene.addEntity(gizmoEntity)
            controlsAdapter.setLabel(ModelViewerControl.AXIS, getString(R.string.axis_on))
        } else {
            viewer.scene.remove(gizmoEntity)
            controlsAdapter.setLabel(ModelViewerControl.AXIS, getString(R.string.axis_off))
        }
        Log.d("ModelViewerActivity", "gizmoVisible set to $gizmoVisible")
    }

    private fun clickedToggleOffsetMarker() {
        val viewer = newModelViewer ?: return
        if (offsetMarkerEntity == 0) return  // marker not built yet

        offsetMarkerVisible = !offsetMarkerVisible
        if (offsetMarkerVisible) {
            viewer.scene.addEntity(offsetMarkerEntity)
            controlsAdapter.setLabel(ModelViewerControl.OFFSET, getString(R.string.offset_on))
        } else {
            viewer.scene.remove(offsetMarkerEntity)
            controlsAdapter.setLabel(ModelViewerControl.OFFSET, getString(R.string.offset_off))
        }
        Log.d("ModelViewerActivity", "offsetMarkerVisible set to $offsetMarkerVisible")
    }

    private fun clickedToggleBoundingBox() {
        val viewer = newModelViewer ?: return
        if (boundingBoxEntity == 0) return  // bounding box not built yet

        boundingBoxVisible = !boundingBoxVisible
        if (boundingBoxVisible) {
            viewer.scene.addEntity(boundingBoxEntity)
            controlsAdapter.setLabel(ModelViewerControl.BOUNDING_BOX, getString(R.string.bbox_on))
        } else {
            viewer.scene.remove(boundingBoxEntity)
            controlsAdapter.setLabel(ModelViewerControl.BOUNDING_BOX, getString(R.string.bbox_off))
        }
        Log.d("ModelViewerActivity", "boundingBoxVisible set to $boundingBoxVisible")
    }

    private fun clickedToggleGridPlane() {
        val viewer = newModelViewer ?: return
        if (gridEntity == 0) return  // grid not built yet

        gridVisible = !gridVisible
        if (gridVisible) {
            viewer.scene.addEntity(gridEntity)
            controlsAdapter.setLabel(ModelViewerControl.GRID, getString(R.string.grid_on))
        } else {
            viewer.scene.remove(gridEntity)
            controlsAdapter.setLabel(ModelViewerControl.GRID, getString(R.string.grid_off))
        }
        Log.d("ModelViewerActivity", "gridVisible set to $gridVisible")
    }

    private fun clickedToggleOverlayOnTop() {
        val viewer = newModelViewer ?: return

        applyOverlayDepthClear(viewer, !overlayAlwaysOnTop)
        Log.d("ModelViewerActivity", "overlayAlwaysOnTop set to $overlayAlwaysOnTop")
    }

    private fun clickedModelDiagnosticsButton() {
        // Still loading: wait (a failed load still opens the checklist, which explains why).
        if (newModelViewer?.asset == null && !rendererLoadFailed) {
            Toast.makeText(this, R.string.model_diag_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        showReadinessDialog()
        refreshReadiness()
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

        // Only recycle when no PixelCopy is reading it, otherwise let GC do it
        if (!presenceProbeInFlight) presenceProbeBitmap?.recycle()
        presenceProbeBitmap = null

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
//                viewer.scene.remove(sunLightEntity)
//                viewer.engine.lightManager.destroy(sunLightEntity)
//                EntityManager.get().destroy(sunLightEntity)
//                sunLightEntity = 0
//            }

            if (gizmoEntity != 0) {
                viewer.scene.remove(gizmoEntity)
                viewer.engine.destroyEntity(gizmoEntity)
                EntityManager.get().destroy(gizmoEntity)
                gizmoEntity = 0
            }

            if (offsetMarkerEntity != 0) {
                viewer.scene.remove(offsetMarkerEntity)
                viewer.engine.destroyEntity(offsetMarkerEntity)
                EntityManager.get().destroy(offsetMarkerEntity)
                offsetMarkerEntity = 0
            }

            if (boundingBoxEntity != 0) {
                viewer.scene.remove(boundingBoxEntity)
                viewer.engine.destroyEntity(boundingBoxEntity)
                EntityManager.get().destroy(boundingBoxEntity)
                boundingBoxEntity = 0
            }

            if (gridEntity != 0) {
                viewer.scene.remove(gridEntity)
                viewer.engine.destroyEntity(gridEntity)
                EntityManager.get().destroy(gridEntity)
                gridEntity = 0
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
            boundingBoxVertexBuffer?.let { viewer.engine.destroyVertexBuffer(it) }
            boundingBoxVertexBuffer = null
            boundingBoxIndexBuffer?.let { viewer.engine.destroyIndexBuffer(it) }
            boundingBoxIndexBuffer = null
            gridVertexBuffer?.let { viewer.engine.destroyVertexBuffer(it) }
            gridVertexBuffer = null
            gridIndexBuffer?.let { viewer.engine.destroyIndexBuffer(it) }
            gridIndexBuffer = null

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
