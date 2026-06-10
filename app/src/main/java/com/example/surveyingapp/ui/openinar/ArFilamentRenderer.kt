package com.example.surveyingapp.ui.openinar

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
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Renders GLB models at ARCore geospatial anchor positions using Filament.
 *
 * Attaches to a [TextureView] with [isOpaque] = false that sits on top of the
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
 * All public methods ([init], [preload], [renderFrame], [destroy]) **must be
 * called from the main thread** (the Choreographer callback or Fragment lifecycle).
 * File I/O inside [preload] is dispatched to [Dispatchers.IO] automatically.
 *
 * ## Typical lifecycle
 * ```
 * onViewCreated  → init(textureView)
 * setCoordinates → preload(key, filePath, lifecycleScope)
 * Choreographer  → renderFrame(frameTimeNs, viewMatrix, projMatrix, poses)
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
        /** Frame counter: entities are added to scene after the first asyncUpdateLoad() call. */
        var framesUpdated: Int = 0
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

    private var initialized = false

    // -------------------------------------------------------------------------
    // Debug / diagnostics

    enum class ModelLoadState { NOT_REQUESTED, LOADING, IN_SCENE }

    /** Returns the current load state for [key], safe to call from any thread. */
    fun modelLoadState(key: String): ModelLoadState {
        val cached = anchorAssets[key] ?: return ModelLoadState.NOT_REQUESTED
        return if (cached.framesUpdated >= 2) ModelLoadState.IN_SCENE else ModelLoadState.LOADING
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
    fun preload(key: String, filePath: String, scope: CoroutineScope) {
        if (!initialized || anchorAssets.containsKey(key)) return
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { File(filePath).readBytes() }
                withContext(Dispatchers.Main) {
                    if (!initialized) return@withContext
                    val buffer = ByteBuffer.wrap(bytes)
                    val asset = assetLoader.createAsset(buffer)
                    if (asset == null) {
                        Log.e(TAG, "createAsset returned null for $filePath")
                        return@withContext
                    }
                    resourceLoader.asyncBeginLoad(asset)
                    asset.releaseSourceData()
                    anchorAssets[key] = CachedAsset(asset)
                    Log.d(TAG, "Load started: $key → $filePath")
                }
            } catch (e: Exception) {
                Log.e(TAG, "preload failed for $filePath", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Rendering

    /**
     * Render one frame. Call from [android.view.Choreographer.FrameCallback.doFrame].
     *
     * @param frameTimeNs Choreographer frame time (nanoseconds).
     * @param viewMatrix  ARCore view matrix (world → camera, column-major float[16]).
     * @param projMatrix  ARCore projection matrix (column-major float[16]).
     * @param poses       Model poses to render; entries with unloaded/missing assets are skipped.
     */
    fun renderFrame(
        frameTimeNs: Long,
        viewMatrix: FloatArray,
        projMatrix: FloatArray,
        poses: List<ModelPose>
    ) {
        if (!initialized) return
        // UiHelper.isReadyToRender: true once the surface is available and sized.
        if (!uiHelper.isReadyToRender) return
        val sc = swapChain ?: return

        // Pump async resource loading for all cached assets.
        resourceLoader.asyncUpdateLoad()

        // Transition LOADING → READY: add entities after the first asyncUpdateLoad pass.
        for (pose in poses) {
            val cached = anchorAssets[pose.key] ?: continue
            if (cached.framesUpdated < 2) {
                cached.framesUpdated++
                if (cached.framesUpdated == 2) {
                    scene.addEntities(cached.asset.entities)
                    Log.d(TAG, "Model added to scene: ${pose.key}")
                } else continue
            }
            // framesUpdated >= 2: update transform
            val tm = engine.transformManager
            val ti = tm.getInstance(cached.asset.root)
            tm.setTransform(ti, pose.worldMatrix)
        }

        // Camera — ARCore gives world→camera; Filament camera.setModelMatrix wants camera→world.
        val cameraWorld = FloatArray(16)
        Matrix.invertM(cameraWorld, 0, viewMatrix, 0)
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
     * Engine.destroy() is deferred by 50 ms (same workaround as [ModelViewerActivity])
     * to avoid the SIGABRT that occurs when the deferred UiHelper cleanup message runs
     * after the engine has already been destroyed.
     */
    fun destroy() {
        if (!initialized) return
        initialized = false

        resourceLoader.asyncCancelLoad()
        anchorAssets.values.forEach { cached ->
            scene.removeEntities(cached.asset.entities)
            assetLoader.destroyAsset(cached.asset)
        }
        anchorAssets.clear()

        if (sunEntity != 0) {
            scene.removeEntity(sunEntity)
            engine.lightManager.destroy(sunEntity)
            EntityManager.get().destroy(sunEntity)
            sunEntity = 0
        }

        // Detach UiHelper — it will call onDetachedFromSurface() → destroySwapChain().
        uiHelper.detach()

        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroyScene(scene)

        // Defer engine.destroy() so any queued Filament main-thread messages
        // can run while the engine is still alive (mirrors ModelViewerActivity pattern).
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
        private const val NEAR_PLANE = 0.01
        private const val FAR_PLANE  = 1000.0
    }
}
