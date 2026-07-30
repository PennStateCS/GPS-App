package app.surrealar.ui.openinar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.os.Bundle
import app.surrealar.util.ArSessionDiagnostics
import app.surrealar.util.DiagnosticsLogger
import app.surrealar.util.argbIntToRgba
import app.surrealar.util.bearingDeg
import app.surrealar.util.bearingToCompass
import app.surrealar.util.formatDist
import app.surrealar.util.haversineM

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import app.surrealar.R
import app.surrealar.databinding.FragmentOpenInArBinding
import app.surrealar.domain.repository.SettingsRepository
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.gnss.mock.MockInjectionStatus
import app.surrealar.gnss.model.Fix
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * AR Fragment that displays surveying coordinates as geospatial anchors in AR space.
 * Uses GNSS fixes from FixSwitchboard for accurate geospatial positioning.
 */
@AndroidEntryPoint
@SuppressLint("SetTextI18n")
class OpenInARFragment : Fragment(), GLSurfaceView.Renderer {

    private var _binding: FragmentOpenInArBinding? = null
    private val binding get() = _binding!!

    // Inject GNSS switchboard for current GPS location
    @Inject
    lateinit var fixSwitchboard: FixSwitchboard

    /** Read-only access to the selected GNSS source and mock-location setting for AR diagnostics. */
    @Inject
    lateinit var settingsRepo: SettingsRepository

    private val viewModel: OpenInARViewModel by viewModels()

    // Current GNSS fix from switchboard (surveying-grade GPS)
    @Volatile
    private var currentGnssFix: Fix? = null
    /** Last logged GNSS provider — used to suppress repeated identical fix logs. */
    private var lastLoggedProvider: app.surrealar.gnss.model.Provider? = null

    // Source/mock context for AR diagnostics (collected on the main thread; read anywhere).
    @Volatile private var selectedLocationSource: String? = null
    @Volatile private var mockLocationEnabled: Boolean? = null
    /** Guards the one-per-AR-session diagnostic header. Reset in onCreateView. */
    private var loggedArSessionHeader = false

    // ARCore session management
    private var session: Session? = null
    private var installRequested = false
    private var availabilityPolling = false
    /**
     * True only after `session.resume()` has been called successfully.
     * Guards `onDrawFrame` against calling `Session.update` on a paused session —
     * the GL thread starts as soon as the surface is created, which can be before
     * [onResume] fires (causing AR_ERROR_SESSION_PAUSED spam otherwise).
     */
    @Volatile private var sessionReady = false

    /**
     * True once the session has been configured with geospatial mode ENABLED. When false, the device
     * either doesn't support geospatial or enabling it failed — every geo-anchor will silently fail,
     * so this drives a clearer status string and is the first thing recorded in the diagnostic export.
     */
    @Volatile private var geospatialAvailable = true

    // OpenGL renderers for different AR elements
    private var backgroundRenderer: BackgroundRenderer? = null  // Camera background (also owns OES texture)
    private var cubeRenderer: SimpleObjectRenderer? = null      // 3D objects/pins
    private var planeVisualizer: PlaneVisualizer? = null        // Detected planes
    private var pointCloudRenderer: PointCloudRenderer? = null  // Point cloud visualization
    // Filament-based renderer for GLB 3D models overlaid on the AR camera feed
    private var filamentRenderer: ArFilamentRenderer? = null

    // Data class representing a geospatial item to display in AR.
    // `label` removed — coord name is always read from coordWithModel.coordinate.name directly.
    private data class GeoItem(
        val lat: Double,
        val lng: Double,
        val alt: Double?,
        val rgba: FloatArray,
        val modelFilePath: String?,
        val modelId: String?,
        /** Full coordinate + model data — used for tap-to-inspect and label text. */
        val coordWithModel: CoordWithModel
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as GeoItem
            return lat == other.lat &&
                    lng == other.lng &&
                    alt == other.alt &&
                    rgba.contentEquals(other.rgba) &&
                    modelFilePath == other.modelFilePath &&
                    modelId == other.modelId &&
                    coordWithModel.coordinate.id == other.coordWithModel.coordinate.id
        }

        override fun hashCode(): Int {
            var result = lat.hashCode()
            result = 31 * result + lng.hashCode()
            result = 31 * result + (alt?.hashCode() ?: 0)
            result = 31 * result + rgba.contentHashCode()
            result = 31 * result + (modelFilePath?.hashCode() ?: 0)
            result = 31 * result + (modelId?.hashCode() ?: 0)
            result = 31 * result + coordWithModel.coordinate.id.hashCode()
            return result
        }
    }

    // Collections for managing geospatial anchors.
    // @Volatile + immutable List: main thread assigns a new snapshot; GL thread reads it safely.
    @Volatile private var geoItems: List<GeoItem> = emptyList()

    /**
     * Tracks each live ARCore geospatial anchor alongside its rendering metadata.
     *
     * Not a data class — [rgba] is a FloatArray and this type is never compared by value.
     * [rgba] is eagerly pre-computed from `coordWithModel.coordinate.color` so it is only
     * allocated once per anchor rather than on every render frame.
     * [modelFilePath] and [modelId] are derived from [coordWithModel] via properties.
     */
    private class AnchorEntry(
        val anchor: Anchor,
        val coordWithModel: CoordWithModel
    ) {
        /** Pre-computed normalised RGBA for GLES pin rendering. */
        val rgba: FloatArray = argbIntToRgba(coordWithModel.coordinate.color)
        val modelFilePath: String? get() = coordWithModel.modelFilePath
        val modelId: String?       get() = coordWithModel.modelId
    }

    // geoAnchors is read on the GL thread and mutated on the main thread (onDestroyView,
    // rebuildGeoAnchorsIfNeeded). CopyOnWriteArrayList makes iteration on the GL thread
    // safe without locking — writes create a new backing array so readers never block.
    private val geoAnchors: MutableList<AnchorEntry> = CopyOnWriteArrayList()
    // @Volatile: written on main thread (setCoordinates), read on GL thread (rebuildGeoAnchorsIfNeeded).
    @Volatile private var geoAnchorsCreated = false

    // All debug test-anchor logic is encapsulated in TestAnchorController.
    private lateinit var testAnchorController: TestAnchorController

    // OpenGL transformation matrices
    private val proj = FloatArray(16)    // Projection matrix
    private val view = FloatArray(16)    // View matrix
    private val model = FloatArray(16)   // Model matrix
    private val modelScaled = FloatArray(16)  // Scaled model matrix
    private val vp = FloatArray(16)      // View-projection matrix
    private val mvp = FloatArray(16)     // Model-view-projection matrix

    // Display rotation and surface dimensions — written on main thread, read on GL thread.
    @Volatile private var displayRotation: Int = Surface.ROTATION_0
    @Volatile private var surfaceWidth: Int = 0
    @Volatile private var surfaceHeight: Int = 0

    // Cached 2-D screen positions of visible geospatial pins.
    // CoordWithModel is embedded so the touch listener never needs to access geoAnchors.
    // Written by GL thread each frame; read by main-thread touch listener (volatile).
    private data class PinScreenEntry(val x: Float, val y: Float, val coordWithModel: CoordWithModel)
    @Volatile private var pinScreenCache: List<PinScreenEntry> = emptyList()

    /**
     * Snapshot of ARCore Earth state extracted on the GL thread before posting to the main thread.
     * ARCore native objects ([Earth], [GeospatialPose]) must NOT be accessed from the main thread;
     * only primitive/string values extracted here are safe to use in [buildDebugText].
     */
    private data class EarthDebugSnapshot(
        val earthStateName: String,
        val camLat: Double?,
        val camLon: Double?,
        val camAlt: Double?,
        val camHeading: Double?
    )

    // Last label set posted to the overlay — used to skip redundant post() calls (60fps).
    @Volatile private var lastPostedLabels: List<CoordinateLabelOverlay.LabelEntry> = emptyList()

    // Last off-screen arrow set posted — used to skip redundant invalidations.
    @Volatile private var lastPostedArrows: List<OffScreenPinIndicatorOverlay.ArrowEntry> = emptyList()

    // Last model progress (inScene, total) — skip chip update when unchanged.
    @Volatile private var lastModelProgress: Pair<Int, Int>? = null
    // Coroutine job that hides the chip after all models finish loading.
    private var hideModelProgressJob: kotlinx.coroutines.Job? = null

    // Set true on main thread (button click); consumed on GL thread to detach & rebuild anchors.
    @Volatile private var shouldRebuildAnchors = false

    /**
     * RTK status at the time the current set of geo-anchors was created.
     * Used to auto re-anchor when quality improves (e.g., SINGLE → RTK FIX).
     * Written and read exclusively on the GL thread.
     */
    private var lastAnchorRtkStatus: app.surrealar.gnss.model.RtkStatus? = null

    /**
     * Timestamp (from [System.currentTimeMillis]) of the last anchor rebuild (manual or auto).
     * Enforces [REANCHOR_COOLDOWN_MS] to prevent ARCore resource exhaustion when RTK quality
     * steps up rapidly (e.g., SINGLE → DGPS → FLOAT → FIX in <500ms when switching to RS2+).
     * Written and read exclusively on the GL thread.
     */
    private var lastReanchorTimeMs: Long = 0L

    /**
     * True once the GNSS-accuracy gate has logged a skip for the current blocked period.
     * Prevents [rebuildGeoAnchorsIfNeeded] (which runs every GL frame until anchors are created)
     * from logging the same "accuracy too poor" skip on every frame. Reset on successful
     * anchor creation and on manual/auto re-anchor. GL-thread only.
     */
    private var loggedAccuracyGate = false

    /**
     * Wall-clock time (ms) when ARCore Earth most recently entered TRACKING (0 = not tracking).
     * Drives the [GeoAnchorGate] localization timeout. GL-thread only.
     */
    private var earthTrackingSinceMs: Long = 0L

    /**
     * ARCore horizontal accuracy (m) captured when the current anchors were created. Used to decide
     * an auto re-anchor once localization materially improves (see [GeoAnchorGate.shouldReanchorOnImprovement]).
     * Null until anchors are created. GL-thread only.
     */
    private var earthAccuracyAtCreationM: Double? = null

    /** True while holding placement waiting for AR localization — surfaces "Localizing…" status. GL-thread only. */
    @Volatile private var awaitingLocalization = false

    /** Log the "waiting for AR localization" skip once per blocked period (avoids per-frame spam). GL-thread only. */
    private var loggedLocalizationWait = false

    /** Throttle for the per-frame Earth-accuracy improvement check. GL-thread only. */
    private var lastImprovementCheckMs: Long = 0L

    /**
     * Volatile snapshot of the ViewModel's distanceFilterM — updated via StateFlow collection
     * on the main thread, read on the GL thread each frame.
     */
    @Volatile private var distanceFilterM: Double? = null

    /**
     * Coordinate IDs hidden by the distance filter on the last frame. Used by [collectGeoAnchors]
     * to log a "distance filter" skip only when the filtered set changes, never every frame.
     * GL-thread only.
     */
    private var lastDistanceSkippedIds: Set<String> = emptySet()

    // AR/model session diagnostics are accumulated in the process-wide ArSessionDiagnostics holder
    // (written to ar-last-session.txt on close, and read by model-integrity.txt). These two fields
    // only hold the last-seen tracking states so we log/record CHANGES rather than every frame.
    private var lastEarthTrackingLogged: String? = null
    private var lastCameraTrackingLogged: String? = null

    /** How the current anchors were placed ("localized"/"timeout"); null until placed. GL-thread only. */
    @Volatile private var lastPlacedVia: String? = null
    /** Last AR-view-quality level surfaced to the user — logs quality transitions. GL-thread only. */
    private var lastLoggedQualityLevel: ArViewQuality.Level? = null


    companion object {
        private const val TAG = "OpenInARFragment"
        /**
         * Shared AR/model diagnostic tag. Reuses [ArFilamentRenderer.DIAG] ("AR_MDL") so the
         * fragment and the Filament renderer log under one greppable tag, and routes through
         * [DiagnosticsLogger] so the entries land in the exported diagnostic report (Logcat-only
         * `android.util.Log` calls never reach the export ZIP).
         */
        private const val DIAG = ArFilamentRenderer.DIAG
        /** Minimum GNSS horizontal accuracy required before creating geospatial anchors. */
        private const val MAX_GNSS_ACCURACY_M = 20.0
        /**
         * Default uniform scale applied to every GLB model rendered via Filament.
         * 1.0 = real-world metric scale (correct for BIM/survey models exported in metres).
         * Increase only if your models were exported at a non-metric unit (e.g. 0.01 for cm).
         * This value is folded into ModelPose.coordScale and applied inside ArFilamentRenderer
         * so the correction matrix, normalization threshold, and placement all use the
         * correct effective scale.
         */
        private const val MODEL_SCALE = 1f
        /** Near/far clip planes — must match [ArFilamentRenderer] constants. */
        private const val NEAR_CLIP = 0.1f
        private const val FAR_CLIP  = 2000f
        /**
         * Minimum milliseconds between any two anchor rebuilds (manual button resets this).
         * Prevents ARCore resource exhaustion when RTK quality steps through SINGLE→DGPS→FLOAT→FIX
         * in rapid succession after switching to an external receiver like the RS2+.
         */
        private const val REANCHOR_COOLDOWN_MS = 30_000L
    }

    /**
     * When false (default): real coordinate anchors are placed at their stored ellipsoidal altitude.
     * When true (terrain mode): all anchors are resolved onto the detected ground via
     * `earth.resolveAnchorOnTerrain(lat, lng, 0.0, …)` (ARCore downloads the terrain elevation and
     * self-updates the anchor to TRACKING), ignoring stored altitudes.
     * Written on the main thread (button click); read on the GL thread (rebuildGeoAnchorsIfNeeded).
     */
    @Volatile private var useTerrainAltitude: Boolean = false
    @Volatile private var arModelScale: Float = MODEL_SCALE
    @Volatile private var arShowLabels: Boolean = true
    @Volatile private var arShowOffscreenArrows: Boolean = true
    @Volatile private var arDebugToolsEnabled: Boolean = false
    @Volatile private var arShowPlanes: Boolean = false
    @Volatile private var arShowPointCloud: Boolean = false

    /**
     * Edge margin in pixels for off-screen arrow indicators.
     * Computed once in [onSurfaceChanged] (density never changes at runtime).
     */
    @Volatile private var edgeMarginPx: Float = 0f

    // Drives Filament rendering at display refresh rate on the main thread.
    private var choreographerInstance: android.view.Choreographer? = null
    private var choreographerFrameCount = 0L
    // True if the previous Choreographer tick had at least one model pose.
    // Used to ensure one transparent "clear" frame is rendered after models are removed
    // so the Filament TextureView does not retain a stale image of the last rendered pins.
    private var choreoPrevHadModels = false
    private val filamentFrameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNs: Long) {
            choreographerInstance?.postFrameCallback(this)
            choreographerFrameCount++
            // Read one consistent snapshot: view matrix, proj matrix, model poses, and visible-keys
            // are all from the SAME GL frame (written atomically after collectGeoAnchors).
            val snapshot = arRenderSnapshot
            val currentPoses    = snapshot?.modelPoses    ?: emptyList()
            val currentVisibleKeys = snapshot?.visibleKeys ?: emptySet()
            // Always pump asset loading / scene management so models load as soon as
            // preload() is called — even before ARCore anchors start tracking or the
            // Filament TextureView surface has been fully created.
            filamentRenderer?.tickAndApplyLoads(currentPoses, currentVisibleKeys)
            // Keep rendering while any model is visible — a visible model whose anchor briefly stopped
            // tracking has no pose this frame but stays in the scene holding its last transform.
            val hasModels = currentPoses.isNotEmpty() || currentVisibleKeys.isNotEmpty()
            // Skip GPU rendering when no models are present and none were present last frame.
            // When models are just cleared (choreoPrevHadModels=true, hasModels=false), we
            // fall through and render one transparent frame so Filament clears the TextureView.
            if (!hasModels && !choreoPrevHadModels) {
                if ((choreographerFrameCount % 300L) == 0L)
                    android.util.Log.d(ArFilamentRenderer.DIAG,
                        "Choreographer#$choreographerFrameCount — modelPoses empty (no anchors tracking or no models)")
                return
            }
            val vm = snapshot?.viewMatrix ?: return
            val pm = snapshot.projMatrix
            filamentRenderer?.renderFrame(frameTimeNs, vm, pm)
            // Update only after a successful render so we retry the clear frame if
            // camera matrices were unavailable this tick.
            choreoPrevHadModels = hasModels
        }
    }

    /**
     * Atomic snapshot of the three values the Choreographer needs to drive Filament for one frame.
     * Written as a single `@Volatile` reference on the GL thread after `collectGeoAnchors` so the
     * camera matrix and model poses are always from the same ARCore frame — eliminating the race
     * that occurred when the Choreographer read the three former independent `@Volatile` fields at
     * different moments and could combine a view matrix from frame N+1 with poses from frame N.
     */
    private data class ArRenderSnapshot(
        val viewMatrix: FloatArray,
        val projMatrix: FloatArray,
        val modelPoses: List<ArFilamentRenderer.ModelPose>,
        val visibleKeys: Set<String>
    )
    @Volatile private var arRenderSnapshot: ArRenderSnapshot? = null

    @Volatile private var arVisibleIdsMirror: Set<String> = emptySet()
    @Volatile private var arVisibilityModeMirror: ArVisibilityMode = ArVisibilityMode.SELECTED
    /** Last visible-model count logged (rendered set change → diagnostics). GL-thread only. */
    private var lastLoggedVisibleCount: Int = -1
    /**
     * The set of coordinate IDs whose models are currently visible in the Filament scene.
     * Written by [collectGeoAnchors] on the GL thread after each frame's visibility pass;
     * read on the same GL thread when building [arRenderSnapshot].
     * Also read by [tickAndApplyLoads] via the snapshot, so this field is only needed
     * as a GL-thread-local staging field — it is never read from another thread.
     */
    private var modelVisibleKeys: Set<String> = emptySet()

    // State flags for error recovery and user experience
    private var hasWarnedLocationOff = false
    private var hasWarnedNoNetwork = false

    // Permission request launchers using modern Activity Result API
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // The result can be delivered after the view is gone — guard binding access.
            if (granted) checkAvailabilityAndInstall()
            else _binding?.textArStatus?.text = getString(R.string.camera_permission_denied)
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) _binding?.textArStatus?.text = getString(R.string.location_permission_needed)
        }


    // Track previous brightness so we can restore it on pause
    private var previousBrightness = -1f
    // Mirrors ViewModel's debugVisible StateFlow; initialised to false to match the ViewModel default.
    private var debugOverlayVisible = false

    // Whether the floating toolbar is currently visible.
    private var toolbarVisible = false

    // ---------------------------------------------------------------------------------------------
    // Fragment Lifecycle Methods

    /**
     * Initialize view and AR components
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOpenInArBinding.inflate(inflater, container, false)
        ArSessionDiagnostics.startSession()
        lastEarthTrackingLogged = null
        lastCameraTrackingLogged = null
        loggedArSessionHeader = false
        lastPlacedVia = null
        lastLoggedQualityLevel = null
        DiagnosticsLogger.i(DIAG, "OpenInARFragment created — AR screen opened")
        setupGlSurface()
        binding.textArStatus.text = getString(R.string.checking_ar_availability)
        return binding.root
    }

    /**
     * Attach observer for coordinate data and check AR availability
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        testAnchorController = TestAnchorController(requireContext())
        // Guard Filament init: a native/engine init failure (e.g. missing GLES features on an odd
        // device) must not take down the whole AR screen. On failure the renderer stays null and
        // every `filamentRenderer?.` call no-ops, so GLES pins/labels still work — only GLB models
        // are unavailable. Throwable (not Exception) to also catch UnsatisfiedLinkError.
        filamentRenderer = try {
            ArFilamentRenderer().also { renderer ->
                renderer.init(binding.filamentSurface)
                // Surface the "geometry far from origin" warning as an in-app snackbar.
                // Fires once per affected model when it is first added to the Filament scene.
                // Uses a set so each model file is only warned about once per session.
                val warnedModels = mutableSetOf<String>()
                renderer.onModelPlacementWarning = { coordId, modelFileName, originToBottomCenterM ->
                    if (warnedModels.add(modelFileName)) {
                        val msg = "\"$modelFileName\": geometry is " +
                            "${"%.0f".format(originToBottomCenterM)} m from its origin — " +
                            "try Bottom Center or Custom Offset placement"
                        _binding?.root?.let { root ->
                            com.google.android.material.snackbar.Snackbar
                                .make(root, msg, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                                .show()
                        }
                        DiagnosticsLogger.w(DIAG, "MODEL_PLACEMENT_WARN_UI shown coordId=$coordId " +
                            "model=$modelFileName originToBottomCenterM=${"%.1f".format(originToBottomCenterM)}")
                    }
                }
            }
        } catch (t: Throwable) {
            DiagnosticsLogger.e(DIAG, "Filament renderer init failed — 3D models disabled, " +
                "pins/labels still render: ${t.message}", t)
            null
        }

        // Prepare the test-pin asset and preload its keys once ready.
        testAnchorController.prepareAsset(viewLifecycleOwner.lifecycleScope) { _ ->
            filamentRenderer?.let { renderer ->
                testAnchorController.preloadModelKeys(renderer, viewLifecycleOwner.lifecycleScope)
            }
        }

        checkAvailabilityAndInstall()

        // Back button — always visible, exits AR and returns to previous destination.
        binding.btnArBack.setOnClickListener {
            DiagnosticsLogger.i(DIAG, "Back pressed — exiting AR")
            // findNavController() can throw if the view is mid-teardown; never let the exit button crash.
            runCatching { findNavController().popBackStack() }
                .onFailure { DiagnosticsLogger.w(DIAG, "popBackStack failed on AR exit: ${it.message}", it) }
        }

        // Toolbar toggle — show/hide with a slide-in animation from the right edge (UI chrome, not logged).
        binding.btnToolbarToggle.setOnClickListener {
            if (toolbarVisible) hideToolbar() else showToolbar()
        }

        binding.btnSpawnTestPoints.setOnClickListener {
            if (testAnchorController.isActive) {
                DiagnosticsLogger.i(DIAG, "Test points: clear requested by user")
                testAnchorController.requestClear()
                binding.txtTestPoints.text = "Test"
            } else {
                DiagnosticsLogger.i(DIAG, "Test points: spawn requested by user")
                val r = filamentRenderer
                if (r != null) testAnchorController.preloadModelKeys(r, viewLifecycleOwner.lifecycleScope)
                else DiagnosticsLogger.w(DIAG, "Test points: renderer unavailable — pins spawn without 3D models")
                testAnchorController.requestSpawn()
                binding.txtTestPoints.text = "Clear"
            }
        }

        binding.btnToggleDebugOverlay.setOnClickListener {
            DiagnosticsLogger.i(DIAG, "Debug overlay ${if (!debugOverlayVisible) "ON" else "OFF"} (user)")
            viewModel.toggleDebug()
        }

        binding.btnTogglePlanes.setOnClickListener {
            DiagnosticsLogger.i(DIAG, "AR planes overlay ${if (!arShowPlanes) "ON" else "OFF"} (user)")
            viewModel.togglePlanes()
        }

        binding.btnTogglePointCloud.setOnClickListener {
            DiagnosticsLogger.i(DIAG, "AR point cloud overlay ${if (!arShowPointCloud) "ON" else "OFF"} (user)")
            viewModel.togglePointCloud()
        }

        binding.btnReanchor.setOnClickListener {
            shouldRebuildAnchors = true
            binding.btnReanchor.isEnabled = false
            DiagnosticsLogger.i(DIAG, "Re-anchor requested by user — anchors will rebuild")
            // Re-enable on a lifecycle-bound coroutine instead of View.postDelayed so the
            // callback is cancelled when the view is destroyed. The old postDelayed dereferenced
            // `binding` (a non-null !! accessor) 2s later and crashed if the user left AR first.
            viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.delay(2000)
                _binding?.btnReanchor?.isEnabled = true
            }
        }

        // The former distance-filter button now opens the model-visibility sheet (range moves inside it).
        binding.btnDistanceFilter.setOnClickListener {
            DiagnosticsLogger.i(DIAG, "Model visibility sheet opened by user")
            ArModelsBottomSheet.show(this)
        }

        binding.btnAltitudeMode.setOnClickListener {
            // Persist through the ViewModel so the choice survives pause/resume and stays in sync with
            // the Settings screen; the arDisplaySettings collector then applies it (updates the flag,
            // the button text, and triggers a rebuild). Previously this flipped a transient local flag
            // that the collector silently reverted on the next settings emission.
            val next = !useTerrainAltitude
            DiagnosticsLogger.i(DIAG, "Altitude mode set to ${if (next) "TERRAIN" else "STORED"} by user")
            viewModel.setAltitudeMode(next)
        }


        // Observe coordinate + model data from ViewModel.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.coordsWithModels.collect { items -> setCoordinates(items) }
            }
        }

        // Observe GNSS fixes from switchboard for current GPS location.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                fixSwitchboard.fixes.collect { fix ->
                    currentGnssFix = fix
                    logArSessionHeaderOnce()
                    updateArGnssChip(fix)
                    // Only log when the provider changes — fixes arrive at up to 10Hz
                    // and logging every one would flood logcat.
                    if (fix.provider != lastLoggedProvider) {
                        lastLoggedProvider = fix.provider
                        android.util.Log.d(TAG, "GNSS provider changed: ${fix.provider}, lat=${fix.latDeg}, lon=${fix.lonDeg}")
                    }
                }
            }
        }

        // Track the selected GNSS source (INTERNAL/EXTERNAL) for AR diagnostics.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepo.locationSource.collect { selectedLocationSource = it.name }
            }
        }

        // Track the mock-location setting for AR diagnostics (does ARCore/Android see a mocked provider?).
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                settingsRepo.mockLocationEnabled.collect { mockLocationEnabled = it }
            }
        }

        // Keep local volatile copy of distance filter in sync for the GL thread.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.distanceFilterM.collect { distanceFilterM = it }
            }
        }

        // Toolbar "Models" label reflects the current visibility mode + shown count.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.arModelsSummary.collect { s ->
                    binding.txtDistanceFilter.text = when (s.mode) {
                        ArVisibilityMode.SELECTED -> "${s.shown} shown"
                        ArVisibilityMode.NEARBY   -> "${s.shown} nearby"
                        ArVisibilityMode.ALL      -> "All ${s.total}"
                    }
                }
            }
        }

        // Mirror AR visibility mode + effective visible set for the GL-thread render gate.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.arVisibilityMode.collect { arVisibilityModeMirror = it }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effectiveVisibleIds.collect { arVisibleIdsMirror = it }
            }
        }

        // Sync debug overlay visibility from ViewModel (survives rotation).
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.debugVisible.collect { visible ->
                    debugOverlayVisible = visible
                    binding.debugPanel.visibility = if (visible) View.VISIBLE else View.GONE
                    binding.txtDebugOverlay.text = if (visible) "Hide" else "Debug"
                }
            }
        }

        // Sync planes toggle from ViewModel.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.showPlanes.collect { on ->
                    arShowPlanes = on
                    val tint = if (on) requireContext().getColor(R.color.app_info)
                               else    requireContext().getColor(android.R.color.white)
                    binding.iconTogglePlanes.setColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN)
                }
            }
        }

        // Sync point cloud toggle from ViewModel.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.showPointCloud.collect { on ->
                    arShowPointCloud = on
                    val tint = if (on) requireContext().getColor(R.color.app_info)
                               else    requireContext().getColor(android.R.color.white)
                    binding.iconTogglePointCloud.setColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN)
                }
            }
        }

        // React to high-accuracy GPS preference changes from the ViewModel.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.highAccuracyEnabled.collect { wantHigh ->
                    if (!isAdded || _binding == null) return@collect
                    if (wantHigh) {
                        val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                            binding.textArStatus.text = "Enable GPS for high accuracy AR positioning"
                        }
                    }
                }
            }
        }

        // Apply AR Display settings (altitude mode, model scale, label/arrow visibility).
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.arDisplaySettings.collect { ar ->
                    val terrain = ar.altitudeMode == "TERRAIN"
                    if (terrain != useTerrainAltitude) {
                        useTerrainAltitude = terrain
                        shouldRebuildAnchors = true
                        _binding?.txtAltitudeMode?.text = if (terrain) "Terrain" else "Stored"
                    }
                    arModelScale = ar.modelScale
                    arShowLabels = ar.showLabels
                    arShowOffscreenArrows = ar.showOffscreenArrows
                    arDebugToolsEnabled = ar.showArDebugTools
                    _binding?.arDebugToolsGroup?.visibility =
                        if (ar.showArDebugTools) View.VISIBLE else View.GONE
                    if (!ar.showArDebugTools) {
                        // Force debug visuals off when the master switch is disabled
                        arShowPlanes = false
                        arShowPointCloud = false
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Floating toolbar show / hide

    /**
     * Slides the toolbar card in from the right edge and makes it visible.
     * The toggle button icon rotates to signal the open state.
     */
    private fun showToolbar() {
        val toolbar = binding.arFloatingToolbar
        toolbar.translationX = toolbar.width.toFloat().coerceAtLeast(200f)
        toolbar.alpha = 0f
        toolbar.visibility = View.VISIBLE
        toolbar.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        binding.btnToolbarToggle.animate()
            .rotation(90f)
            .setDuration(200)
            .start()
        binding.btnToolbarToggle.contentDescription = "Hide AR tools"
        toolbarVisible = true
    }

    /**
     * Slides the toolbar card back out to the right and hides it when the animation ends.
     */
    private fun hideToolbar() {
        val toolbar = binding.arFloatingToolbar
        toolbar.animate()
            .translationX(toolbar.width.toFloat().coerceAtLeast(200f))
            .alpha(0f)
            .setDuration(160)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { toolbar.visibility = View.GONE }
            .start()
        binding.btnToolbarToggle.animate()
            .rotation(0f)
            .setDuration(160)
            .start()
        binding.btnToolbarToggle.contentDescription = "Show AR tools"
        toolbarVisible = false
    }

    // ---------------------------------------------------------------------------------------------
    // Display rotation helper

    /**
     * Returns the current display rotation constant (Surface.ROTATION_*).
     * Called on the main thread; result is stored in [displayRotation] for GL thread use.
     */
    private fun readDisplayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requireContext().display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            (requireContext().getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.rotation
        }

    /**
     * Resume AR session and check all prerequisites
     */
    override fun onResume() {
        super.onResume()
        android.util.Log.d(TAG, "onResume called")

        displayRotation = readDisplayRotation()
        android.util.Log.d(TAG, "Display rotation: $displayRotation")

        if (!checkAndRequestCameraPermission()) {
            android.util.Log.w(TAG, "Camera permission not granted")
            return
        }
        if (!checkAndRequestLocationPermission()) {
            android.util.Log.w(TAG, "Location permission not granted")
            return
        }

        if (session == null) {
            android.util.Log.d(TAG, "Creating new AR session")
            checkAvailabilityAndInstall()
        }

        val locationEnabled = isLocationServicesEnabled()
        val networkAvailable = hasNetwork()
        android.util.Log.d(TAG, "Location enabled: $locationEnabled, Network: $networkAvailable")

        if (!locationEnabled && !hasWarnedLocationOff) {
            hasWarnedLocationOff = true
            android.util.Log.w(TAG, "Location services disabled — geospatial won't work")
        }
        if (!networkAvailable && !hasWarnedNoNetwork) {
            hasWarnedNoNetwork = true
            android.util.Log.w(TAG, "No internet — geospatial won't localize")
        }

        // Resume AR session and related components
        try {
            session?.resume()
            sessionReady = true
            binding.glSurfaceViewAr.onResume()

            // Ensure the camera texture name is registered with the session.
            // onSurfaceCreated may have run before the session was created (first launch),
            // in which case the setCameraTextureName call there was a no-op. Calling it here
            // guarantees ARCore receives camera frames from the very first update() call,
            // preventing the "IMU buffer overflow / Last visual features at: 0 ns" condition.
            backgroundRenderer?.let { br ->
                session?.setCameraTextureName(br.textureId)
            }

            // Start Filament render loop (driven by display vsync)
            choreographerInstance = android.view.Choreographer.getInstance()
            choreographerInstance?.postFrameCallback(filamentFrameCallback)
            DiagnosticsLogger.i(DIAG, "AR session resumed — camera active, render loop started")
            binding.textArStatus.text = "AR camera active"

            val window = requireActivity().window
            val lp = window.attributes
            previousBrightness = lp.screenBrightness
            lp.screenBrightness = 1.0f
            window.attributes = lp
            android.util.Log.d(TAG, "Screen brightness set to maximum")
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            android.util.Log.d(TAG, "Screen wake lock enabled")
        } catch (e: CameraNotAvailableException) {
            DiagnosticsLogger.e(DIAG, "AR resume failed — camera unavailable", e)
            binding.textArStatus.text = getString(R.string.camera_unavailable)
            try { session?.pause() } catch (_: Exception) {}
        } catch (e: Exception) {
            DiagnosticsLogger.e(DIAG, "AR resume failed — ${e.message}", e)
            binding.textArStatus.text = "AR error: ${e.message}"
        }
    }

    /**
     * Called when the device rotates (because MainActivity declares configChanges).
     * The Fragment is NOT recreated — update displayRotation so the GL thread
     * picks up the new orientation on the next onDrawFrame call.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        displayRotation = readDisplayRotation()
        android.util.Log.d(TAG, "Config changed — display rotation: $displayRotation")
    }

    /**
     * Pause AR session and components
     */
    override fun onPause() {
        super.onPause()
        choreographerInstance?.removeFrameCallback(filamentFrameCallback)
        choreographerInstance = null
        sessionReady = false
        binding.glSurfaceViewAr.onPause()
        try { session?.pause() } catch (e: Exception) {
            DiagnosticsLogger.w(DIAG, "session.pause() failed on AR pause: ${e.message}", e)
        }

        // Reset one-shot warning flags so they re-fire if the user navigates away
        // and back while location/network state has changed.
        hasWarnedLocationOff = false
        hasWarnedNoNetwork   = false

        // Restore previous screen brightness and allow screen to sleep
        try {
            val window = requireActivity().window
            val lp = window.attributes
            lp.screenBrightness = previousBrightness
            window.attributes = lp
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            android.util.Log.d(TAG, "Screen brightness restored to $previousBrightness")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to restore brightness/sleep", e)
        }
    }

    /**
     * Clean up AR resources when view is destroyed
     */
    override fun onDestroyView() {
        super.onDestroyView()
        DiagnosticsLogger.i(DIAG, "OpenInARFragment destroyed — AR screen closed " +
            "(anchors=${geoAnchors.size}, modelPoses=${arRenderSnapshot?.modelPoses?.size ?: 0}, items=${geoItems.size}); " +
            "pending view-scoped callbacks cancelled")
        writeArSessionSummary()
        choreographerInstance?.removeFrameCallback(filamentFrameCallback)
        choreographerInstance = null
        choreoPrevHadModels = false
        filamentRenderer?.destroy()
        filamentRenderer = null
        arRenderSnapshot  = null
        pinScreenCache = emptyList()
        lastPostedLabels = emptyList()
        lastPostedArrows = emptyList()
        lastModelProgress = null
        hideModelProgressJob?.cancel()
        hideModelProgressJob = null
        // Clean up test anchors via controller
        testAnchorController.cleanup()
        // Clean up geospatial anchors
        for (entry in geoAnchors) try { entry.anchor.detach() } catch (_: Exception) {}
        geoAnchors.clear()
        geoAnchorsCreated = false
        lastAnchorRtkStatus = null
        lastReanchorTimeMs = 0L
        earthTrackingSinceMs = 0L
        earthAccuracyAtCreationM = null
        awaitingLocalization = false
        loggedLocalizationWait = false
        lastPlacedVia = null
        // Clean up AR session
        try { session?.close() } catch (_: Exception) {}
        session = null
        _binding = null
    }

    /**
     * Writes a compact summary of the just-ended AR/model session to `ar-last-session.txt`. Unlike
     * the rolling event log, this single file survives event-log rotation, so the export always has
     * a snapshot of the most recent session (counts only — not an event log).
     */
    private fun writeArSessionSummary() {
        if (!ArSessionDiagnostics.hasSession()) return
        ArSessionDiagnostics.endSession()
        DiagnosticsLogger.writeSessionSummary("ar-last-session.txt", ArSessionDiagnostics.buildSummaryText())
    }

    // ---------------------------------------------------------------------------------------------
    // Permission Management & ARCore Installation

    /**
     * Check camera permission and request if needed
     */
    private fun checkAndRequestCameraPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) true else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            false
        }
    }

    /**
     * Check location permission and request if needed
     */
    private fun checkAndRequestLocationPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            == PackageManager.PERMISSION_GRANTED
        ) true else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            false
        }
    }

    /**
     * Sets the AR status text only if the view still exists. Safe to call from async ARCore
     * availability/install callbacks, which can fire after onDestroyView() has cleared `_binding`.
     */
    private fun setArStatus(text: String) {
        _binding?.textArStatus?.text = text
    }

    /**
     * Create a new ARCore session with required configuration
     */
    private fun tryCreateSession() {
        try {
            val ses = Session(requireContext())
            session = ses
            configureSession()
        } catch (_: CameraNotAvailableException) {
            setArStatus(getString(R.string.camera_unavailable))
        } catch (e: Exception) {
            setArStatus("Session error: ${e.message ?: "Unknown"}")
        }
    }

    /**
     * Configure ARCore session with geospatial and other features
     */
    private fun configureSession() {
        val ses = session ?: return
        // Geospatial mode is what places anchors at real-world lat/lon. If it can't be enabled, every
        // geo-anchor silently fails — the #1 cause of "AR doesn't work" reports — so all three outcomes
        // are recorded in the diagnostic export (not just logcat), and the unavailable case is surfaced.
        var geoEnabled = false
        val config = Config(ses).apply {
            if (ses.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
                try {
                    geospatialMode = Config.GeospatialMode.ENABLED
                    geoEnabled = true
                    DiagnosticsLogger.i(DIAG, "Geospatial mode enabled")
                } catch (e: Exception) {
                    DiagnosticsLogger.e(DIAG, "Geospatial mode supported but failed to enable — " +
                        "coordinates cannot be anchored in AR: ${e.message}", e)
                }
            } else {
                DiagnosticsLogger.w(DIAG, "Geospatial mode NOT supported on this device — " +
                    "coordinates cannot be anchored in AR")
            }
            // Configure other AR features
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            focusMode = Config.FocusMode.AUTO
            lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            if (ses.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                depthMode = Config.DepthMode.AUTOMATIC
            }
            instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
        }
        try {
            ses.configure(config)
            if (backgroundRenderer != null) ses.setCameraTextureName(backgroundRenderer!!.textureId)
            geospatialAvailable = geoEnabled
            DiagnosticsLogger.i(DIAG, "AR session configured successfully (geospatial=$geoEnabled)")
        } catch (e: Exception) {
            DiagnosticsLogger.e(DIAG, "Error configuring AR session: ${e.message}", e)
            setArStatus("AR config error: ${e.message}")
        }
    }

    /**
     * Handle ARCore installation process
     */
    private fun checkInstallation() {
        try {
            val status =
                ArCoreApk.getInstance().requestInstall(requireActivity(), !installRequested)
            when (status) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    setArStatus("Requesting ARCore install...")
                }

                ArCoreApk.InstallStatus.INSTALLED -> tryCreateSession()
            }
        } catch (_: UnavailableUserDeclinedInstallationException) {
            setArStatus("ARCore install declined")
        } catch (e: Exception) {
            setArStatus("Install check failed: ${e.message ?: "Unknown"}")
        }
    }

    /**
     * Check ARCore availability and trigger installation if needed
     */
    private fun checkAvailabilityAndInstall() {
        // Reachable from async callbacks (permission result, availability poll). Bail out if the
        // fragment is detached or the view is gone so requireContext()/binding cannot crash.
        if (!isAdded || _binding == null) return
        val availability = ArCoreApk.getInstance().checkAvailability(requireContext())
        when (availability) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> tryCreateSession()
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> checkInstallation()

            ArCoreApk.Availability.UNKNOWN_CHECKING -> {
                // Poll until availability is determined. Use a lifecycle-bound coroutine instead
                // of View.postDelayed so the retry is cancelled on onDestroyView; the `_binding`
                // guard also stops checkAvailabilityAndInstall() (which touches binding) from
                // running after the view is gone — `isAdded` alone can still be true then.
                if (!availabilityPolling) {
                    availabilityPolling = true
                    viewLifecycleOwner.lifecycleScope.launch {
                        kotlinx.coroutines.delay(200)
                        availabilityPolling = false
                        if (isAdded && _binding != null) checkAvailabilityAndInstall()
                    }
                }
            }

            else -> {
                setArStatus(when (availability) {
                    ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> "Device not compatible"
                    else -> "AR not supported"
                })
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Geospatial Anchoring & Earth Tracking

    /**
     * Create geospatial anchors for all coordinate points when Earth tracking is stable.
     * [earth] is passed in from [processEarthAndAnchors] to avoid a redundant access
     * to the non-volatile [session] field from the GL thread.
     */
    private fun rebuildGeoAnchorsIfNeeded(earth: Earth) {
        if (geoAnchorsCreated || geoItems.isEmpty()) return
        if (earth.trackingState != TrackingState.TRACKING) return

        // Use GNSS fix from switchboard for positioning instead of ARCore's camera pose
        val gnssFix = currentGnssFix
        if (gnssFix == null) {
            // Not logging here — this fires every frame until a fix arrives;
            // the debug panel shows "⚠️ BLOCKER: currentGnssFix is null" instead.
            return
        }

        // Determine horizontal accuracy for gating.
        // Prefer GST-derived hAccM; fall back to a HDOP-based estimate so the gate
        // always has a value when GST sentences are absent (common with phone GPS).
        // UERE (User Equivalent Range Error) constants per fix quality:
        //   RTK FIX ≈ 0.02m/HDOP, RTK FLOAT ≈ 0.1m/HDOP, DGPS ≈ 0.5m/HDOP, SINGLE ≈ 3.0m/HDOP
        val accuracyM: Double? = gnssFix.hAccM ?: gnssFix.hDop?.let { hdop ->
            when (gnssFix.rtkStatus) {
                app.surrealar.gnss.model.RtkStatus.FIX    -> hdop * 0.02
                app.surrealar.gnss.model.RtkStatus.FLOAT  -> hdop * 0.1
                app.surrealar.gnss.model.RtkStatus.DGPS   -> hdop * 0.5
                else                                                  -> hdop * 3.0
            }
        }
        if (accuracyM != null && accuracyM > MAX_GNSS_ACCURACY_M) {
            // Log once per blocked period — this method runs every GL frame until anchors exist.
            if (!loggedAccuracyGate) {
                loggedAccuracyGate = true
                DiagnosticsLogger.w(DIAG, "anchor skip reason=\"earth_not_tracking/gnss_accuracy\" " +
                    "accuracyM=${"%.1f".format(accuracyM)} maxM=$MAX_GNSS_ACCURACY_M items=${geoItems.size}")
            }
            return
        }
        loggedAccuracyGate = false

        // ── ARCore Earth localization gate ──────────────────────────────────────────────────────
        // The GNSS gate above validates the RECEIVER, not ARCore's visual localization. TrackingState
        // == TRACKING alone can still be tens of metres off right after Earth first tracks, and
        // committing anchors then maps every coordinate to ≈ the device origin (all models on the
        // user). Wait until ARCore's own cameraGeospatialPose accuracy is good enough, or place after
        // a timeout so models still appear where VPS is unavailable.
        if (earthTrackingSinceMs == 0L) earthTrackingSinceMs = System.currentTimeMillis()
        val camAccPose = runCatching { earth.cameraGeospatialPose }.getOrNull()
        val arHAcc = camAccPose?.horizontalAccuracy ?: Double.POSITIVE_INFINITY
        val arYawAcc = camAccPose?.orientationYawAccuracy ?: Double.POSITIVE_INFINITY
        val trackingElapsed = System.currentTimeMillis() - earthTrackingSinceMs
        val gate = GeoAnchorGate.decide(arHAcc, arYawAcc, trackingElapsed)
        if (gate == GeoAnchorGate.Decision.WAIT) {
            awaitingLocalization = true
            if (!loggedLocalizationWait) {
                loggedLocalizationWait = true
                DiagnosticsLogger.w(DIAG, "anchor waiting reason=\"ar_localizing\" " +
                    "arHAccM=${"%.1f".format(arHAcc)} arYawDeg=${"%.1f".format(arYawAcc)} " +
                    "elapsedMs=$trackingElapsed items=${geoItems.size}")
            }
            return
        }
        awaitingLocalization = false
        loggedLocalizationWait = false
        // Remember the accuracy we committed at, so a later improvement can trigger a re-anchor.
        earthAccuracyAtCreationM = arHAcc.takeIf { it.isFinite() }
        val placedVia = if (gate == GeoAnchorGate.Decision.PLACE_TIMEOUT) "timeout" else "localized"

        // ── Diagnostics: the exact placement INPUTS (fix + Earth pose + mock/source state) ──────────
        // Lets a future export distinguish a good geospatial estimate from a low-confidence timeout,
        // and see which location source (incl. mock injection) was feeding ARCore. Missing accuracy is
        // logged as "unknown" — never 0.0. Runs once per (re)anchor, not per frame.
        val anchorMode = if (useTerrainAltitude) "TERRAIN" else "WGS84"
        val replacedPrevious = geoAnchors.isNotEmpty()
        val fixAgeMs = System.currentTimeMillis() - gnssFix.timeUtc.toEpochMilli()
        val earthVAcc = camAccPose?.verticalAccuracy?.takeIf { it.isFinite() }
        ArSessionDiagnostics.apply {
            arSelectedSource = selectedLocationSource
            arCurrentFixProvider = gnssFix.provider.name
            arMockEnabledDuringAr = mockLocationEnabled
            arMockInjectionActiveDuringAr = MockInjectionStatus.providerActive
            arMockAppApproved = isMockLocationAppApproved()
            arLastMockFixAgeAtAnchorMs = MockInjectionStatus.lastSourceFixAgeMs()
            arEarthTrackingAtAnchor = earth.trackingState.name
            arHAccAtAnchorM = arHAcc.takeIf { it.isFinite() }
            arVAccAtAnchorM = earthVAcc
            arYawAccAtAnchorDeg = arYawAcc.takeIf { it.isFinite() }
            if (placedVia == "timeout") anchorsViaTimeout += 1
            modelTransformSource = "anchor-driven"
        }
        DiagnosticsLogger.i(DIAG, "ANCHOR_PLACEMENT_INPUT anchorMode=$anchorMode placementReason=$placedVia " +
            "replacedPrevious=$replacedPrevious items=${geoItems.size} " +
            "selectedSource=${selectedLocationSource ?: "unknown"} " +
            "fixProvider=${gnssFix.provider} fixAgeMs=$fixAgeMs " +
            "fixLat=${"%.7f".format(gnssFix.latDeg)} fixLon=${"%.7f".format(gnssFix.lonDeg)} " +
            "fixAltM=${gnssFix.altEllipsoidalM?.let { "%.2f".format(it) } ?: "unknown"} " +
            "fixHAccM=${gnssFix.hAccM?.let { "%.2f".format(it) } ?: "unknown"} " +
            "fixVAccM=${gnssFix.vAccM?.let { "%.2f".format(it) } ?: "unknown"} rtk=${gnssFix.rtkStatus} " +
            "gnssGateAccM=${accuracyM?.let { "%.1f".format(it) } ?: "unknown"} " +
            "mockEnabled=${mockLocationEnabled?.toString() ?: "unknown"} " +
            "mockInjectionActive=${MockInjectionStatus.providerActive} " +
            "lastMockFixProvider=${MockInjectionStatus.lastSourceFixProvider ?: "n/a"} " +
            "lastMockFixAgeMs=${MockInjectionStatus.lastSourceFixAgeMs()?.toString() ?: "n/a"} " +
            "earthLat=${camAccPose?.latitude?.let { "%.7f".format(it) } ?: "unknown"} " +
            "earthLon=${camAccPose?.longitude?.let { "%.7f".format(it) } ?: "unknown"} " +
            "earthAltM=${camAccPose?.altitude?.let { "%.2f".format(it) } ?: "unknown"} " +
            "earthHAccM=${arHAcc.takeIf { it.isFinite() }?.let { "%.2f".format(it) } ?: "unknown"} " +
            "earthVAccM=${earthVAcc?.let { "%.2f".format(it) } ?: "unknown"} " +
            "earthYawAccDeg=${arYawAcc.takeIf { it.isFinite() }?.let { "%.2f".format(it) } ?: "unknown"} " +
            "earthTracking=${earth.trackingState} cameraTracking=${lastCameraTrackingLogged ?: "n/a"} " +
            "transformSource=anchor-driven")
        // Per-coordinate placement inputs: distance/bearing from the CURRENT fix to each target
        // (for display/diagnosis only — these never feed the model transform).
        for (item in geoItems) {
            val c = item.coordWithModel.coordinate
            val dM  = haversineM(gnssFix.latDeg, gnssFix.lonDeg, c.latitude, c.longitude)
            val brg = bearingDeg(gnssFix.latDeg, gnssFix.lonDeg, c.latitude, c.longitude)
            DiagnosticsLogger.i(DIAG, "  anchor input coordId=${c.id} name=\"${c.name}\" " +
                "lat=${"%.7f".format(c.latitude)} lon=${"%.7f".format(c.longitude)} " +
                "altM=${if (c.altitude.isFinite()) "%.2f".format(c.altitude) else "unknown"} " +
                "distFromFixM=${"%.1f".format(dM)} bearingDeg=${"%.0f".format(brg)} " +
                "hasModel=${item.modelFilePath != null}")
        }

        // Clean up existing anchors
        for (entry in geoAnchors) try {
            entry.anchor.detach()
        } catch (_: Exception) {
        }
        geoAnchors.clear()

        // Use GNSS ellipsoidal altitude as fallback for coordinates with no recorded altitude.
        // altEllipsoidalM (WGS84 HAE) is required because ARCore's createAnchor() expects it.
        // If ellipsoidal alt is unavailable, derive it from MSL + geoid separation.
        // Never use raw altMslM alone — omitting geoid separation causes ±15–85m errors.
        val fallbackAlt = gnssFix.altEllipsoidalM
            ?: run {
                val msl   = gnssFix.altMslM
                val geoid = gnssFix.geoidSeparationM
                if (msl != null && geoid != null) msl + geoid else null
            }
            ?: 0.0

        // Record the RTK status at the time of anchor creation for auto re-anchor logic.
        lastAnchorRtkStatus = gnssFix.rtkStatus

        // Create new anchors for each coordinate — snapshot geoItems (volatile read) so the
        // main thread can safely call setCoordinates() concurrently without a CME.
        val items = geoItems

        val earthStateName = earth.earthState?.name ?: "null"
        if (useTerrainAltitude) {
            // Terrain mode: resolveAnchorOnTerrain places each anchor at altitudeAboveTerrain=0.0
            // (exactly on the terrain surface at that lat/lon). The anchor starts with
            // TrackingState.PAUSED and self-updates to TRACKING once ARCore downloads the
            // terrain elevation for that location — no manual polling required.
            var failed = 0
            for (item in items) {
                try {
                    val anchor = earth.resolveAnchorOnTerrain(
                        item.lat, item.lng, 0.0, 0f, 0f, 0f, 1f)
                    geoAnchors.add(AnchorEntry(anchor, item.coordWithModel))
                } catch (e: Exception) {
                    failed++
                    if (item.coordWithModel.modelFilePath != null)
                        ArSessionDiagnostics.setStatus(item.coordWithModel.coordinate.id,
                            ArSessionDiagnostics.ModelStatus.FAILED, "anchor_failed")
                    DiagnosticsLogger.w(DIAG, "anchor failed coordinateId=${item.coordWithModel.coordinate.id} " +
                        "name=\"${item.coordWithModel.coordinate.name}\" mode=TERRAIN reason=\"${e.message}\"", e)
                }
            }
            DiagnosticsLogger.i(DIAG, "anchors created count=${geoAnchors.size} failed=$failed mode=TERRAIN " +
                "anchorType=TERRAIN transformSource=anchor-driven replacedPrevious=$replacedPrevious " +
                "placedVia=$placedVia trackingState=${earth.trackingState} earthState=$earthStateName " +
                "gnssAccM=${accuracyM?.let { "%.1f".format(it) } ?: "unknown"} " +
                "arHAccM=${arHAcc.takeIf { it.isFinite() }?.let { "%.1f".format(it) } ?: "unknown"} " +
                "arYawDeg=${arYawAcc.takeIf { it.isFinite() }?.let { "%.1f".format(it) } ?: "unknown"} — will appear as terrain resolves")
            recordAnchorOutcome(items.size, geoAnchors.size, failed)
        } else {
            // Stored-altitude mode: create WGS84 anchors at each coordinate's recorded altitude.
            var failed = 0
            for (item in items) {
                val altToUse = item.alt ?: fallbackAlt
                try {
                    val anchor = earth.createAnchor(item.lat, item.lng, altToUse, 0f, 0f, 0f, 1f)
                    geoAnchors.add(AnchorEntry(anchor, item.coordWithModel))
                } catch (e: Exception) {
                    failed++
                    if (item.coordWithModel.modelFilePath != null)
                        ArSessionDiagnostics.setStatus(item.coordWithModel.coordinate.id,
                            ArSessionDiagnostics.ModelStatus.FAILED, "anchor_failed")
                    DiagnosticsLogger.w(DIAG, "anchor failed coordinateId=${item.coordWithModel.coordinate.id} " +
                        "name=\"${item.coordWithModel.coordinate.name}\" mode=STORED reason=\"${e.message}\"", e)
                }
            }
            DiagnosticsLogger.i(DIAG, "anchors created count=${geoAnchors.size} failed=$failed mode=STORED " +
                "anchorType=WGS84 transformSource=anchor-driven replacedPrevious=$replacedPrevious " +
                "placedVia=$placedVia trackingState=${earth.trackingState} earthState=$earthStateName " +
                "gnssAccM=${accuracyM?.let { "%.1f".format(it) } ?: "unknown"} " +
                "arHAccM=${arHAcc.takeIf { it.isFinite() }?.let { "%.1f".format(it) } ?: "unknown"} " +
                "arYawDeg=${arYawAcc.takeIf { it.isFinite() }?.let { "%.1f".format(it) } ?: "unknown"}")
            recordAnchorOutcome(items.size, geoAnchors.size, failed)
        }

        lastPlacedVia = placedVia
        geoAnchorsCreated = true
    }

    /** Records anchor-build tallies into the session diagnostics (GL thread). */
    private fun recordAnchorOutcome(attempted: Int, created: Int, failed: Int) {
        ArSessionDiagnostics.anchorsAttempted += attempted
        ArSessionDiagnostics.anchorsCreated = maxOf(ArSessionDiagnostics.anchorsCreated, created)
        ArSessionDiagnostics.anchorFailures += failed
    }

    /**
     * Whether this app is the OS-selected mock-location app (AppOps `OPSTR_MOCK_LOCATION` == ALLOWED).
     * Returns null when the value cannot be determined. This is the app-level signal for "could ARCore
     * be reading a mocked Android location" — distinct from the in-app mock-enabled setting.
     */
    private fun isMockLocationAppApproved(): Boolean? = try {
        val ctx = requireContext()
        val aom = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            aom.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_MOCK_LOCATION,
                android.os.Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION")
            aom.checkOpNoThrow(android.app.AppOpsManager.OPSTR_MOCK_LOCATION,
                android.os.Process.myUid(), ctx.packageName)
        mode == android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { null }

    /**
     * Logs one AR session header (source + fix + mock + geospatial state) the first time a fix arrives
     * after the AR screen opens. Answers, from a future export: what location source was active, was
     * mock location enabled/approved/injecting, and was ARCore geospatial available. Missing values are
     * logged as "unknown" — never 0.0. Also seeds the AR/Mock export summary. Main thread only.
     */
    private fun logArSessionHeaderOnce() {
        if (loggedArSessionHeader) return
        loggedArSessionHeader = true
        val fix = currentGnssFix
        val fixAgeMs = fix?.let { System.currentTimeMillis() - it.timeUtc.toEpochMilli() }
        val mockApproved = isMockLocationAppApproved()
        ArSessionDiagnostics.arSelectedSource = selectedLocationSource
        ArSessionDiagnostics.arCurrentFixProvider = fix?.provider?.name
        ArSessionDiagnostics.arMockEnabledDuringAr = mockLocationEnabled
        ArSessionDiagnostics.arMockInjectionActiveDuringAr = MockInjectionStatus.providerActive
        ArSessionDiagnostics.arMockAppApproved = mockApproved
        DiagnosticsLogger.i(DIAG, "AR_SESSION_HEADER " +
            "selectedSource=${selectedLocationSource ?: "unknown"} " +
            "activeFixProvider=${fix?.provider?.name ?: "none"} " +
            "fixAgeMs=${fixAgeMs?.toString() ?: "n/a"} " +
            "fixLat=${fix?.let { "%.7f".format(it.latDeg) } ?: "n/a"} " +
            "fixLon=${fix?.let { "%.7f".format(it.lonDeg) } ?: "n/a"} " +
            "fixAltM=${fix?.altEllipsoidalM?.let { "%.2f".format(it) } ?: "unknown"} " +
            "fixHAccM=${fix?.hAccM?.let { "%.2f".format(it) } ?: "unknown"} " +
            "fixVAccM=${fix?.vAccM?.let { "%.2f".format(it) } ?: "unknown"} " +
            "rtk=${fix?.rtkStatus?.name ?: "n/a"} " +
            "mockLocationEnabled=${mockLocationEnabled?.toString() ?: "unknown"} " +
            "mockInjectionActive=${MockInjectionStatus.providerActive} " +
            "mockAppApproved=${mockApproved?.toString() ?: "unknown"} " +
            "lastMockInjectionAgeMs=${MockInjectionStatus.lastInjectionAgeMs()?.toString() ?: "n/a"} " +
            "sdk=${Build.VERSION.SDK_INT} geospatialEnabled=$geospatialAvailable " +
            "earthTracking=${lastEarthTrackingLogged ?: "n/a"}")
        viewModel.logArVisibilityState("ar_start")
    }


    /**
     * Check if device location services are enabled
     */
    private fun isLocationServicesEnabled(): Boolean {
        val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /**
     * Check if device has internet connectivity for geospatial localization
     */
    private fun hasNetwork(): Boolean {
        val cm =
            requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nw = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(nw) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }


    // ---------------------------------------------------------------------------------------------
    // GLSurfaceView.Renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)

        // BackgroundRenderer creates and owns the OES camera texture.
        // Wrap in try-catch: a shader compilation failure here would leave backgroundRenderer
        // null, causing onDrawFrame to return before setCameraTextureName() is called →
        // ARCore never receives camera frames → persistent "Last visual features at: 0 ns".
        try {
            backgroundRenderer   = BackgroundRenderer()
            cubeRenderer         = SimpleObjectRenderer()
            planeVisualizer      = PlaneVisualizer()
            pointCloudRenderer   = PointCloudRenderer()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "onSurfaceCreated: renderer init failed", e)
            // backgroundRenderer remains null; onDrawFrame will retry on the next frame.
            return
        }

        // Pre-register the camera texture name with the session so that ARCore can start
        // delivering camera frames immediately without waiting for the first onDrawFrame call.
        // If the session is not created yet (first launch), onDrawFrame will set it anyway.
        session?.setCameraTextureName(backgroundRenderer!!.textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        surfaceWidth = width
        surfaceHeight = height
        edgeMarginPx = 40f * resources.displayMetrics.density
        try {
            session?.setDisplayGeometry(displayRotation, width, height)
        } catch (e: Exception) {
            DiagnosticsLogger.w(DIAG, "setDisplayGeometry failed (${width}x$height rot=$displayRotation): ${e.message}", e)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val s = session ?: return
        if (!sessionReady) return
        try {
            val texId = backgroundRenderer?.textureId ?: return
            s.setCameraTextureName(texId)
            if (surfaceWidth > 0 && surfaceHeight > 0) {
                s.setDisplayGeometry(displayRotation, surfaceWidth, surfaceHeight)
            }

            val frame  = s.update()
            val camera = frame.camera

            // Camera tracking-state CHANGE detection (recorded/logged once per change, not per frame).
            val camTracking = camera.trackingState.name
            if (camTracking != lastCameraTrackingLogged) {
                lastCameraTrackingLogged = camTracking
                ArSessionDiagnostics.lastCameraTrackingState = camTracking
                val reasonName = runCatching { camera.trackingFailureReason?.name }.getOrNull()
                DiagnosticsLogger.i(DIAG, "camera_state trackingState=$camTracking" +
                    (reasonName?.let { " failureReason=$it" } ?: ""))
            }

            // 1) Camera background
            backgroundRenderer?.draw(frame)

            // 2) Earth / anchor management
            val earth       = s.earth
            val earthStatus = processEarthAndAnchors(earth)

            // 3) 3-D rendering (only when camera is tracking)
            var planeCount = 0
            var pointCount = 0
            if (camera.trackingState == TrackingState.TRACKING) {
                camera.getViewMatrix(view, 0)
                camera.getProjectionMatrix(proj, 0, NEAR_CLIP, FAR_CLIP)
                Matrix.multiplyMM(vp, 0, proj, 0, view, 0)


                pointCount = if (arDebugToolsEnabled && arShowPointCloud) pointCloudRenderer?.draw(frame, vp) ?: 0 else 0
                planeCount = if (arDebugToolsEnabled && arShowPlanes) planeVisualizer?.drawAllPlanes(s, vp) ?: 0 else 0

                val margin        = edgeMarginPx
                val pinPositions  = mutableListOf<PinScreenEntry>()
                val labelEntries  = mutableListOf<CoordinateLabelOverlay.LabelEntry>()
                val arrowEntries  = mutableListOf<OffScreenPinIndicatorOverlay.ArrowEntry>()
                val newModelPoses = mutableListOf<ArFilamentRenderer.ModelPose>()

                collectGeoAnchors(earth, vp, margin, pinPositions, labelEntries, arrowEntries, newModelPoses)
                testAnchorController.render(vp, model, modelScaled, mvp, cubeRenderer,
                    surfaceWidth, surfaceHeight, margin, labelEntries, arrowEntries, newModelPoses)

                pinScreenCache = pinPositions
                // Publish a single atomic snapshot so the Choreographer always reads a camera matrix
                // and model poses from the SAME ARCore frame — see ArRenderSnapshot kdoc.
                arRenderSnapshot = ArRenderSnapshot(
                    viewMatrix  = view.copyOf(),
                    projMatrix  = proj.copyOf(),
                    modelPoses  = newModelPoses,
                    visibleKeys = modelVisibleKeys
                )
                postOverlayUpdates(labelEntries, arrowEntries)
            }

            // 4) Status bar, debug panel, model progress chip (posted to main thread)
            postDebugPanelUpdate(earth, earthStatus, planeCount, pointCount)

        } catch (_: CameraNotAvailableException) {
        } catch (e: Exception) {
            android.util.Log.w(TAG, "onDrawFrame error", e)
        }
    }

    // ── onDrawFrame helpers (all called from the GL thread) ──────────────────

    /** Ordered quality levels for auto re-anchor; higher index = better quality. */
    private fun rtkQualityRank(status: app.surrealar.gnss.model.RtkStatus?): Int =
        when (status) {
            app.surrealar.gnss.model.RtkStatus.FIX    -> 4
            app.surrealar.gnss.model.RtkStatus.FLOAT  -> 3
            app.surrealar.gnss.model.RtkStatus.DGPS   -> 2
            app.surrealar.gnss.model.RtkStatus.SINGLE -> 1
            else                                                  -> 0
        }

    /**
     * Handles a pending re-anchor request then processes earth tracking state.
     * Returns a short human-readable earth status string for the debug panel.
     */
    private fun processEarthAndAnchors(earth: Earth?): String {
        // Process manual re-anchor request from main thread BEFORE rebuilding.
        if (shouldRebuildAnchors) {
            shouldRebuildAnchors = false
            for (entry in geoAnchors) try { entry.anchor.detach() } catch (_: Exception) {}
            geoAnchors.clear()
            geoAnchorsCreated = false
            lastAnchorRtkStatus = null
            lastReanchorTimeMs = System.currentTimeMillis()
            loggedAccuracyGate = false
            earthAccuracyAtCreationM = null
            ArSessionDiagnostics.reanchorAttempts++
            DiagnosticsLogger.i(DIAG, "Re-anchor (manual) — anchors cleared for rebuild")
        }

        if (earth == null) return if (geospatialAvailable) "Geo: N/A" else "Geo: unavailable on this device"

        // Earth/geospatial tracking-state CHANGE detection (logged + recorded once per change,
        // never per frame).
        val earthTracking = earth.trackingState.name
        if (earthTracking != lastEarthTrackingLogged) {
            lastEarthTrackingLogged = earthTracking
            val earthStateName = runCatching { earth.earthState?.name }.getOrNull() ?: "n/a"
            ArSessionDiagnostics.lastEarthTrackingState = earthTracking
            ArSessionDiagnostics.lastEarthState = earthStateName
            DiagnosticsLogger.i(DIAG, "earth_state trackingState=$earthTracking earthState=$earthStateName")
            // (Re)start the localization timeout clock on entering TRACKING; clear it (and the
            // waiting state) when tracking is lost so a fresh TRACKING period is timed from scratch.
            if (earth.trackingState == TrackingState.TRACKING) {
                earthTrackingSinceMs = System.currentTimeMillis()
            } else {
                earthTrackingSinceMs = 0L
                awaitingLocalization = false
                loggedLocalizationWait = false
            }
        }

        return when (earth.trackingState) {
            TrackingState.TRACKING -> {
                // Auto re-anchor only for a meaningful quality jump: non-RTK → RTK.
                // Incremental steps (SINGLE→DGPS or FLOAT→FIX) are deliberately excluded
                // because switching to RS2+ causes rapid steps through all quality levels
                // in <500ms, which would flood ARCore with create/cancel terrain-anchor
                // requests and crash the session. The manual Re-anchor button handles
                // deliberate upgrades; the cooldown prevents a repeat within 30s.
                val currentFix = currentGnssFix
                if (geoAnchorsCreated && currentFix != null) {
                    val newRank  = rtkQualityRank(currentFix.rtkStatus)
                    val lastRank = rtkQualityRank(lastAnchorRtkStatus)
                    val isRtkUpgrade = newRank >= 3 && lastRank < 3   // non-RTK → RTK threshold
                    val cooldownElapsed = System.currentTimeMillis() - lastReanchorTimeMs >= REANCHOR_COOLDOWN_MS
                    if (isRtkUpgrade && cooldownElapsed) {
                        ArSessionDiagnostics.reanchorAttempts++
                        ArSessionDiagnostics.anchorsRebuiltAfterInitial++
                        DiagnosticsLogger.i(DIAG, "Re-anchor (auto) reason=rtk_upgrade " +
                            "$lastAnchorRtkStatus → ${currentFix.rtkStatus} — clearing ${geoAnchors.size} anchors")
                        for (entry in geoAnchors) try { entry.anchor.detach() } catch (_: Exception) {}
                        geoAnchors.clear()
                        geoAnchorsCreated = false
                        lastAnchorRtkStatus = null
                        lastReanchorTimeMs = System.currentTimeMillis()
                        loggedAccuracyGate = false
                        earthAccuracyAtCreationM = null
                    }
                }

                // Auto re-anchor when ARCore's OWN localization materially improves after a rough or
                // timeout placement — so a placement committed near the user self-corrects instead of
                // requiring a manual re-anchor. Throttled + cooldown-gated to avoid churn.
                val nowMs = System.currentTimeMillis()
                val committedAcc = earthAccuracyAtCreationM
                if (geoAnchorsCreated && committedAcc != null && nowMs - lastImprovementCheckMs >= 2_000L) {
                    lastImprovementCheckMs = nowMs
                    val curAcc = runCatching { earth.cameraGeospatialPose.horizontalAccuracy }.getOrNull()
                    val cooldownElapsed = nowMs - lastReanchorTimeMs >= REANCHOR_COOLDOWN_MS
                    if (curAcc != null &&
                        GeoAnchorGate.shouldReanchorOnImprovement(committedAcc, curAcc, cooldownElapsed)) {
                        ArSessionDiagnostics.reanchorAttempts++
                        ArSessionDiagnostics.anchorsRebuiltAfterInitial++
                        DiagnosticsLogger.i(DIAG, "Re-anchor (auto) reason=ar_localization_improved " +
                            "${"%.1f".format(committedAcc)} → ${"%.1f".format(curAcc)} m — clearing ${geoAnchors.size} anchors")
                        for (entry in geoAnchors) try { entry.anchor.detach() } catch (_: Exception) {}
                        geoAnchors.clear()
                        geoAnchorsCreated = false
                        earthAccuracyAtCreationM = null
                        lastReanchorTimeMs = nowMs
                        loggedAccuracyGate = false
                    }
                }

                rebuildGeoAnchorsIfNeeded(earth)
                val fix = currentGnssFix
                if (fix != null) {
                    testAnchorController.processActionIfNeeded(earth, fix.latDeg, fix.lonDeg)
                }
                if (awaitingLocalization) "Geo: Localizing…" else "Geo: TRACKING"
            }
            TrackingState.PAUSED  -> "Geo: Localizing..."
            TrackingState.STOPPED -> "Geo: Off (AR active)"
        }
    }

    /**
     * Iterates [geoAnchors], renders each pin or queues a Filament model pose, and
     * appends to the label/arrow/pin-position collections for this frame.
     */
    private fun collectGeoAnchors(
        earth: Earth?,
        vp: FloatArray,
        edgeMargin: Float,
        pinPositions: MutableList<PinScreenEntry>,
        labelEntries: MutableList<CoordinateLabelOverlay.LabelEntry>,
        arrowEntries: MutableList<OffScreenPinIndicatorOverlay.ArrowEntry>,
        newModelPoses: MutableList<ArFilamentRenderer.ModelPose>
    ) {
        if (earth?.trackingState != TrackingState.TRACKING) return
        val camGeoPose = earth.cameraGeospatialPose
        val filterM    = distanceFilterM
        val distanceSkipped = mutableSetOf<String>()
        val mode = arVisibilityModeMirror
        val visibleIds = arVisibleIdsMirror
        val visibleKeys = HashSet<String>()

        for (entry in geoAnchors) {
            val coord = entry.coordWithModel.coordinate
            val entryModelFilePath = entry.modelFilePath
            val distM = haversineM(camGeoPose.latitude, camGeoPose.longitude,
                coord.latitude, coord.longitude)
            // Model-visibility decision (shared with the bottom sheet via ArVisibilityLogic): eligibility
            // (has model + in range unless ALL) AND selection (SELECTED mode). Computed regardless of this
            // anchor's tracking so a visible-but-not-tracking model stays in the Filament scene (no
            // flicker). Toggling one item only changes this set — the anchors are untouched, no rebuild.
            val inRange = ArVisibilityLogic.inRange(mode, distM, filterM)
            val modelVisible = entryModelFilePath != null &&
                ArVisibilityLogic.renderable(mode, inRange, coord.id in visibleIds)
            if (modelVisible) visibleKeys += coord.id
            if (!inRange) distanceSkipped += coord.id

            if (entry.anchor.trackingState != TrackingState.TRACKING) continue
            // Hidden model coords render nothing (no model/pin/label); out-of-range pins are skipped.
            if (entryModelFilePath != null) {
                if (!modelVisible) continue
            } else if (!inRange) {
                continue
            }

            entry.anchor.pose.toMatrix(model, 0)
            System.arraycopy(model, 0, modelScaled, 0, 16)

            if (entryModelFilePath != null) {
                // Pass the raw anchor pose — do NOT pre-scale the basis columns here.
                // ArFilamentRenderer folds arModelScale into effScale via ModelPose.coordScale
                // so the correction matrix and bounding-box normalization both see the
                // correct full scale (fixes the inconsistency that caused arModelScale to be
                // applied to the world matrix but NOT to the correction matrix's effScale).
                newModelPoses += ArFilamentRenderer.ModelPose(
                    key        = coord.id,
                    worldMatrix = model.copyOf(),
                    filePath   = entryModelFilePath,
                    coordScale = arModelScale
                )
            } else {
                Matrix.scaleM(modelScaled, 0, 0.1f, 0.3f, 0.1f)
                Matrix.multiplyMM(mvp, 0, vp, 0, modelScaled, 0)
                cubeRenderer?.draw(mvp, entry.rgba[0], entry.rgba[1], entry.rgba[2], entry.rgba[3])
            }

            val bearing = bearingDeg(camGeoPose.latitude, camGeoPose.longitude,
                coord.latitude, coord.longitude)
            val subtext = "${formatDist(distM)} ${bearingToCompass(bearing)}"

            val pinWorldPos = floatArrayOf(model[12], model[13] + 0.6f, model[14], 1f)
            val screenProj = projectToScreen(pinWorldPos, vp, surfaceWidth, surfaceHeight) ?: continue
            if (screenProj.onScreen) {
                pinPositions  += PinScreenEntry(screenProj.sx, screenProj.sy, entry.coordWithModel)
                if (arShowLabels) {
                    val labelText  = if (entryModelFilePath != null)
                        CoordinateLabelOverlay.MODEL_TAG + coord.name else coord.name
                    labelEntries  += CoordinateLabelOverlay.LabelEntry(labelText, screenProj.sx, screenProj.sy, subtext)
                }
            } else if (arShowOffscreenArrows) {
                val arrow = computeEdgeArrow(screenProj.sx, screenProj.sy, surfaceWidth, surfaceHeight, edgeMargin)
                if (arrow != null) {
                    arrowEntries += OffScreenPinIndicatorOverlay.ArrowEntry(
                        edgeX    = arrow.first.x,
                        edgeY    = arrow.first.y,
                        angleDeg = arrow.second,
                        name     = coord.name,
                        distStr  = subtext,
                        isModel  = entryModelFilePath != null
                    )
                }
            }
        }

        // Publish the visible-model set for Filament scene membership; log rendered count on change.
        modelVisibleKeys = visibleKeys
        if (visibleKeys.size != lastLoggedVisibleCount) {
            DiagnosticsLogger.i(DIAG, "AR_MODELS_RENDERED count=${visibleKeys.size} mode=$mode " +
                "rangeM=${filterM?.let { "%.0f".format(it) } ?: "all"} selectedIds=${visibleIds.size}")
            lastLoggedVisibleCount = visibleKeys.size
        }

        // Log only when the distance-filtered set changes — never every frame.
        if (distanceSkipped != lastDistanceSkippedIds) {
            val newlyHidden = distanceSkipped - lastDistanceSkippedIds
            // A coordinate that re-enters range is no longer distance-skipped; let later load
            // events restore its status. Newly-hidden model coordinates are marked SKIPPED.
            newlyHidden.forEach { id ->
                ArSessionDiagnostics.setStatus(id, ArSessionDiagnostics.ModelStatus.SKIPPED, "distance_filter")
            }
            lastDistanceSkippedIds = distanceSkipped
            if (newlyHidden.isNotEmpty()) {
                DiagnosticsLogger.i(DIAG, "scene skip reason=\"distance_filter\" " +
                    "maxDistanceM=$filterM hidden=${distanceSkipped.size} newlyHiddenIds=$newlyHidden")
            }
        }
    }

    /** Posts label and arrow overlay updates to the main thread, skipping unchanged frames. */
    private fun postOverlayUpdates(
        labelEntries: List<CoordinateLabelOverlay.LabelEntry>,
        arrowEntries: List<OffScreenPinIndicatorOverlay.ArrowEntry>
    ) {
        if (labelEntries != lastPostedLabels) {
            lastPostedLabels = labelEntries
            // Track the peak number of labels rendered this session (changes only when the set does).
            ArSessionDiagnostics.labelsShown = maxOf(ArSessionDiagnostics.labelsShown, labelEntries.size)
            _binding?.labelOverlay?.post { _binding?.labelOverlay?.updateLabels(labelEntries) }
        }
        if (arrowEntries != lastPostedArrows) {
            lastPostedArrows = arrowEntries
            _binding?.offScreenOverlay?.post { _binding?.offScreenOverlay?.updateArrows(arrowEntries) }
        }
    }

    /** Per-anchor data extracted on the GL thread for safe use in [buildDebugText] on the main thread. */
    private data class AnchorDebugEntry(
        val name: String,
        val coordId: String,
        val tracking: Boolean,
        val distM: Double,
        val bear: Double,
        val hasModel: Boolean
    )

    /**
     * Posts a status bar + debug text + model-progress chip update to the main thread.
     * All UI writes happen inside `post {}` so they are safe to call from the GL thread.
     * All ARCore state ([Earth], anchor [TrackingState]) is extracted here on the GL thread
     * into plain data before posting — ARCore objects must never be read on the main thread.
     */
    private fun postDebugPanelUpdate(
        earth: Earth?,
        earthStatus: String,
        planeCount: Int,
        pointCount: Int
    ) {
        // Extract all ARCore earth values NOW on the GL thread before posting (one pose read, reused
        // for both the debug snapshot and the AR-view-quality indicator).
        val camGeo = runCatching {
            if (earth != null && earth.trackingState == TrackingState.TRACKING) earth.cameraGeospatialPose else null
        }.getOrNull()
        val snapshot: EarthDebugSnapshot? = if (earth != null) {
            EarthDebugSnapshot(
                earthStateName = earth.earthState?.name ?: "null",
                camLat         = camGeo?.latitude,
                camLon         = camGeo?.longitude,
                camAlt         = camGeo?.altitude,
                camHeading     = camGeo?.heading
            )
        } else null

        // ── AR view quality (ARCore geospatial confidence — NOT the GNSS fix) ────────────────────────
        val qHAcc = camGeo?.horizontalAccuracy
        val qVAcc = camGeo?.verticalAccuracy
        val qYaw  = camGeo?.orientationYawAccuracy
        val placement = when {
            !geoAnchorsCreated          -> ArViewQuality.Placement.WAITING
            geoAnchors.isEmpty()        -> ArViewQuality.Placement.FAILED
            lastPlacedVia == "timeout"  -> ArViewQuality.Placement.PLACED_BY_TIMEOUT
            else                        -> ArViewQuality.Placement.PLACED
        }
        val quality = ArViewQuality.evaluate(
            earthTracking  = earth?.trackingState == TrackingState.TRACKING,
            cameraTracking = lastCameraTrackingLogged == "TRACKING",
            hAccM = qHAcc, vAccM = qVAcc, yawDeg = qYaw,
            placement = placement,
        )
        if (quality.level != lastLoggedQualityLevel) {
            DiagnosticsLogger.i(DIAG, "AR_QUALITY ${lastLoggedQualityLevel ?: "n/a"} → ${quality.level} " +
                "hAccM=${qHAcc?.takeIf { it.isFinite() }?.let { "%.1f".format(it) } ?: "unknown"} " +
                "vAccM=${qVAcc?.takeIf { it.isFinite() }?.let { "%.1f".format(it) } ?: "unknown"} " +
                "yawDeg=${qYaw?.takeIf { it.isFinite() }?.let { "%.1f".format(it) } ?: "unknown"} " +
                "earthTracking=${earth?.trackingState ?: "null"} cameraTracking=${lastCameraTrackingLogged ?: "n/a"} " +
                "placement=$placement modelsRendered=${quality.modelsRenderable} " +
                "placedByTimeout=${placement == ArViewQuality.Placement.PLACED_BY_TIMEOUT}")
            lastLoggedQualityLevel = quality.level
        }

        // Extract per-anchor states on the GL thread (anchor.trackingState is an ARCore call).
        val camLat = snapshot?.camLat
        val camLon = snapshot?.camLon
        val anchorDebug: List<AnchorDebugEntry> = geoAnchors.map { entry ->
            val coord = entry.coordWithModel.coordinate
            val distM = if (camLat != null && camLon != null)
                haversineM(camLat, camLon, coord.latitude, coord.longitude) else -1.0
            val bear = if (camLat != null && camLon != null)
                bearingDeg(camLat, camLon, coord.latitude, coord.longitude) else 0.0
            AnchorDebugEntry(
                name     = coord.name,
                coordId  = coord.id,
                tracking = entry.anchor.trackingState == TrackingState.TRACKING,
                distM    = distM,
                bear     = bear,
                hasModel = entry.modelFilePath != null
            )
        }

        _binding?.textArStatus?.post {
            val gnssFix   = currentGnssFix
            val topStatus = if (gnssFix == null) "AR active • Waiting for GNSS • $earthStatus"
                            else "AR active • $earthStatus"

            // Always update the top status bar.
            _binding?.textArStatus?.text = topStatus

            // AR view quality surfacing (only when there are model-linked coordinates to place):
            //  - before placement → prominent centred banner with live AR accuracy + guidance
            //  - after placement  → compact persistent "AR quality" chip
            // This is the ARCore geospatial (AR) confidence, distinct from the GNSS fix chip.
            val haveModels = geoItems.any { it.modelFilePath != null }
            when {
                !haveModels -> {
                    _binding?.localizingBanner?.visibility = View.GONE
                    _binding?.chipArQuality?.visibility = View.GONE
                }
                quality.modelsRenderable -> {
                    _binding?.localizingBanner?.visibility = View.GONE
                    _binding?.chipArQuality?.let { chip ->
                        val timeoutTag = if (placement == ArViewQuality.Placement.PLACED_BY_TIMEOUT) " · timeout" else ""
                        chip.text = "${quality.statusLabel} · ${quality.accuracyText}$timeoutTag"
                        chip.visibility = View.VISIBLE
                    }
                }
                else -> {
                    _binding?.chipArQuality?.visibility = View.GONE
                    _binding?.txtLocalizingTitle?.text = quality.statusLabel
                    _binding?.txtLocalizingHint?.text = buildString {
                        append("AR accuracy: ${quality.accuracyText}")
                        quality.headingText?.let { append(" · Heading: $it") }
                        quality.helperText?.let { append("\n$it") }
                    }
                    _binding?.localizingBanner?.visibility = View.VISIBLE
                }
            }

            // Only build the debug string (and pay its allocation cost) when the panel is visible.
            if (debugOverlayVisible) {
                val debugLines = buildDebugText(snapshot, earthStatus, planeCount, pointCount, gnssFix, anchorDebug)
                _binding?.textArDebug?.text  = debugLines
            }

            updateModelProgressChip()
        }
    }

    /** Builds the multi-line debug panel string. Must be called on the main thread. */
    private fun buildDebugText(
        snapshot: EarthDebugSnapshot?,
        earthStatus: String,
        planeCount: Int,
        pointCount: Int,
        gnssFix: Fix?,
        anchorDebug: List<AnchorDebugEntry>
    ): String = buildString {
        val providerText = when (gnssFix?.provider) {
            app.surrealar.gnss.model.Provider.INTERNAL    -> "Internal GPS"
            app.surrealar.gnss.model.Provider.RS2_EXTERNAL -> "RS2+ (${gnssFix.rtkStatus})"
            null  -> "Waiting"
            else  -> gnssFix.provider.toString()
        }
        val accText = gnssFix?.hAccM?.let { "±%.1fm".format(it) } ?: "N/A"

        appendLine("🌍 Earth: $earthStatus  [${snapshot?.earthStateName}]")
        appendLine("🧭 AR: Planes=$planeCount  Points=$pointCount")
        appendLine("📡 GPS: $providerText  Acc=$accText")
        appendLine("⚓ Anchors: ${anchorDebug.size}  geoAnchorsCreated=$geoAnchorsCreated")
        appendLine("📋 Items loaded: ${geoItems.size}")

        val fix  = currentGnssFix
        val accM = fix?.hAccM
        appendLine("📡 GNSS fix: ${if (fix != null) "YES  hAccM=${accM?.let { "%.2f".format(it) } ?: "null (gate skipped)"}" else "NO FIX YET"}")
        if (geoItems.isEmpty())          appendLine("⚠️  BLOCKER: geoItems is empty")
        if (fix == null)                 appendLine("⚠️  BLOCKER: currentGnssFix is null")
        if (accM != null && accM > MAX_GNSS_ACCURACY_M) appendLine("⚠️  BLOCKER: accuracy ${"%.1f".format(accM)}m > ${MAX_GNSS_ACCURACY_M.toInt()}m")
        if (geoAnchorsCreated && anchorDebug.isEmpty()) appendLine("⚠️  geoAnchorsCreated=true but 0 anchors")

        if (snapshot?.camLat != null) {
            appendLine("📍 Cam: %.6f, %.6f  alt=%.1fm  hdg=%.0f°".format(
                snapshot.camLat, snapshot.camLon, snapshot.camAlt, snapshot.camHeading))
        }
        anchorDebug.forEachIndexed { i, entry ->
            val state = if (entry.hasModel)
                filamentRenderer?.modelLoadState(entry.coordId)?.name ?: "NO_RENDERER"
            else "GLES_PIN"
            appendLine("[$i] ${entry.name}  tracking=${entry.tracking}" +
                "  %.1fm @ %.0f°  model=$state".format(entry.distM, entry.bear))
        }
        if (anchorDebug.isEmpty()) appendLine("  → no anchors yet")
    }.trimEnd()

    /** Updates the model-load-progress chip. Must be called on the main thread. */
    private fun updateModelProgressChip() {
        val posesSnapshot = arRenderSnapshot?.modelPoses ?: emptyList()
        val total   = posesSnapshot.size
        val inScene = posesSnapshot.count {
            filamentRenderer?.modelLoadState(it.key) == ArFilamentRenderer.ModelLoadState.IN_SCENE
        }
        val progress = inScene to total
        if (progress == lastModelProgress) return
        lastModelProgress = progress
        // Fires only on change (early-return above) — reports models currently rendered in scene.
        DiagnosticsLogger.i(DIAG, "scene state modelsInScene=$inScene total=$total activeAnchors=${geoAnchors.size}")
        ArSessionDiagnostics.activeAnchorCount = geoAnchors.size
        // Mark models that have reached the scene (only changes when progress changes — not per frame).
        posesSnapshot.forEach { pose ->
            if (filamentRenderer?.modelLoadState(pose.key) == ArFilamentRenderer.ModelLoadState.IN_SCENE)
                ArSessionDiagnostics.setStatus(pose.key, ArSessionDiagnostics.ModelStatus.IN_SCENE)
        }

        val loading = total - inScene
        when {
            total == 0 -> {
                hideModelProgressJob?.cancel()
                _binding?.chipModelProgress?.visibility = View.GONE
            }
            loading > 0 -> {
                hideModelProgressJob?.cancel()
                hideModelProgressJob = null
                _binding?.chipModelProgress?.let {
                    it.text = "⏳ $inScene/$total models"
                    it.visibility = View.VISIBLE
                }
            }
            else -> {
                if (hideModelProgressJob == null) {
                    _binding?.chipModelProgress?.let {
                        it.text = "✓ $total/$total models"
                        it.visibility = View.VISIBLE
                    }
                    // Guard: only launch if the view is still attached.
                    if (_binding != null) {
                        hideModelProgressJob = viewLifecycleOwner.lifecycleScope.launch {
                            kotlinx.coroutines.delay(2000)
                            _binding?.chipModelProgress?.visibility = View.GONE
                            hideModelProgressJob = null
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    // ---------------------------------------------------------------------------------------------
    // Coordinate data — handled by OpenInARViewModel; setCoordinates() is called via StateFlow.

    /** Map DB rows (with resolved model paths) to internal GeoItem list and mark anchors for rebuild. */
    private fun setCoordinates(items: List<CoordWithModel>) {
        // Log every incoming emission so we can confirm coordinates/models reach the fragment.
        // Routed through DiagnosticsLogger so these counts appear in the exported report.
        val withModels = items.filter { it.modelFilePath != null }
        val visibleModels = withModels.count { it.coordinate.renderEnabled }
        DiagnosticsLogger.i(DIAG,
            "setCoordinates total=${items.size} withModels=${withModels.size} visible=$visibleModels")
        ArSessionDiagnostics.coordinateCount = maxOf(ArSessionDiagnostics.coordinateCount, items.size)
        ArSessionDiagnostics.linkedModelCount = maxOf(ArSessionDiagnostics.linkedModelCount, withModels.size)
        ArSessionDiagnostics.coordinatesVisible = maxOf(ArSessionDiagnostics.coordinatesVisible, visibleModels)
        // Skip rebuild if the incoming list is identical to what we already have.
        // Room emits on every observed change; without this guard a no-op emission
        // clears geoAnchorsCreated and forces a full anchor rebuild at 60fps.
        val newItems = items.map { item ->
            val c = item.coordinate
            // Only treat altitude as absent when it is NaN or infinite.
            // DO NOT exclude 0.0 — coordinates at sea level have a legitimate 0m ellipsoidal altitude.
            val altitude = if (c.altitude.isNaN() || !c.altitude.isFinite()) null else c.altitude
            GeoItem(
                lat = c.latitude,
                lng = c.longitude,
                alt = altitude,
                rgba = argbIntToRgba(c.color),
                modelFilePath = item.modelFilePath,
                modelId = item.modelId,
                coordWithModel = item
            )
        }
        if (newItems == geoItems) return   // nothing changed — avoid spurious anchor rebuild

        geoItems = newItems
        // Invalidate anchors so they rebuild for the new coordinate set. Logged so the export shows
        // every reason anchors are cleared/rebuilt (here: the coordinate list changed, not user motion).
        if (geoAnchorsCreated) DiagnosticsLogger.i(DIAG,
            "anchors invalidated reason=coordinates_changed newCount=${newItems.size} prevAnchors=${geoAnchors.size}")
        geoAnchorsCreated = false

        // Kick off Filament GLB loading for every model-linked coordinate. preload() must run on the
        // main thread (Filament requirement), but the per-file existence/size stat is filesystem I/O —
        // batch those off the main thread first, then dispatch the preloads back on main.
        val scope = viewLifecycleOwner.lifecycleScope
        scope.launch {
            val stats: Map<String, Pair<Boolean, Long>> = withContext(Dispatchers.IO) {
                items.mapNotNull { it.modelFilePath }.distinct().associateWith { p ->
                    val f = File(p); val e = f.exists(); e to (if (e) f.length() else -1L)
                }
            }
            items.forEach { item ->
                val coord = item.coordinate
                val path  = item.modelFilePath ?: return@forEach   // no linked model — pin/label only
                // Per-model context so the export can pinpoint why a model did or did not render
                // (missing file, disabled visibility, altitude, scale, placement).
                val (exists, sizeBytes) = stats[path] ?: (false to -1L)
                val altState = if (coord.altitude.isNaN() || !coord.altitude.isFinite()) "missing" else "stored"
                DiagnosticsLogger.i(DIAG,
                    "preload dispatch coordinateId=${coord.id} modelId=${item.modelId} " +
                    "name=\"${coord.name}\" path=\"$path\" exists=$exists " +
                    "sizeBytes=$sizeBytes renderEnabled=${coord.renderEnabled} " +
                    "altitude=$altState coordScale=$arModelScale placement=${item.placement}")
                val basename = path.substringAfterLast('/')
                when {
                    !exists -> {
                        ArSessionDiagnostics.recordModel(coord.id, coord.name, item.modelId, basename,
                            exists, sizeBytes, ArSessionDiagnostics.ModelStatus.SKIPPED, "missing_file")
                        DiagnosticsLogger.w(DIAG,
                            "scene skip coordinateId=${coord.id} modelId=${item.modelId} reason=\"missing_file\" path=\"$path\"")
                    }
                    else -> {
                        // Preload every model that exists on disk regardless of visibility — the AR
                        // model-visibility control decides at render time which are shown, so preloading
                        // all makes toggling a model on instant (no reload) with no anchor rebuild.
                        ArSessionDiagnostics.recordModel(coord.id, coord.name, item.modelId, basename,
                            exists, sizeBytes, ArSessionDiagnostics.ModelStatus.QUEUED)
                        filamentRenderer?.preload(coord.id, path, scope, item.placement)
                    }
                }
            }
        }
    }

    /** Updates the compact AR GNSS status chip with live source, solution, and accuracy. */
    private fun updateArGnssChip(fix: Fix) {
        val b = _binding ?: return
        val src = when (fix.provider) {
            app.surrealar.gnss.model.Provider.RS2_EXTERNAL -> "RS2+"
            app.surrealar.gnss.model.Provider.INTERNAL     -> "Phone GPS"
            else -> fix.provider.name
        }
        val (solLabel, solColor) = when (fix.rtkStatus) {
            app.surrealar.gnss.model.RtkStatus.FIX    -> "RTK Fixed"  to requireContext().getColor(R.color.app_success)
            app.surrealar.gnss.model.RtkStatus.FLOAT  -> "RTK Float"  to requireContext().getColor(R.color.app_warning)
            app.surrealar.gnss.model.RtkStatus.DGPS   -> "DGPS"       to requireContext().getColor(R.color.app_info)
            app.surrealar.gnss.model.RtkStatus.SINGLE -> "GPS"        to requireContext().getColor(android.R.color.white)
            else                                                  -> "No Fix"     to requireContext().getColor(R.color.app_error)
        }
        val acc = fix.hAccM?.let { " · H ±${"%.2f".format(it)} m" } ?: ""
        b.chipArGnssStatus.text = "$src · $solLabel$acc"
        b.chipArGnssStatus.setTextColor(solColor)
    }

    /** Configure GL surface view and queue taps for processing on GL thread. */
    private fun setupGlSurface() {
        val gl = binding.glSurfaceViewAr
        gl.preserveEGLContextOnPause = true
        gl.setEGLContextClientVersion(3)
        gl.setRenderer(this)
        gl.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // Ensure the view can receive touch events
        gl.isFocusable = true
        gl.isFocusableInTouchMode = true
        gl.isClickable = true
        gl.requestFocus()

        gl.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                val tapX = ev.x
                val tapY = ev.y

                // Check whether the tap lands on (or near) a geospatial pin.
                // Hit radius = 60 dp — generous enough for finger-sized targets.
                val threshPx = 60f * resources.displayMetrics.density
                val threshSq = threshPx * threshPx
                val cache    = pinScreenCache         // snapshot (volatile read)
                val hit      = cache.minByOrNull {
                    val dx = it.x - tapX; val dy = it.y - tapY; dx * dx + dy * dy
                }
                if (hit != null) {
                    val dx = hit.x - tapX; val dy = hit.y - tapY
                    if (dx * dx + dy * dy <= threshSq) {
                        // CoordWithModel is embedded in PinScreenEntry — no geoAnchors access needed
                        val coord = hit.coordWithModel.coordinate
                        // Touch listeners always run on the main thread — no runOnUiThread needed.
                        PinInspectBottomSheet.show(
                            childFragmentManager,
                            name      = coord.name,
                            lat       = coord.latitude,
                            lon       = coord.longitude,
                            alt       = coord.altitude,
                            hAccM     = coord.horizontalAccuracyM,
                            rtkStatus = coord.rtkStatus?.name,
                            provider  = coord.provider.name,
                            modelId   = hit.coordWithModel.modelId,
                            timestamp = coord.timestamp
                        )
                        v.performClick()
                        return@setOnTouchListener true
                    }
                }

                // No pin tapped — consume the tap (performClick for accessibility) and do nothing.
                v.performClick()
                true
            } else {
                false
            }
        }

        android.util.Log.d("OpenInARFragment", "✅ GLSurfaceView setup complete with touch listener")
    }
}