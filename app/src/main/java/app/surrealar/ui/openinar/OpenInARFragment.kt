package app.surrealar.ui.openinar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PointF
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.os.Bundle
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
import app.surrealar.gnss.bus.FixSwitchboard
import app.surrealar.gnss.model.Fix
import com.google.ar.core.*
import com.google.ar.core.Point
import com.google.ar.core.Plane
import com.google.ar.core.exceptions.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    private val viewModel: OpenInARViewModel by viewModels()

    // Current GNSS fix from switchboard (surveying-grade GPS)
    @Volatile
    private var currentGnssFix: Fix? = null
    /** Last logged GNSS provider — used to suppress repeated identical fix logs. */
    private var lastLoggedProvider: app.surrealar.gnss.model.Provider? = null

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

    // OpenGL renderers for different AR elements
    private var backgroundRenderer: BackgroundRenderer? = null  // Camera background (also owns OES texture)
    private var cubeRenderer: SimpleObjectRenderer? = null      // 3D objects/pins
    private var planeVisualizer: PlaneVisualizer? = null        // Detected planes
    private var pointCloudRenderer: PointCloudRenderer? = null  // Point cloud visualization
    // Filament-based renderer for GLB 3D models overlaid on the AR camera feed
    private var filamentRenderer: ArFilamentRenderer? = null

    // Local anchor for tap-to-place (single green floor square — tap on a detected plane)
    private var demoFloorAnchor: Anchor? = null

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

    // Thread-safe touch input handling
    @Volatile
    private var queuedTap: PointF? = null

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

    // ── Compact AR/model session tallies — written to ar-last-session.txt on close so the export
    // still summarizes the session even after the rolling event log has rotated. Not an event log.
    @Volatile private var sessionOpenedAtMs = 0L
    @Volatile private var sessionCoordinatesConsidered = 0
    @Volatile private var sessionModelLinked = 0
    @Volatile private var sessionAnchorsCreated = 0
    @Volatile private var sessionAnchorFailures = 0
    @Volatile private var sessionModelsInScene = 0
    @Volatile private var sessionEarthTrackingReached = false
    @Volatile private var sessionLastSkipReason: String? = null


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
         * Uniform scale applied to every GLB model world matrix before handing it to Filament.
         * Adjust to match the native size of your models.
         */
        private const val MODEL_SCALE = 2f
        /** Near/far clip planes — must match [ArFilamentRenderer] constants. */
        private const val NEAR_CLIP = 0.1f
        private const val FAR_CLIP  = 2000f
        /**
         * Minimum milliseconds between any two anchor rebuilds (manual button resets this).
         * Prevents ARCore resource exhaustion when RTK quality steps through SINGLE→DGPS→FLOAT→FIX
         * in rapid succession after switching to an external receiver like the RS2+.
         */
        private const val REANCHOR_COOLDOWN_MS = 30_000L
        /**
         * Approximate camera lens height above ground when holding a phone at eye level.
         * Used to convert `cameraGeospatialPose.altitude` to a ground-level estimate
         * in terrain-altitude mode.
         */
        private const val CAMERA_EYE_HEIGHT_M = 1.65
    }

    /**
     * When false (default): real coordinate anchors are placed at their stored ellipsoidal altitude.
     * When true (terrain mode): all anchors are placed at the current detected ground level
     * (`cameraGeospatialPose.altitude` − [CAMERA_EYE_HEIGHT_M]), ignoring stored altitudes.
     * Written on the main thread (button click); read on the GL thread (rebuildGeoAnchorsIfNeeded).
     */
    @Volatile private var useTerrainAltitude: Boolean = false
    @Volatile private var arModelScale: Float = 2f
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
            // Always pump asset loading / scene management so models load as soon as
            // preload() is called — even before ARCore anchors start tracking or the
            // Filament TextureView surface has been fully created.
            val currentPoses = modelPoses
            filamentRenderer?.tickAndApplyLoads(currentPoses)
            val hasModels = currentPoses.isNotEmpty()
            // Skip GPU rendering when no models are present and none were present last frame.
            // When models are just cleared (choreoPrevHadModels=true, hasModels=false), we
            // fall through and render one transparent frame so Filament clears the TextureView.
            if (!hasModels && !choreoPrevHadModels) {
                if ((choreographerFrameCount % 300L) == 0L)
                    android.util.Log.d(ArFilamentRenderer.DIAG,
                        "Choreographer#$choreographerFrameCount — modelPoses empty (no anchors tracking or no models)")
                return
            }
            val vm = arViewMatrix ?: return
            val pm = arProjMatrix ?: return
            filamentRenderer?.renderFrame(frameTimeNs, vm, pm)
            // Update only after a successful render so we retry the clear frame if
            // camera matrices were unavailable this tick.
            choreoPrevHadModels = hasModels
        }
    }

    // ARCore matrices + model poses — written by the GL thread each frame,
    // read by the Choreographer callback on the main thread (@Volatile for visibility).
    @Volatile private var arViewMatrix: FloatArray? = null
    @Volatile private var arProjMatrix: FloatArray? = null
    @Volatile private var modelPoses: List<ArFilamentRenderer.ModelPose> = emptyList()

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
        sessionOpenedAtMs = System.currentTimeMillis()
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
        filamentRenderer = ArFilamentRenderer().also { it.init(binding.filamentSurface) }

        // Prepare the test-pin asset and preload its keys once ready.
        testAnchorController.prepareAsset(viewLifecycleOwner.lifecycleScope) { _ ->
            filamentRenderer?.let { renderer ->
                testAnchorController.preloadModelKeys(renderer, viewLifecycleOwner.lifecycleScope)
            }
        }

        checkAvailabilityAndInstall()

        // Back button — always visible, exits AR and returns to previous destination.
        binding.btnArBack.setOnClickListener {
            findNavController().popBackStack()
        }

        // Toolbar toggle — show/hide with a slide-in animation from the right edge.
        binding.btnToolbarToggle.setOnClickListener {
            if (toolbarVisible) hideToolbar() else showToolbar()
        }

        binding.btnSpawnTestPoints.setOnClickListener {
            if (testAnchorController.isActive) {
                testAnchorController.requestClear()
                binding.txtTestPoints.text = "Test"
            } else {
                filamentRenderer?.let { r ->
                    testAnchorController.preloadModelKeys(r, viewLifecycleOwner.lifecycleScope)
                }
                testAnchorController.requestSpawn()
                binding.txtTestPoints.text = "Clear"
            }
        }

        binding.btnToggleDebugOverlay.setOnClickListener {
            viewModel.toggleDebug()
        }

        binding.btnTogglePlanes.setOnClickListener {
            viewModel.togglePlanes()
        }

        binding.btnTogglePointCloud.setOnClickListener {
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

        binding.btnDistanceFilter.setOnClickListener {
            viewModel.cycleDistanceFilter()
        }

        binding.btnAltitudeMode.setOnClickListener {
            useTerrainAltitude = !useTerrainAltitude
            shouldRebuildAnchors = true
            binding.txtAltitudeMode.text = if (useTerrainAltitude) "Terrain" else "Stored"
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

        // Keep local volatile copy of distance filter in sync for the GL thread.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.distanceFilterM.collect { distanceFilterM = it }
            }
        }

        // Keep button label in sync with filter state.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.distanceFilterLabel.collect { label ->
                    // Strip the leading emoji prefix and trim to keep the label short.
                    binding.txtDistanceFilter.text = label.replace("📏 ", "").take(8)
                }
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
        try { session?.pause() } catch (_: Exception) {}

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
            "(anchors=${geoAnchors.size}, modelPoses=${modelPoses.size}, items=${geoItems.size}); " +
            "pending view-scoped callbacks cancelled")
        writeArSessionSummary()
        choreographerInstance?.removeFrameCallback(filamentFrameCallback)
        choreographerInstance = null
        choreoPrevHadModels = false
        filamentRenderer?.destroy()
        filamentRenderer = null
        arViewMatrix  = null
        arProjMatrix  = null
        modelPoses    = emptyList()
        pinScreenCache = emptyList()
        lastPostedLabels = emptyList()
        lastPostedArrows = emptyList()
        lastModelProgress = null
        hideModelProgressJob?.cancel()
        hideModelProgressJob = null
        // Clean up test anchors via controller
        testAnchorController.cleanup()
        // Clean up tap-to-place floor anchor
        try { demoFloorAnchor?.detach() } catch (_: Exception) {}
        demoFloorAnchor = null
        // Clean up geospatial anchors
        for (entry in geoAnchors) try { entry.anchor.detach() } catch (_: Exception) {}
        geoAnchors.clear()
        geoAnchorsCreated = false
        lastAnchorRtkStatus = null
        lastReanchorTimeMs = 0L
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
        if (sessionOpenedAtMs == 0L) return
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val now = System.currentTimeMillis()
        val summary = buildString {
            appendLine("=== AR last session ===")
            appendLine("opened   : ${fmt.format(Date(sessionOpenedAtMs))}")
            appendLine("closed   : ${fmt.format(Date(now))}")
            appendLine("durationS: ${(now - sessionOpenedAtMs) / 1000}")
            appendLine("coordinatesConsidered : $sessionCoordinatesConsidered")
            appendLine("coordinatesWithModel  : $sessionModelLinked")
            appendLine("anchorsCreated        : $sessionAnchorsCreated")
            appendLine("anchorFailures        : $sessionAnchorFailures")
            appendLine("modelsInScene(max)    : $sessionModelsInScene")
            appendLine("earthTrackingReached  : $sessionEarthTrackingReached")
            appendLine("lastSkipReason        : ${sessionLastSkipReason ?: "none"}")
            appendLine("(detailed per-model events are in app-log-*.txt under tags AR / AR_MDL)")
        }
        DiagnosticsLogger.writeSessionSummary("ar-last-session.txt", summary)
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
        val config = Config(ses).apply {
            // Try to enable geospatial mode for GPS anchoring (optional - AR works without it)
            if (ses.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
                try {
                    geospatialMode = Config.GeospatialMode.ENABLED
                    android.util.Log.d(TAG, "Geospatial mode enabled")
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to enable geospatial mode: ${e.message}")
                }
            } else {
                android.util.Log.d(TAG, "Geospatial mode not supported on this device")
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
            android.util.Log.d(TAG, "AR session configured successfully")
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
                sessionLastSkipReason = "gnss accuracy too poor"
                DiagnosticsLogger.w(DIAG, "anchor skip reason=\"gnss accuracy too poor\" " +
                    "accuracyM=${"%.1f".format(accuracyM)} maxM=$MAX_GNSS_ACCURACY_M items=${geoItems.size}")
            }
            return
        }
        loggedAccuracyGate = false

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

        // In terrain mode, snap every anchor to the current detected ground level so
        // models appear on the ground regardless of their stored altitude.
        // Ground level = camera ellipsoidal altitude − eye height above ground.
        val terrainAlt: Double? = if (useTerrainAltitude)
            earth.cameraGeospatialPose.altitude - CAMERA_EYE_HEIGHT_M
        else null

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
                    DiagnosticsLogger.w(DIAG, "anchor failed coordinateId=${item.coordWithModel.coordinate.id} " +
                        "name=\"${item.coordWithModel.coordinate.name}\" mode=TERRAIN reason=\"${e.message}\"", e)
                }
            }
            DiagnosticsLogger.i(DIAG, "anchors created count=${geoAnchors.size} failed=$failed mode=TERRAIN " +
                "trackingState=${earth.trackingState} earthState=$earthStateName " +
                "accuracyM=${accuracyM?.let { "%.1f".format(it) } ?: "n/a"} — will appear as terrain resolves")
            sessionAnchorsCreated = maxOf(sessionAnchorsCreated, geoAnchors.size)
            sessionAnchorFailures += failed
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
                    DiagnosticsLogger.w(DIAG, "anchor failed coordinateId=${item.coordWithModel.coordinate.id} " +
                        "name=\"${item.coordWithModel.coordinate.name}\" mode=STORED reason=\"${e.message}\"", e)
                }
            }
            DiagnosticsLogger.i(DIAG, "anchors created count=${geoAnchors.size} failed=$failed mode=STORED " +
                "trackingState=${earth.trackingState} earthState=$earthStateName " +
                "accuracyM=${accuracyM?.let { "%.1f".format(it) } ?: "n/a"}")
            sessionAnchorsCreated = maxOf(sessionAnchorsCreated, geoAnchors.size)
            sessionAnchorFailures += failed
        }

        geoAnchorsCreated = true
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
        } catch (_: Exception) {
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

            // 1) Camera background
            backgroundRenderer?.draw(frame)

            // 2) Earth / anchor management
            val earth       = s.earth
            val earthStatus = processEarthAndAnchors(earth)

            // 3) Tap-to-place floor marker
            processTapToPlace(frame, camera)

            // 4) 3-D rendering (only when camera is tracking)
            var planeCount = 0
            var pointCount = 0
            if (camera.trackingState == TrackingState.TRACKING) {
                camera.getViewMatrix(view, 0)
                camera.getProjectionMatrix(proj, 0, NEAR_CLIP, FAR_CLIP)
                Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

                arViewMatrix = view.copyOf()
                arProjMatrix = proj.copyOf()

                pointCount = if (arDebugToolsEnabled && arShowPointCloud) pointCloudRenderer?.draw(frame, vp) ?: 0 else 0
                planeCount = if (arDebugToolsEnabled && arShowPlanes) planeVisualizer?.drawAllPlanes(s, vp) ?: 0 else 0

                drawFloorMarker(vp)

                val margin        = edgeMarginPx
                val pinPositions  = mutableListOf<PinScreenEntry>()
                val labelEntries  = mutableListOf<CoordinateLabelOverlay.LabelEntry>()
                val arrowEntries  = mutableListOf<OffScreenPinIndicatorOverlay.ArrowEntry>()
                val newModelPoses = mutableListOf<ArFilamentRenderer.ModelPose>()

                collectGeoAnchors(earth, vp, margin, pinPositions, labelEntries, arrowEntries, newModelPoses)
                testAnchorController.render(vp, model, modelScaled, mvp, cubeRenderer,
                    surfaceWidth, surfaceHeight, margin, labelEntries, arrowEntries, newModelPoses)

                pinScreenCache = pinPositions
                modelPoses     = newModelPoses
                postOverlayUpdates(labelEntries, arrowEntries)
            }

            // 5) Status bar, debug panel, model progress chip (posted to main thread)
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
            DiagnosticsLogger.i(DIAG, "Re-anchor (manual) — anchors cleared for rebuild")
        }

        if (earth == null) return "Geo: N/A"

        return when (earth.trackingState) {
            TrackingState.TRACKING -> {
                sessionEarthTrackingReached = true
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
                        DiagnosticsLogger.i(DIAG, "Re-anchor (auto) — RTK upgrade $lastAnchorRtkStatus → ${currentFix.rtkStatus}")
                        for (entry in geoAnchors) try { entry.anchor.detach() } catch (_: Exception) {}
                        geoAnchors.clear()
                        geoAnchorsCreated = false
                        lastAnchorRtkStatus = null
                        lastReanchorTimeMs = System.currentTimeMillis()
                        loggedAccuracyGate = false
                    }
                }
                rebuildGeoAnchorsIfNeeded(earth)
                val fix = currentGnssFix
                if (fix != null) {
                    testAnchorController.processActionIfNeeded(earth, fix.latDeg, fix.lonDeg)
                }
                "Geo: TRACKING"
            }
            TrackingState.PAUSED  -> "Geo: Localizing..."
            TrackingState.STOPPED -> "Geo: Off (AR active)"
        }
    }

    /** Processes a queued tap: places a floor marker or logs why it failed. */
    private fun processTapToPlace(frame: Frame, camera: Camera) {
        queuedTap?.let { pt ->
            if (camera.trackingState == TrackingState.TRACKING) {
                val hits = frame.hitTest(pt.x, pt.y)
                var found = false
                for ((index, hit) in hits.withIndex()) {
                    val trackable  = hit.trackable
                    val isPlane    = trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)
                    val isPoint    = trackable is Point &&
                            trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                    if (isPlane || isPoint) {
                        found = true
                        try {
                            demoFloorAnchor?.detach()
                            demoFloorAnchor = hit.createAnchor()
                            android.util.Log.d(TAG, "✅ Floor marker placed at hit $index")
                        } catch (e: Exception) {
                            android.util.Log.e(TAG, "❌ Failed to place anchor: ${e.message}", e)
                        }
                        break
                    }
                }
                if (!found) android.util.Log.w(TAG,
                    if (hits.isEmpty()) "⚠️ No hits — tap on a detected plane"
                    else "⚠️ ${hits.size} hits but none were valid planes/points")
            }
            queuedTap = null
        }
    }

    /** Renders the demo floor marker (bright-green flat square) if its anchor is tracking. */
    private fun drawFloorMarker(vp: FloatArray) {
        demoFloorAnchor?.let { anchor ->
            if (anchor.trackingState == TrackingState.TRACKING) {
                anchor.pose.toMatrix(model, 0)
                System.arraycopy(model, 0, modelScaled, 0, 16)
                Matrix.scaleM(modelScaled, 0, 0.3f, 0.02f, 0.3f)
                Matrix.multiplyMM(mvp, 0, vp, 0, modelScaled, 0)
                cubeRenderer?.draw(mvp, 0.0f, 1.0f, 0.0f, 1.0f)
            }
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

        for (entry in geoAnchors) {
            if (entry.anchor.trackingState != TrackingState.TRACKING) continue
            entry.anchor.pose.toMatrix(model, 0)
            System.arraycopy(model, 0, modelScaled, 0, 16)

            val coord = entry.coordWithModel.coordinate
            val distM = haversineM(camGeoPose.latitude, camGeoPose.longitude,
                coord.latitude, coord.longitude)

            if (filterM != null && distM > filterM) {
                distanceSkipped += coord.id
                continue
            }

            val entryModelFilePath = entry.modelFilePath
            if (entryModelFilePath != null) {
                val scaledWorld = model.copyOf()
                val s = arModelScale
                Matrix.scaleM(scaledWorld, 0, s, s, s)
                newModelPoses += ArFilamentRenderer.ModelPose(
                    key         = coord.id,
                    worldMatrix = scaledWorld,
                    filePath    = entryModelFilePath
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

        // Log only when the distance-filtered set changes — never every frame.
        if (distanceSkipped != lastDistanceSkippedIds) {
            val newlyHidden = distanceSkipped - lastDistanceSkippedIds
            lastDistanceSkippedIds = distanceSkipped
            if (newlyHidden.isNotEmpty()) {
                sessionLastSkipReason = "distance filter"
                DiagnosticsLogger.i(DIAG, "scene skip reason=\"distance filter\" " +
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
        // Extract all ARCore earth values NOW on the GL thread before posting.
        val snapshot: EarthDebugSnapshot? = if (earth != null) {
            val camGeo = runCatching {
                if (earth.trackingState == TrackingState.TRACKING) earth.cameraGeospatialPose else null
            }.getOrNull()
            EarthDebugSnapshot(
                earthStateName = earth.earthState?.name ?: "null",
                camLat         = camGeo?.latitude,
                camLon         = camGeo?.longitude,
                camAlt         = camGeo?.altitude,
                camHeading     = camGeo?.heading
            )
        } else null

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
        val posesSnapshot = modelPoses
        val total   = posesSnapshot.size
        val inScene = posesSnapshot.count {
            filamentRenderer?.modelLoadState(it.key) == ArFilamentRenderer.ModelLoadState.IN_SCENE
        }
        val progress = inScene to total
        if (progress == lastModelProgress) return
        lastModelProgress = progress
        // Fires only on change (early-return above) — reports models currently rendered in scene.
        DiagnosticsLogger.i(DIAG, "scene state modelsInScene=$inScene total=$total activeAnchors=${geoAnchors.size}")
        sessionModelsInScene = maxOf(sessionModelsInScene, inScene)

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
        sessionCoordinatesConsidered = maxOf(sessionCoordinatesConsidered, items.size)
        sessionModelLinked = maxOf(sessionModelLinked, withModels.size)
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
        geoAnchorsCreated = false

        // Kick off Filament GLB loading for every model-linked coordinate.
        // This runs on the main thread (StateFlow collector), which is where
        // Filament API calls are required.
        val scope = viewLifecycleOwner.lifecycleScope
        items.forEach { item ->
            val coord = item.coordinate
            val path  = item.modelFilePath
            if (path == null) return@forEach   // coordinate has no linked model — pin/label only
            // Record per-model context up front so the export can pinpoint why a model
            // did or did not render (missing file, disabled visibility, altitude, scale, placement).
            val file   = File(path)
            val exists = file.exists()
            val altState = if (coord.altitude.isNaN() || !coord.altitude.isFinite()) "missing" else "stored"
            DiagnosticsLogger.i(DIAG,
                "preload dispatch coordinateId=${coord.id} modelId=${item.modelId} " +
                "name=\"${coord.name}\" path=\"$path\" exists=$exists " +
                "sizeBytes=${if (exists) file.length() else -1L} renderEnabled=${coord.renderEnabled} " +
                "altitude=$altState scale=$arModelScale placement=${item.placement}")
            when {
                !exists -> {
                    sessionLastSkipReason = "file missing"
                    DiagnosticsLogger.w(DIAG,
                        "scene skip coordinateId=${coord.id} modelId=${item.modelId} reason=\"file missing\" path=\"$path\"")
                }
                // renderEnabled gates whether a linked model is drawn in AR. When false we simply
                // don't preload it, so the coordinate still shows its pin/label but no 3D model.
                !coord.renderEnabled -> {
                    sessionLastSkipReason = "visibility off"
                    DiagnosticsLogger.i(DIAG,
                        "scene skip coordinateId=${coord.id} modelId=${item.modelId} reason=\"visibility off\"")
                }
                else ->
                    filamentRenderer?.preload(coord.id, path, scope, item.placement)
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

                // No pin tapped — queue tap for demo floor-marker placement
                queuedTap = PointF(tapX, tapY)
                v.performClick()
                true
            } else {
                false
            }
        }

        android.util.Log.d("OpenInARFragment", "✅ GLSurfaceView setup complete with touch listener")
    }
}