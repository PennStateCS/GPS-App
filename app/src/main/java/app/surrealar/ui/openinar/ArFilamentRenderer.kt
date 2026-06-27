package app.surrealar.ui.openinar

import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import com.google.android.filament.*
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.Collections

/**
 * Renders GLB models at ARCore geospatial anchor positions using Filament.
 *
 * Attaches to a [TextureView] with `isOpaque` = false that sits on top of the
 * existing camera-background [android.opengl.GLSurfaceView] in the layout.
 * Filament clears to transparent (RGBA 0,0,0,0) each frame so the camera feed
 * shows through wherever no model is rendered.
 *
 * ## Surface lifecycle
 * [UiHelper] is used instead of a manual [TextureView.SurfaceTextureListener] so that
 * the underlying EGL surface is created with `EGL_TIMESTAMPS_ANDROID = EGL_TRUE`.
 * Without this attribute, Filament's `FrameInfoManager` — which calls
 * `eglGetFrameTimestampsANDROID` after every `endFrame()` — returns
 * `EGL_BAD_ACCESS` spam in logcat. [UiHelper] sets the attribute automatically.
 *
 * ## Threading contract
 * All public methods ([init], [preload], [tickAndApplyLoads], [renderFrame], [destroy])
 * **must be called from the main thread** (the Choreographer callback or Fragment lifecycle).
 * File I/O inside [preload] is dispatched to [Dispatchers.IO] automatically.
 *
 * ## Typical lifecycle
 * ```
 * onViewCreated  → init(textureView)
 * setCoordinates → preload(key, filePath, lifecycleScope)
 * Choreographer  → tickAndApplyLoads(modelPoses)   // every frame — pumps uploads
 * Choreographer  → renderFrame(frameTimeNs, view, proj)  // only when poses non-empty
 * onDestroyView  → destroy()
 * ```
 */
class ArFilamentRenderer {

    // -------------------------------------------------------------------------
    // Public data types

    /** The pose of one model-linked anchor that should be rendered this frame. */
    data class ModelPose(
        /** Unique key — coordinate ID — so the asset cache is keyed per anchor. */
        val key: String,
        /** 4×4 column-major world matrix from [com.google.ar.core.Anchor.pose] toMatrix(). */
        val worldMatrix: FloatArray,
        /** Absolute path to the .glb file. */
        val filePath: String
    )

    // -------------------------------------------------------------------------
    // Private state

    private data class CachedAsset(
        val asset: FilamentAsset,
        /**
         * True once `scene.addEntities` has been called for this asset.
         */
        var addedToScene: Boolean = false,
        /**
         * Number of [tickAndApplyLoads] calls since [ResourceLoader.asyncBeginLoad] was called
         * for this asset.  Used as a per-asset readiness gate: once this reaches
         * [MIN_TICKS_BEFORE_SCENE_ADD] the asset is added to the scene regardless of the
         * global [ResourceLoader.asyncGetLoadProgress] value.
         *
         * Background: asyncGetLoadProgress() aggregates decode-task completions across ALL
         * loaded assets.  When many assets are loaded simultaneously (e.g. a 25-point test
         * grid), Filament's decode thread pool can be overwhelmed and drops tasks silently,
         * causing global progress to stall permanently below 1.0.  The per-asset tick counter
         * bypasses this: mesh buffers are uploaded synchronously inside asyncBeginLoad(), so
         * after a few vsync ticks the GPU data is resident and safe to render.
         */
        var ticksSinceLoad: Int = 0,
        /**
         * Combined centering + normalization matrix applied to the world transform each frame
         * for imported models whose geometry is far from origin or whose units are non-metric.
         * Null = no correction needed (model is well-formed or is a test-grid asset).
         * Computed once when the asset is first added to the scene.
         */
        var correctionMatrix: FloatArray? = null,
        /** Per-coordinate placement (scale/rotation/offset) folded into [correctionMatrix]. */
        val placement: ModelPlacement = ModelPlacement()
    )

    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: View
    private lateinit var camera: Camera
    private var cameraEntity: Int = 0
    private var sunEntity: Int = 0

    private var swapChain: SwapChain? = null

    /**
     * [UiHelper] manages EGL surface creation for the [TextureView].
     * Crucially, it creates the EGL window surface with `EGL_TIMESTAMPS_ANDROID = EGL_TRUE`
     * (on devices that support the `EGL_ANDROID_get_frame_timestamps` extension), which
     * prevents the `EGL_BAD_ACCESS` spam from Filament's `FrameInfoManager`.
     */
    private lateinit var uiHelper: UiHelper

    private lateinit var assetLoader: AssetLoader
    private lateinit var resourceLoader: ResourceLoader

    /** One [CachedAsset] per anchor key (coordinate ID). */
    private val anchorAssets = mutableMapOf<String, CachedAsset>()
    /** Keys currently being loaded so duplicate preload requests are ignored. */
    private val loadingKeys = Collections.synchronizedSet(mutableSetOf<String>())
    /** Shared GLB bytes cache by file path to avoid repeated large allocations. */
    private val fileBytesCache = mutableMapOf<String, ByteArray>()
    /** Serialize file reads/cache population to prevent concurrent readBytes() spikes. */
    private val fileReadMutex = Mutex()
    /** Limit concurrent preload work to reduce pressure during large test grids. */
    private val preloadSemaphore = Semaphore(permits = 2)

    private var initialized = false

    /** Counts every tickAndApplyLoads() call; used to throttle per-frame log spam. */
    private var tickCount = 0L
    /** Whether isReadyToRender was false on the last renderFrame call — avoids log spam. */
    private var lastReadyToRender = true

    // -------------------------------------------------------------------------
    // Debug / diagnostics

    enum class ModelLoadState { NOT_REQUESTED, LOADING, IN_SCENE }

    /** Returns the current load state for [key], safe to call from any thread. */
    fun modelLoadState(key: String): ModelLoadState {
        val cached = anchorAssets[key] ?: return ModelLoadState.NOT_REQUESTED
        return if (cached.addedToScene) ModelLoadState.IN_SCENE else ModelLoadState.LOADING
    }

    // -------------------------------------------------------------------------
    // Lifecycle

    /**
     * Create the Filament engine and attach [UiHelper] to [textureView].
     */
    fun init(textureView: TextureView) {
        Utils.init()   // loads Filament native .so — must precede Engine.create()
        engine   = Engine.create()
        renderer = engine.createRenderer()
        scene    = engine.createScene()

        cameraEntity = EntityManager.get().create()
        camera = engine.createCamera(cameraEntity)

        view = engine.createView().also { v ->
            v.scene  = scene
            v.camera = camera
        }

        // Transparent clear — the GLSurfaceView camera background shows through.
        renderer.clearOptions = Renderer.ClearOptions().apply {
            clearColor[0] = 0f; clearColor[1] = 0f; clearColor[2] = 0f; clearColor[3] = 0f
            clear = true
        }
        scene.skybox = null  // no opaque background layer

        // Directional sunlight so PBR-material models are lit without a KTX environment.
        sunEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .direction(0.5f, -1.0f, -0.5f)
            .intensity(100_000f)
            .castShadows(false)
            .build(engine, sunEntity)
        scene.addEntity(sunEntity)

        // GLB loading infrastructure.
        assetLoader    = AssetLoader(engine, UbershaderProvider(engine), EntityManager.get())
        resourceLoader = ResourceLoader(engine, true)

        // UiHelper: replaces manual SurfaceTextureListener.
        // isOpaque = false → requests a translucent EGL surface config, which combined
        // with clearColor α=0 gives a fully transparent overlay over the camera feed.
        // We also explicitly set textureView.isOpaque = false here because TextureView
        // defaults to opaque=true; without this Android's compositor blends incorrectly
        // and the TextureView covers the GLSurfaceView as solid black.
        textureView.isOpaque = false
        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).also { h ->
            h.isOpaque = false
            h.renderCallback = object : UiHelper.RendererCallback {
                override fun onNativeWindowChanged(surface: android.view.Surface) {
                    swapChain?.let { engine.destroySwapChain(it) }
                    // CONFIG_TRANSPARENT allocates an alpha channel in the Filament
                    // framebuffer so clearing to (0,0,0,0) actually produces transparent
                    // pixels — without this flag the clear alpha is ignored and the
                    // surface appears as solid black over the camera feed.
                    swapChain = engine.createSwapChain(surface, SwapChainFlags.CONFIG_TRANSPARENT)
                    Log.d(TAG, "SwapChain created via UiHelper (transparent)")
                }
                override fun onDetachedFromSurface() {
                    swapChain?.let { engine.destroySwapChain(it) }
                    swapChain = null
                    Log.d(TAG, "SwapChain destroyed (surface detached)")
                }
                override fun onResized(width: Int, height: Int) {
                    view.viewport = Viewport(0, 0, width, height)
                    Log.d(TAG, "Viewport resized: ${width}×${height}")
                }
            }
            h.attachTo(textureView)
        }

        initialized = true
        Log.d(TAG, "Initialised")
    }

    // -------------------------------------------------------------------------
    // Asset loading

    /**
     * Begin async loading of the GLB at [filePath] for the anchor identified by [key].
     * Idempotent — subsequent calls with the same key are silently ignored.
     */
    fun preload(key: String, filePath: String, scope: CoroutineScope, placement: ModelPlacement = ModelPlacement()) {
        if (!initialized) {
            Log.w(DIAG, "preload($key) skipped — not initialized")
            return
        }
        if (anchorAssets.containsKey(key)) {
            Log.d(DIAG, "preload($key) skipped — already in anchorAssets (state=${modelLoadState(key)})")
            return
        }
        if (!loadingKeys.add(key)) {
            Log.d(DIAG, "preload($key) skipped — already in loadingKeys")
            return
        }
        Log.d(DIAG, "preload($key) starting  path=$filePath  fileExists=${File(filePath).exists()}  fileSize=${File(filePath).length()}B")
        scope.launch {
            try {
                preloadSemaphore.withPermit {
                    val bytes = withContext(Dispatchers.IO) {
                        fileReadMutex.withLock {
                            fileBytesCache[filePath] ?: File(filePath).readBytes().also {
                                fileBytesCache[filePath] = it
                            }
                        }
                    }
                    Log.d(DIAG, "preload($key) IO done — ${bytes.size}B read")
                    withContext(Dispatchers.Main) {
                        if (!initialized) {
                            Log.w(DIAG, "preload($key) aborted — renderer destroyed while loading")
                            return@withContext
                        }
                        val buffer = ByteBuffer.wrap(bytes)
                        val asset = assetLoader.createAsset(buffer)
                        if (asset == null) {
                            Log.e(DIAG, "preload($key) FAILED — createAsset returned null  path=$filePath")
                            loadingKeys.remove(key)
                            return@withContext
                        }
                        resourceLoader.asyncBeginLoad(asset)
                        asset.releaseSourceData()
                        anchorAssets[key] = CachedAsset(asset, placement = placement)
                        loadingKeys.remove(key)
                        Log.d(DIAG, "preload($key) asyncBeginLoad called — entities=${asset.entities.size}  path=$filePath")
                    }
                }
            } catch (e: Exception) {
                loadingKeys.remove(key)
                Log.e(DIAG, "preload($key) EXCEPTION  path=$filePath", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Rendering

    /**
     * Pump [ResourceLoader.asyncUpdateLoad] and advance any LOADING assets to IN_SCENE once
     * all pending GPU uploads are complete.  Must be called from the main thread every
     * Choreographer frame — even when [renderFrame] is skipped because there are no poses
     * or the Filament surface is not yet ready.  Separating pumping from rendering means
     * model assets begin loading as soon as [preload] is called, regardless of whether
     * ARCore anchors are tracking or the TextureView surface has been created.
     *
     * @param poses The current set of model poses; used to (a) remove stale test-grid assets
     *              and (b) call `scene.addEntities` / [TransformManager.setTransform] for each
     *              asset that has just finished uploading.
     */
    fun tickAndApplyLoads(poses: List<ModelPose>) {
        if (!initialized) return

        tickCount++
        val logThisFrame = (tickCount % 60L) == 0L  // log once per ~second at 60 fps

        resourceLoader.asyncUpdateLoad()
        val progress  = resourceLoader.asyncGetLoadProgress()
        val allLoaded = progress >= 1.0f

        if (logThisFrame) {
            val states = anchorAssets.entries.joinToString { (k, v) ->
                "$k→${if (v.addedToScene) "IN_SCENE" else "LOADING"}"
            }
            Log.d(DIAG, "tick#$tickCount  poses=${poses.size}  cached=${anchorAssets.size}" +
                    "  loading=${loadingKeys.size}  progress=${"%.2f".format(progress)}" +
                    "  allLoaded=$allLoaded  swapChain=${swapChain != null}" +
                    "  ready=${uiHelper.isReadyToRender}" +
                    if (anchorAssets.isNotEmpty()) "  assets=[$states]" else "  assets=[]")
        }

        // Remove stale test-grid assets when their keys are no longer present.
        // This is required so "Clear Tests" actually removes already-added entities.
        val activeKeys    = poses.asSequence().map { it.key }.toHashSet()
        val staleTestKeys = anchorAssets.keys.filter {
            it.startsWith(TEST_GRID_KEY_PREFIX) && it !in activeKeys
        }
        if (staleTestKeys.isNotEmpty()) {
            // Stale assets that have not yet been fully added to the scene may still be
            // in the ResourceLoader's upload queue. Calling destroyAsset() on an asset
            // that is currently being uploaded triggers a PreconditionPanic — cancel all
            // pending loads first, then re-start loads for non-stale unfinished assets.
            val anyStillLoading = staleTestKeys.any { !anchorAssets[it]!!.addedToScene }
            if (anyStillLoading) {
                resourceLoader.asyncCancelLoad()
            }
            staleTestKeys.forEach { key ->
                val cached = anchorAssets.remove(key) ?: return@forEach
                if (cached.addedToScene) {
                    scene.removeEntities(cached.asset.entities)
                }
                runCatching { assetLoader.destroyAsset(cached.asset) }
                loadingKeys.remove(key)
                Log.d(TAG, "Stale test asset removed: $key")
            }
            if (anyStillLoading) {
                // Re-start uploads for non-stale assets whose loads were interrupted above.
                for ((_, cached) in anchorAssets) {
                    if (!cached.addedToScene) {
                        runCatching { resourceLoader.asyncBeginLoad(cached.asset) }
                    }
                }
            }
        }

        // Transition LOADING → IN_SCENE using a two-path gate:
        //   Path A (fast): asyncGetLoadProgress() reaches 1.0 — all decode tasks finished.
        //   Path B (fallback): per-asset ticksSinceLoad reaches MIN_TICKS_BEFORE_SCENE_ADD.
        //     Mesh buffers are uploaded synchronously inside asyncBeginLoad(), so after a small
        //     number of vsync ticks the GPU data is resident.  This fallback handles the case
        //     where Filament's decode thread pool is overwhelmed by many simultaneous loads
        //     (e.g., a large test grid), causing the global progress counter to stall below 1.0.
        for (pose in poses) {
            val cached = anchorAssets[pose.key]
            if (cached == null) {
                if (logThisFrame) Log.d(DIAG, "  pose ${pose.key} → NOT in anchorAssets (preload pending or failed)")
                continue
            }
            if (!cached.addedToScene) {
                cached.ticksSinceLoad++
                val readyByProgress = allLoaded
                val readyByTicks    = cached.ticksSinceLoad >= MIN_TICKS_BEFORE_SCENE_ADD
                if (!readyByProgress && !readyByTicks) {
                    if (logThisFrame) Log.d(DIAG, "  pose ${pose.key} → LOADING  progress=${"%.2f".format(progress)}" +
                            "  ticks=${cached.ticksSinceLoad}/$MIN_TICKS_BEFORE_SCENE_ADD")
                    continue
                }
                val reason = if (readyByProgress) "progress=1.0" else "ticks=${cached.ticksSinceLoad}"
                cached.addedToScene = true
                scene.addEntities(cached.asset.entities)
                val bb = cached.asset.boundingBox
                val bbc = bb.center
                val bbh = bb.halfExtent
                val maxHalfExtent = maxOf(bbh[0], bbh[1], bbh[2])
                // Normalize scale: compress models whose halfExtent implies non-metric units
                // (e.g. exported in feet, cm, or with absolute coords baked in).
                val ns = if (maxHalfExtent > BB_NORMALIZE_THRESHOLD_M)
                    1.0f / (maxHalfExtent * 2f) else 1.0f
                // Center: pull models whose geometry is far from their local origin back
                // to the anchor.  Small offsets (< BB_CENTER_THRESHOLD_M) are left alone
                // so models intentionally pivoted at their base remain ground-level.
                val needsCenter = Math.abs(bbc[0]) > BB_CENTER_THRESHOLD_M ||
                    Math.abs(bbc[1]) > BB_CENTER_THRESHOLD_M ||
                    Math.abs(bbc[2]) > BB_CENTER_THRESHOLD_M
                val isTestKey = pose.key.startsWith(TEST_GRID_KEY_PREFIX)
                if (!isTestKey) {
                    // Base correction R(180°Y) · S(effScale) · T(-bbc): always applied to user models.
                    //
                    // R(180°Y) rotates the model so its GLTF +Z axis (the face shown in
                    // standard viewers) points toward geodetic North in ARCore's EUS frame
                    // (EUS: X=East, Y=Up, Z=South → 180° around Y makes +Z face North).
                    //
                    // effScale = ns × the per-coordinate placement scale. Combined column-major
                    // matrix = diag(-effScale, effScale, -effScale, 1) with translation column
                    // = R(180°Y)·S(effScale)·(-bbc) = (effScale·bbc.x, -effScale·bbc.y, effScale·bbc.z, 1).
                    val placement = cached.placement
                    val effScale = ns * placement.scale
                    val cm = FloatArray(16)   // zero-initialised
                    cm[0]  = -effScale;  cm[5]  = effScale;  cm[10] = -effScale;  cm[15] = 1.0f
                    cm[12] = effScale * bbc[0]
                    cm[13] = -effScale * bbc[1]
                    cm[14] = effScale * bbc[2]

                    cached.correctionMatrix = if (placement.isIdentity) {
                        cm
                    } else {
                        // Compose the per-coordinate placement on top of the base correction:
                        //   final = T(offset) · R(yaw,pitch,roll) · cm
                        // With identity placement this reduces exactly to `cm`, so models without
                        // custom placement are unaffected.
                        val rot = FloatArray(16); Matrix.setIdentityM(rot, 0)
                        Matrix.rotateM(rot, 0, placement.yawDeg,   0f, 1f, 0f) // yaw about Up (Y)
                        Matrix.rotateM(rot, 0, placement.pitchDeg, 1f, 0f, 0f) // pitch about East (X)
                        Matrix.rotateM(rot, 0, placement.rollDeg,  0f, 0f, 1f) // roll about Z
                        val rc = FloatArray(16); Matrix.multiplyMM(rc, 0, rot, 0, cm, 0)
                        val trans = FloatArray(16); Matrix.setIdentityM(trans, 0)
                        Matrix.translateM(
                            trans, 0,
                            placement.originOffsetXM,
                            placement.verticalOffsetM + placement.originOffsetYM,
                            placement.originOffsetZM
                        )
                        FloatArray(16).also { Matrix.multiplyMM(it, 0, trans, 0, rc, 0) }
                    }
                }
                Log.d(DIAG, "  pose ${pose.key} → ADDED TO SCENE ($reason)" +
                    "  entities=${cached.asset.entities.size}" +
                    "  bbCenter=(%.2f,%.2f,%.2f)  halfExtent=(%.2f,%.2f,%.2f)" +
                    "  ns=%.6f  center=%b  northFacing=%b".format(
                        bbc[0], bbc[1], bbc[2], bbh[0], bbh[1], bbh[2],
                        ns, needsCenter, !isTestKey))
            }
            // Asset is in scene — update its world transform every frame.
            val tm = engine.transformManager
            val ti = tm.getInstance(cached.asset.root)
            if (ti == 0) {
                Log.e(DIAG, "  pose ${pose.key} → root entity has NO transform component!")
                continue
            }
            val cm = cached.correctionMatrix
            if (cm != null) {
                val corrected = FloatArray(16)
                Matrix.multiplyMM(corrected, 0, pose.worldMatrix, 0, cm, 0)
                tm.setTransform(ti, corrected)
            } else {
                tm.setTransform(ti, pose.worldMatrix)
            }
        }
    }

    /**
     * Render one frame. Call from [android.view.Choreographer.FrameCallback.doFrame].
     *
     * Asset loading/management is handled separately in [tickAndApplyLoads], which must be
     * called every frame before this method.  [renderFrame] is responsible only for the
     * GPU rendering pass and may be skipped when there are no poses to draw.
     *
     * @param frameTimeNs Choreographer frame time (nanoseconds).
     * @param viewMatrix  ARCore view matrix (world → camera, column-major float[16]).
     * @param projMatrix  ARCore projection matrix (column-major float[16]).
     */
    fun renderFrame(
        frameTimeNs: Long,
        viewMatrix: FloatArray,
        projMatrix: FloatArray,
    ) {
        if (!initialized) return

        val ready = uiHelper.isReadyToRender
        if (ready != lastReadyToRender) {
            Log.d(DIAG, "renderFrame: isReadyToRender changed → $ready  swapChain=${swapChain != null}")
            lastReadyToRender = ready
        }
        if (!ready) return
        val sc = swapChain ?: run {
            Log.w(DIAG, "renderFrame: swapChain is null despite isReadyToRender=true")
            return
        }

        // Camera — ARCore gives world→camera; Filament camera.setModelMatrix wants camera→world.
        // Guard against a degenerate view matrix (should not happen with a healthy ARCore session,
        // but a singular matrix would produce NaN/Inf values that trigger PreconditionPanic).
        val cameraWorld = FloatArray(16)
        if (!Matrix.invertM(cameraWorld, 0, viewMatrix, 0)) {
            Log.w(TAG, "renderFrame: view matrix is singular — skipping frame")
            return
        }
        camera.setModelMatrix(cameraWorld)
        camera.setCustomProjection(
            DoubleArray(16) { i -> projMatrix[i].toDouble() },
            NEAR_PLANE, FAR_PLANE
        )

        if (renderer.beginFrame(sc, frameTimeNs)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    // -------------------------------------------------------------------------
    // Cleanup

    /**
     * Release all Filament resources.
     * Engine.destroy() is deferred by 50 ms (same workaround as `ModelViewerActivity`)
     * to avoid the SIGABRT that occurs when the deferred UiHelper cleanup message runs
     * after the engine has already been destroyed.
     *
     * Destruction order matters:
     *  1. Cancel async loads; destroy all gltfio assets.
     *  2. Explicitly destroy [resourceLoader] and [assetLoader] — these gltfio objects hold
     *     native state and have JVM finalizers. If NOT destroyed here, the GC may invoke
     *     their finalizers after engine.destroy() → `utils::PreconditionPanic` (SIGABRT).
     *  3. Detach UiHelper (fires onDetachedFromSurface → destroySwapChain).
     *  4. Destroy remaining engine-owned objects (renderer, view, camera, scene).
     *  5. Deferred: engine.flushAndWait() + engine.destroy() after 50 ms so that any
     *     messages posted by UiHelper/DisplayHelper have already run on the main thread.
     */
    fun destroy() {
        if (!initialized) return
        initialized = false

        resourceLoader.asyncCancelLoad()
        loadingKeys.clear()
        fileBytesCache.clear()
        anchorAssets.values.forEach { cached ->
            runCatching { scene.removeEntities(cached.asset.entities) }
            runCatching { assetLoader.destroyAsset(cached.asset) }
        }
        anchorAssets.clear()

        if (sunEntity != 0) {
            runCatching { scene.removeEntity(sunEntity) }
            runCatching { engine.lightManager.destroy(sunEntity) }
            runCatching { EntityManager.get().destroy(sunEntity) }
            sunEntity = 0
        }

        // Explicitly destroy gltfio utility objects BEFORE the engine.
        // Their JVM finalizers call back into native; if the engine is already
        // gone when finalizers run, Filament throws PreconditionPanic.
        runCatching { resourceLoader.destroy() }.onFailure { Log.w(TAG, "resourceLoader.destroy() threw", it) }
        runCatching { assetLoader.destroy()    }.onFailure { Log.w(TAG, "assetLoader.destroy() threw", it) }

        // Detach UiHelper — triggers onDetachedFromSurface() → destroySwapChain().
        runCatching { uiHelper.detach() }

        runCatching { engine.destroyRenderer(renderer) }
        runCatching { engine.destroyView(view) }
        runCatching { engine.destroyCameraComponent(cameraEntity) }
        runCatching { EntityManager.get().destroy(cameraEntity) }
        runCatching { engine.destroyScene(scene) }

        // Defer engine.destroy() so any queued Filament main-thread messages
        // (e.g. from UiHelper's DisplayHelper) can run while the engine is still alive.
        val eng = engine
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                eng.flushAndWait()
                eng.destroy()
                Log.d(TAG, "Engine destroyed")
            } catch (t: Throwable) {
                Log.w(TAG, "Engine destroy threw", t)
            }
        }, 50)

        Log.d(TAG, "Destroyed")
    }

    // -------------------------------------------------------------------------
    companion object {
        private const val TAG        = "ArFilamentRenderer"
        /** Dedicated tag for model-loading diagnostics — filter logcat by this tag. */
        const val DIAG               = "AR_MDL"
        /** Must match the near/far values used in ARCore's getProjectionMatrix() call. */
        private const val NEAR_PLANE = 0.1
        private const val FAR_PLANE  = 2000.0
        private const val TEST_GRID_KEY_PREFIX = "test_grid_"
        /**
         * Minimum number of [tickAndApplyLoads] calls that must occur after `asyncBeginLoad`
         * before an asset is added to the scene via the tick-based fallback path.
         * 5 ticks ≈ 83 ms at 60 fps — enough for mesh-buffer uploads queued inside
         * asyncBeginLoad() to drain through the Filament backend command queue.
         */
        private const val MIN_TICKS_BEFORE_SCENE_ADD = 5
        /**
         * Bounding-box half-extent threshold (metres) above which the model is assumed to be
         * in non-metric units and is normalised to a 1-metre reference size.
         * Models like survey pins exported in cm/feet/absolute-coords trigger this.
         */
        private const val BB_NORMALIZE_THRESHOLD_M = 50.0f
        /**
         * Bounding-box centre threshold (metres) above which the model's geometry is considered
         * offset from its local origin and the centre is snapped to the anchor position.
         * Models pivoted intentionally at their base (small offset) are left unchanged.
         */
        private const val BB_CENTER_THRESHOLD_M = 1.0f
    }
}
