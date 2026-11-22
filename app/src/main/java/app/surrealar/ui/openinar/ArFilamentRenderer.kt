package app.surrealar.ui.openinar

import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import app.surrealar.util.ArSessionDiagnostics
import app.surrealar.util.DiagnosticsLogger
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
        /**
         * 4×4 column-major world matrix from [com.google.ar.core.Anchor.pose] toMatrix().
         * Must be the **raw** anchor pose — do NOT pre-scale the basis columns via Matrix.scaleM.
         * Scaling is handled inside [tickAndApplyLoads] via [coordScale] so the correction
         * matrix and bounding-box normalization thresholds use the correct effective scale.
         */
        val worldMatrix: FloatArray,
        /** Absolute path to the .glb file. */
        val filePath: String,
        /**
         * Global scale factor applied to this model in addition to [ModelPlacement.scale].
         * Corresponds to the per-session "model scale" slider (default 1.0 = real-world size).
         * Folded into [effScale] inside [tickAndApplyLoads] so the correction matrix,
         * normalization threshold, and placement all account for the full intended scale.
         */
        val coordScale: Float = 1.0f
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
        val placement: ModelPlacement = ModelPlacement(),
        /**
         * True once the asset has been in the scene at least once. A hidden asset
         * (addedToScene=false) that was previously shown has already finished its upload and
         * must not be re-submitted to [ResourceLoader.asyncBeginLoad].
         */
        var everAddedToScene: Boolean = false
    )

    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    /**
     * Scene with no entities, swapped into [view] by [renderFrame] when `hideModels` is set (camera
     * tracking lost) so a transparent frame is drawn without removing/destroying any cached asset.
     */
    private lateinit var emptyScene: Scene
    private lateinit var view: View
    private lateinit var camera: Camera
    private var cameraEntity: Int = 0
    private var sunEntity: Int = 0
    /** Ambient light so faces away from the sun never render black; rebuilt when the estimate shifts. */
    private var indirectLight: IndirectLight? = null
    private var lighting: ArLighting.State? = null
    private var lastIblSh: FloatArray? = null
    private var lastIblRebuildMs = 0L
    private var lastLightLogMs = 0L

    private var swapChain: SwapChain? = null

    /** Depth-only renderable for real-world occlusion; null if it failed to initialise. */
    private var occluder: FilamentDepthOccluder? = null

    /**
     * [UiHelper] manages EGL surface creation for the [TextureView].
     * Crucially, it creates the EGL window surface with `EGL_TIMESTAMPS_ANDROID = EGL_TRUE`
     * (on devices that support the `EGL_ANDROID_get_frame_timestamps` extension), which
     * prevents the `EGL_BAD_ACCESS` spam from Filament's `FrameInfoManager`.
     */
    private lateinit var uiHelper: UiHelper

    private lateinit var assetLoader: AssetLoader
    /** Loads real (user) models. Never cancelled while the renderer is alive. */
    private lateinit var resourceLoader: ResourceLoader
    /**
     * Loads debug test-grid pins only. Clearing the grid mid-load needs asyncCancelLoad(), which cancels
     * EVERY pending texture decode on its loader; with one shared loader that killed a real model's
     * textures and the model rendered black (gltfio won't restart an asset it already began). Keeping
     * the pins on their own loader confines the cancel to them.
     */
    private lateinit var testResourceLoader: ResourceLoader

    private fun loaderFor(key: String): ResourceLoader =
        if (key.startsWith(TEST_GRID_KEY_PREFIX)) testResourceLoader else resourceLoader

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

    /**
     * Called on the main thread when a model whose geometry is far from its local origin
     * is first added to the scene with a placement preset (ORIGIN or CUSTOM) that is unlikely
     * to show the geometry correctly. The fragment uses this to show a one-time snackbar
     * guiding the user to switch to Bottom Center or Custom Offset.
     *
     * [coordId] — the coordinate ID; [modelFileName] — the model's file basename;
     * [originToBottomCenterM] — distance from local origin to visible geometry bottom center.
     */
    var onModelPlacementWarning: ((coordId: String, modelFileName: String, originToBottomCenterM: Float) -> Unit)? = null

    /** Counts every tickAndApplyLoads() call; used to throttle per-frame log spam. */
    private var tickCount = 0L
    /** Whether isReadyToRender was false on the last renderFrame call — avoids log spam. */
    private var lastReadyToRender = true

    // -------------------------------------------------------------------------
    // Debug / diagnostics

    enum class ModelLoadState { NOT_REQUESTED, LOADING, IN_SCENE }

    /**
     * Returns the current load state for [key]. Call from the main thread: this reads [anchorAssets],
     * which is a plain (non-concurrent) map confined to the main thread — every mutation happens in the
     * Choreographer tick, the [preload] main-continuation, and [destroy], all of which run on main.
     */
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
        emptyScene = engine.createScene()

        cameraEntity = EntityManager.get().create()
        camera = engine.createCamera(cameraEntity)

        view = engine.createView().also { v ->
            v.scene  = scene
            v.camera = camera
        }

        // Transparent clear — the GLSurfaceView camera background shows through.
        renderer.clearOptions = Renderer.ClearOptions().apply {
            clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
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
        // Ambient light from the start (fallback); ARCore's estimate takes over in updateLighting().
        applyLighting(ArLighting.fallback(), force = true)

        // Depth occluder (optional): failure only disables occlusion, never the renderer.
        occluder = runCatching { FilamentDepthOccluder(engine, textureView.context.assets) }
            .onFailure { DiagnosticsLogger.w(DIAG, "depth occluder init failed — occlusion unavailable: ${it.message}", it) }
            .getOrNull()

        // GLB loading infrastructure.
        assetLoader    = AssetLoader(engine, UbershaderProvider(engine), EntityManager.get())
        resourceLoader = ResourceLoader(engine, true)
        testResourceLoader = ResourceLoader(engine, true)

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
            DiagnosticsLogger.w(DIAG, "preload($key) skipped — renderer not initialized")
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
        val srcFile = File(filePath)
        DiagnosticsLogger.i(DIAG, "preload start key=$key path=\"$filePath\" " +
            "exists=${srcFile.exists()} sizeBytes=${if (srcFile.exists()) srcFile.length() else -1L}")
        ArSessionDiagnostics.setStatus(key, ArSessionDiagnostics.ModelStatus.LOADING)
        scope.launch {
            try {
                preloadSemaphore.withPermit {
                    val bytes = withContext(Dispatchers.IO) {
                        fileReadMutex.withLock {
                            fileBytesCache[filePath] ?: try {
                                File(filePath).readBytes()
                            } catch (oom: OutOfMemoryError) {
                                // Too big for the heap: fail this model through the normal
                                // exception path below instead of crashing the AR session.
                                throw java.io.IOException("Out of memory reading model file " +
                                    "(${File(filePath).length()} bytes)", oom)
                            }.also {
                                fileBytesCache[filePath] = it
                            }
                        }
                    }
                    Log.d(DIAG, "preload($key) IO done — ${bytes.size}B read")
                    withContext(Dispatchers.Main) {
                        if (!initialized) {
                            // Lifecycle cancellation: the user left AR before the GLB finished loading.
                            DiagnosticsLogger.w(DIAG, "preload aborted key=$key reason=\"renderer destroyed while loading\"")
                            ArSessionDiagnostics.setStatus(key, ArSessionDiagnostics.ModelStatus.FAILED, "lifecycle_cancelled")
                            return@withContext
                        }
                        val buffer = ByteBuffer.wrap(bytes)
                        // createAsset parses the GLB/GLTF; null means parse / asset creation failed.
                        val asset = assetLoader.createAsset(buffer)
                        if (asset == null) {
                            DiagnosticsLogger.e(DIAG, "preload failed key=$key reason=\"createAsset returned null (GLB parse/asset creation failed)\" path=\"$filePath\"")
                            ArSessionDiagnostics.setStatus(key, ArSessionDiagnostics.ModelStatus.FAILED, "filament_asset_failed")
                            loadingKeys.remove(key)
                            return@withContext
                        }
                        loaderFor(key).asyncBeginLoad(asset)
                        asset.releaseSourceData()
                        anchorAssets[key] = CachedAsset(asset, placement = placement)
                        loadingKeys.remove(key)
                        // The GLB is now parsed and its GPU upload is queued — the raw file bytes are no
                        // longer needed. Drop them (under the same mutex that guards the cache) so a long
                        // session with many/large models doesn't retain every file's bytes until destroy().
                        // The cache still de-dups concurrent reads of a shared file; a later re-preload of
                        // the same path simply re-reads it from disk.
                        fileReadMutex.withLock { fileBytesCache.remove(filePath) }
                        DiagnosticsLogger.i(DIAG, "preload success key=$key entities=${asset.entities.size}")
                        ArSessionDiagnostics.setStatus(key, ArSessionDiagnostics.ModelStatus.READY)
                    }
                }
            } catch (e: Exception) {
                loadingKeys.remove(key)
                DiagnosticsLogger.e(DIAG, "preload failed key=$key reason=\"exception\" path=\"$filePath\"", e)
                ArSessionDiagnostics.setStatus(key, ArSessionDiagnostics.ModelStatus.FAILED, "parse_failed")
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
    fun tickAndApplyLoads(
        poses: List<ModelPose>,
        visibleKeys: Set<String> = poses.mapTo(HashSet()) { it.key },
        /**
         * Test-grid keys whose anchors still exist even if not tracking this frame. Their assets are
         * kept; only keys absent from both [poses] and this set are treated as cleared.
         */
        liveTestKeys: Set<String> = emptySet(),
    ) {
        if (!initialized) return

        tickCount++
        val logThisFrame = (tickCount % 60L) == 0L  // log once per ~second at 60 fps

        resourceLoader.asyncUpdateLoad()
        testResourceLoader.asyncUpdateLoad()
        // Progress per loader: user models are judged only by their own loader.
        val progress  = resourceLoader.asyncGetLoadProgress()
        val testProgress = testResourceLoader.asyncGetLoadProgress()
        val allLoaded = progress >= 1.0f
        val allTestLoaded = testProgress >= 1.0f

        if (logThisFrame) {
            val states = anchorAssets.entries.joinToString { (k, v) ->
                "$k→${if (v.addedToScene) "IN_SCENE" else "LOADING"}"
            }
            Log.d(DIAG, "tick#$tickCount  poses=${poses.size}  cached=${anchorAssets.size}" +
                    "  loading=${loadingKeys.size}  progress=${"%.2f".format(progress)}" +
                    "  testProgress=${"%.2f".format(testProgress)}" +
                    "  allLoaded=$allLoaded  swapChain=${swapChain != null}" +
                    "  ready=${uiHelper.isReadyToRender}" +
                    if (anchorAssets.isNotEmpty()) "  assets=[$states]" else "  assets=[]")
        }

        // Remove stale test-grid assets when their anchors are gone (so "Clear Tests" actually
        // removes already-added entities). A key with no pose this frame but a live anchor is NOT
        // stale: right after a Stored/Terrain respawn the new anchors are not tracking yet, and
        // deleting the assets then left the grid's labels with no models (they were never reloaded).
        val activeKeys    = poses.asSequence().map { it.key }.toHashSet().apply { addAll(liveTestKeys) }
        val staleTestKeys = anchorAssets.keys.filter {
            it.startsWith(TEST_GRID_KEY_PREFIX) && it !in activeKeys
        }
        if (staleTestKeys.isNotEmpty()) {
            // Stale assets that have not yet been fully added to the scene may still be
            // in the ResourceLoader's upload queue. Calling destroyAsset() on an asset
            // that is currently being uploaded triggers a PreconditionPanic — cancel all
            // pending loads first, then re-start loads for non-stale unfinished assets.
            val anyStillLoading = staleTestKeys.any { anchorAssets[it]?.everAddedToScene == false }
            if (anyStillLoading) {
                // Test-grid loader only — real models' texture decodes are untouched.
                testResourceLoader.asyncCancelLoad()
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
                // Only assets that never reached the scene: a model the user merely HID
                // (addedToScene=false) already finished uploading, and re-submitting it to
                // asyncBeginLoad would re-upload its resources.
                for ((key, cached) in anchorAssets) {
                    if (key.startsWith(TEST_GRID_KEY_PREFIX) && !cached.everAddedToScene) {
                        runCatching { testResourceLoader.asyncBeginLoad(cached.asset) }
                    }
                }
            }
        }

        // Model-visibility: an asset that is no longer in [visibleKeys] (user hid it, or it fell out of
        // range/mode) is removed from the scene but kept cached (addedToScene=false) so re-showing it
        // re-adds instantly without reloading. A key that IS visible but has no pose this frame (its
        // anchor briefly stopped tracking) is left in the scene, holding its last transform — no flicker.
        for ((key, cached) in anchorAssets) {
            if (key.startsWith(TEST_GRID_KEY_PREFIX)) continue
            if (cached.addedToScene && key !in visibleKeys) {
                runCatching { scene.removeEntities(cached.asset.entities) }
                cached.addedToScene = false
                if (logThisFrame) Log.d(DIAG, "  model $key → HIDDEN (removed from scene, kept cached)")
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
                val readyByProgress = if (pose.key.startsWith(TEST_GRID_KEY_PREFIX)) allTestLoaded else allLoaded
                val readyByTicks    = cached.ticksSinceLoad >= MIN_TICKS_BEFORE_SCENE_ADD
                if (!readyByProgress && !readyByTicks) {
                    if (logThisFrame) Log.d(DIAG, "  pose ${pose.key} → LOADING  progress=${"%.2f".format(progress)}" +
                            "  ticks=${cached.ticksSinceLoad}/$MIN_TICKS_BEFORE_SCENE_ADD")
                    continue
                }
                val reason = if (readyByProgress) "progress=1.0" else "ticks=${cached.ticksSinceLoad}"
                cached.addedToScene = true
                cached.everAddedToScene = true
                scene.addEntities(cached.asset.entities)
                val bb = cached.asset.boundingBox
                val bbc = bb.center
                val bbh = bb.halfExtent
                val maxHalfExtent = maxOf(bbh[0], bbh[1], bbh[2])
                // Normalize scale ONLY for implausibly large models (wrong units, e.g. mm, or absolute
                // projected coordinates baked into the vertices). Real site/building models of a few
                // hundred metres must stay true scale — the old 50 m threshold shrank them to 1 m.
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
                    val f: (Float) -> String = { "%.2f".format(it) }
                    val fileName = pose.filePath.substringAfterLast('/')
                    val bounds = ModelBounds.fromCenterHalfExtent(bbc[0], bbc[1], bbc[2], bbh[0], bbh[1], bbh[2])
                    val placement = cached.placement
                    val origin = placement.placementOrigin

                    DiagnosticsLogger.i(DIAG, "MODEL_BOUNDS model=$fileName coordId=${pose.key} " +
                        "boundsMin=(${f(bounds.min.x)},${f(bounds.min.y)},${f(bounds.min.z)}) " +
                        "boundsMax=(${f(bounds.max.x)},${f(bounds.max.y)},${f(bounds.max.z)}) " +
                        "size=(${f(bounds.size.x)},${f(bounds.size.y)},${f(bounds.size.z)}) " +
                        "center=(${f(bounds.center.x)},${f(bounds.center.y)},${f(bounds.center.z)}) " +
                        "bottomCenter=(${f(bounds.bottomCenter.x)},${f(bounds.bottomCenter.y)},${f(bounds.bottomCenter.z)}) " +
                        "originToBottomCenter=${f(bounds.originToBottomCenterM)}m")
                    // Warn only when the geometry is far from the origin AND the (default) ORIGIN/CENTER
                    // presets would leave it misplaced — steer the user to Bottom Center / Custom Offset.
                    if (bounds.originToBottomCenterM > ModelBounds.FAR_FROM_ORIGIN_M &&
                        (origin == ModelPlacementOrigin.ORIGIN || origin == ModelPlacementOrigin.CUSTOM)) {
                        DiagnosticsLogger.w(DIAG, "MODEL_PLACEMENT_WARN model=$fileName " +
                            "originToBottomCenter=${f(bounds.originToBottomCenterM)}m; visible geometry is far " +
                            "from origin. Use Bottom Center or Custom Offset.")
                        onModelPlacementWarning?.invoke(pose.key, fileName, bounds.originToBottomCenterM)
                    }

                    // Anchor point = the model-local point that should sit on the AR anchor, per preset.
                    // For CUSTOM it is the per-coordinate offset. The base correction is
                    //   R(180°Y) · S(effScale) · T(-anchorPoint)
                    // R(180°Y) points the model's GLTF +Z toward geodetic North in ARCore's EUS frame.
                    // Column-major: diag(-eff, eff, -eff, 1) with translation (eff·ap.x, -eff·ap.y, eff·ap.z, 1).
                    val customOffset = Vec3(placement.originOffsetXM, placement.originOffsetYM, placement.originOffsetZM)
                    // Shared split so the custom offset is applied EXACTLY ONCE (anchor point for CUSTOM,
                    // nudge otherwise) — see ModelBounds.resolveOffsets. Same logic the tests exercise.
                    val offsets = bounds.resolveOffsets(origin, customOffset, placement.verticalOffsetM)
                    val ap = offsets.anchorPoint
                    // coordScale = global "model scale" slider; placement.scale = per-model scale;
                    // ns = normalization factor that shrinks non-metric models to ≤1 m reference.
                    // All three are folded here so the correction matrix and threshold checks are
                    // correct for the actual rendered size (fixes the arModelScale inconsistency
                    // that occurred when the caller applied scale to worldMatrix basis columns).
                    val effScale = ns * placement.scale * pose.coordScale
                    // Same matrix as before, now shared with the GLB-orientation tests.
                    val cm = ModelCorrectionMatrix.base(effScale, ap.x, ap.y, ap.z)

                    // Fine nudge applied on top (T(nudge) · R · cm). For CUSTOM the offset is already in the
                    // anchor point above, so only the vertical nudge remains; other presets keep the nudge.
                    val nudgeX = offsets.nudge.x
                    val nudgeY = offsets.nudge.y
                    val nudgeZ = offsets.nudge.z
                    val hasRot = placement.yawDeg != 0f || placement.pitchDeg != 0f || placement.rollDeg != 0f
                    val hasNudge = nudgeX != 0f || nudgeY != 0f || nudgeZ != 0f

                    cached.correctionMatrix = if (!hasRot && !hasNudge) {
                        cm
                    } else {
                        // final = T(nudge) · R(yaw,pitch,roll) · cm
                        val rot = FloatArray(16); Matrix.setIdentityM(rot, 0)
                        Matrix.rotateM(rot, 0, placement.yawDeg,   0f, 1f, 0f) // yaw about Up (Y)
                        Matrix.rotateM(rot, 0, placement.pitchDeg, 1f, 0f, 0f) // pitch about East (X)
                        Matrix.rotateM(rot, 0, placement.rollDeg,  0f, 0f, 1f) // roll about Z
                        val rc = FloatArray(16); Matrix.multiplyMM(rc, 0, rot, 0, cm, 0)
                        val trans = FloatArray(16); Matrix.setIdentityM(trans, 0)
                        Matrix.translateM(trans, 0, nudgeX, nudgeY, nudgeZ)
                        FloatArray(16).also { Matrix.multiplyMM(it, 0, trans, 0, rc, 0) }
                    }

                    // Scale audit: what the model will actually measure in the world. "trueScale" is the
                    // only correct state for real-world placement; anything else is called out so a
                    // too-big/too-small model can be traced to its cause from the export alone.
                    val worldSizeM = maxHalfExtent * 2f * effScale
                    val scaleCause = when {
                        ns != 1f -> "unit_normalized(halfExtent=${f(maxHalfExtent)}>${f(BB_NORMALIZE_THRESHOLD_M)})"
                        pose.coordScale != 1f -> "global_model_scale=${f(pose.coordScale)}"
                        placement.scale != 1f -> "per_model_scale=${f(placement.scale)}"
                        else -> "trueScale"
                    }
                    val scaleMsg = "MODEL_SCALE model=$fileName coordId=${pose.key} fileSizeM=${f(maxHalfExtent * 2f)} " +
                        "worldSizeM=${f(worldSizeM)} effScale=${f(effScale)} ns=${"%.6f".format(ns)} " +
                        "perModel=${f(placement.scale)} global=${f(pose.coordScale)} state=$scaleCause"
                    if (scaleCause == "trueScale") DiagnosticsLogger.i(DIAG, scaleMsg) else DiagnosticsLogger.w(DIAG, scaleMsg)

                    DiagnosticsLogger.i(DIAG, "MODEL_PLACEMENT model=$fileName coordId=${pose.key} origin=$origin " +
                        "anchorPoint=(${f(ap.x)},${f(ap.y)},${f(ap.z)}) " +
                        "computedOffset=(${f(-ap.x)},${f(-ap.y)},${f(-ap.z)}) " +
                        "customOffset=(${f(customOffset.x)},${f(customOffset.y)},${f(customOffset.z)}) " +
                        "nudge=(${f(nudgeX)},${f(nudgeY)},${f(nudgeZ)}) scale=${f(effScale)} " +
                        "yawCCW=${f(placement.yawDeg)} pitch=${f(placement.pitchDeg)} roll=${f(placement.rollDeg)} " +
                        "anchorType=geospatial transformSource=anchor-driven")
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
     * Shows the real-world depth occluder built from [frame], or removes it when [frame] is null
     * (occlusion off / no depth yet). Main thread; call each Choreographer frame before [renderFrame].
     */
    internal fun updateOcclusion(frame: OcclusionFrame?) {
        if (!initialized) return
        occluder?.update(scene, frame)
    }

    /**
     * Follows ARCore's light estimate (null/invalid keeps the current lighting). Main thread; call each
     * Choreographer frame before [renderFrame]. Cheap when nothing changed.
     */
    internal fun updateLighting(estimate: ArLighting.Estimate?) {
        if (!initialized) return
        if (estimate == null && lighting != null) return
        applyLighting(ArLighting.next(estimate, lighting), force = false)
    }

    private fun applyLighting(state: ArLighting.State, force: Boolean) {
        lighting = state
        if (sunEntity != 0) {
            val lm = engine.lightManager
            val inst = lm.getInstance(sunEntity)
            if (inst != 0) {
                lm.setDirection(inst, state.sunDirection[0], state.sunDirection[1], state.sunDirection[2])
                lm.setColor(inst, state.sunColor[0], state.sunColor[1], state.sunColor[2])
                lm.setIntensity(inst, state.sunLux)
            }
        }
        // Rebuilding the IBL allocates on the native side — at most twice a second and only on real change.
        val now = android.os.SystemClock.elapsedRealtime()
        val prevSh = lastIblSh
        val rebuild = force || prevSh == null ||
            (now - lastIblRebuildMs >= 500L && ArLighting.shChangedEnough(prevSh, state.ambientSh))
        if (rebuild) {
            val ibl = IndirectLight.Builder()
                .irradiance(3, state.ambientSh)
                .intensity(state.ambientIntensity)
                .build(engine)
            scene.indirectLight = ibl
            indirectLight?.let { old -> runCatching { engine.destroyIndirectLight(old) } }
            indirectLight = ibl
            lastIblSh = state.ambientSh.copyOf()
            lastIblRebuildMs = now
        }
        if (force || now - lastLightLogMs >= 30_000L) {
            lastLightLogMs = now
            DiagnosticsLogger.i(DIAG, "LIGHT_ESTIMATE source=${if (state.fromEstimate) "arcore" else "fallback"} " +
                "sunLux=${"%.0f".format(state.sunLux)} rawSun=${"%.2f".format(state.rawSunIntensity)} sunRgb=${"%.2f,%.2f,%.2f".format(state.sunColor[0], state.sunColor[1], state.sunColor[2])} " +
                "sunDir=${"%.2f,%.2f,%.2f".format(state.sunDirection[0], state.sunDirection[1], state.sunDirection[2])} " +
                "ambientDc=${"%.2f,%.2f,%.2f".format(state.ambientSh[0], state.ambientSh[1], state.ambientSh[2])}")
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
     * @param hideModels  When true (ARCore camera not tracking — [viewMatrix] is stale), render a
     *                    transparent frame instead of drawing models at an out-of-date camera pose.
     *                    Assets stay cached and in [scene]; they reappear on the next normal frame.
     */
    fun renderFrame(
        frameTimeNs: Long,
        viewMatrix: FloatArray,
        projMatrix: FloatArray,
        hideModels: Boolean = false,
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

        val targetScene = if (hideModels) emptyScene else scene
        if (view.scene !== targetScene) view.scene = targetScene

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
        testResourceLoader.asyncCancelLoad()
        loadingKeys.clear()
        fileBytesCache.clear()
        anchorAssets.values.forEach { cached ->
            runCatching { scene.removeEntities(cached.asset.entities) }
            runCatching { assetLoader.destroyAsset(cached.asset) }
        }
        anchorAssets.clear()

        occluder?.destroy(scene)
        occluder = null

        if (sunEntity != 0) {
            runCatching { scene.remove(sunEntity) }
            runCatching { engine.lightManager.destroy(sunEntity) }
            runCatching { EntityManager.get().destroy(sunEntity) }
            sunEntity = 0
        }
        indirectLight?.let { ibl ->
            runCatching { scene.indirectLight = null }
            runCatching { engine.destroyIndirectLight(ibl) }
        }
        indirectLight = null
        lighting = null
        lastIblSh = null

        // Explicitly destroy gltfio utility objects BEFORE the engine.
        // Their JVM finalizers call back into native; if the engine is already
        // gone when finalizers run, Filament throws PreconditionPanic.
        runCatching { resourceLoader.destroy() }.onFailure { Log.w(TAG, "resourceLoader.destroy() threw", it) }
        runCatching { testResourceLoader.destroy() }.onFailure { Log.w(TAG, "testResourceLoader.destroy() threw", it) }
        runCatching { assetLoader.destroy()    }.onFailure { Log.w(TAG, "assetLoader.destroy() threw", it) }

        // Detach UiHelper — triggers onDetachedFromSurface() → destroySwapChain().
        runCatching { uiHelper.detach() }

        runCatching { engine.destroyRenderer(renderer) }
        runCatching { engine.destroyView(view) }
        runCatching { engine.destroyCameraComponent(cameraEntity) }
        runCatching { EntityManager.get().destroy(cameraEntity) }
        runCatching { engine.destroyScene(scene) }
        runCatching { engine.destroyScene(emptyScene) }

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
        const val DIAG               = "AR"
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
         * Bounding-box half-extent threshold (metres) above which the model is assumed to be in
         * non-metric units (mm/cm) or to carry absolute projected coordinates, and is normalised to a
         * 1-metre reference size. 2 km (a 4 km model) is far beyond any real site or building, so
         * true-scale models are never shrunk; the old 50 m threshold shrank anything over 100 m.
         * Every load logs MODEL_SCALE with the resulting world size and the cause of any non-1 scale.
         */
        private const val BB_NORMALIZE_THRESHOLD_M = 2000.0f
        /**
         * Bounding-box centre threshold (metres) above which the model's geometry is considered
         * offset from its local origin and the centre is snapped to the anchor position.
         * Models pivoted intentionally at their base (small offset) are left unchanged.
         */
        private const val BB_CENTER_THRESHOLD_M = 1.0f
    }
}
