package com.example.surveyingapp.ui.openinar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PointF
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.os.Bundle
import android.content.SharedPreferences
import com.example.surveyingapp.ui.settings.SettingsFragment
import kotlin.math.max

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.surveyingapp.R
import com.example.surveyingapp.domain.model.Coordinate
import com.example.surveyingapp.data.local.dao.CoordinateDao
import com.example.surveyingapp.data.local.entity.CoordinateEntity
import com.example.surveyingapp.databinding.FragmentOpenInArBinding
import com.google.ar.core.*
import com.google.ar.core.Point
import com.google.ar.core.Plane
import com.google.ar.core.exceptions.*
import com.google.ar.core.Coordinates2d
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * AR Fragment that displays surveying coordinates as geospatial anchors in AR space.
 */
@SuppressLint("SetTextI18n")
class OpenInARFragment : Fragment(), GLSurfaceView.Renderer {

    private var _binding: FragmentOpenInArBinding? = null
    private val binding get() = _binding!!

    // ARCore session management
    private var session: Session? = null
    private var installRequested = false
    private var availabilityPolling = false

    // OpenGL texture for camera feed
    private var cameraTextureId: Int = -1

    // OpenGL renderers for different AR elements
    private var backgroundRenderer: BackgroundRenderer? = null  // Camera background
    private var cubeRenderer: SimpleObjectRenderer? = null      // 3D objects/pins
    private var planeVisualizer: PlaneVisualizer? = null        // Detected planes
    private var pointCloudRenderer: PointCloudRenderer? = null  // Point cloud visualization

    // Local anchor for tap-to-place functionality
    private var demoAnchor: Anchor? = null

    // Database connection for coordinate data
    private var coordinateDao: CoordinateDao? = null
    private var liveObserverAttached = false

    // Data class representing a geospatial item to display in AR
    private data class GeoItem(
        val lat: Double,
        val lng: Double,
        val alt: Double?,            // null => use camera altitude
        val label: String?,
        val rgba: FloatArray         // 4 floats 0..1 from ARGB Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as GeoItem
            return lat == other.lat &&
                    lng == other.lng &&
                    alt == other.alt &&
                    label == other.label &&
                    rgba.contentEquals(other.rgba)
        }

        override fun hashCode(): Int {
            var result = lat.hashCode()
            result = 31 * result + lng.hashCode()
            result = 31 * result + (alt?.hashCode() ?: 0)
            result = 31 * result + (label?.hashCode() ?: 0)
            result = 31 * result + rgba.contentHashCode()
            return result
        }
    }

    // Collections for managing geospatial anchors
    private val geoItems: MutableList<GeoItem> = mutableListOf()
    private val geoAnchors: MutableList<Pair<Anchor, FloatArray>> = mutableListOf()
    private var geoAnchorsCreated = false

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

    // State flags for error recovery and user experience
    private var attemptedEarthRestart = false
    private var hasWarnedLocationOff = false
    private var hasWarnedNoNetwork = false

    // Permission request launchers using modern Activity Result API
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) checkAvailabilityAndInstall()
            else binding.textArStatus.text = getString(R.string.camera_permission_denied)
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) binding.textArStatus.text = getString(R.string.location_permission_needed)
        }

    // SharedPreferences for high accuracy setting
    private var prefs: SharedPreferences? = null
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SettingsFragment.PREF_HIGH_ACCURACY) {
            applyHighAccuracyPreference()
        }
    }

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
        setupGlSurface()
        binding.textArStatus.text = getString(R.string.checking_ar_availability)
        // Load preferences
        prefs =
            requireContext().getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        applyHighAccuracyPreference()
        return binding.root
    }

    /**
     * Attach observer for coordinate data and check AR availability
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        attachObserverIfPossible()
        checkAvailabilityAndInstall()
    }

    /**
     * Resume AR session and check all prerequisites
     */
    override fun onResume() {
        super.onResume()
        // Ensure we have required permissions before starting AR
        if (!checkAndRequestCameraPermission()) return
        if (!checkAndRequestLocationPermission()) return

        // Create AR session if needed
        if (session == null) checkAvailabilityAndInstall()

        // Provide user feedback for common issues
        if (!isLocationServicesEnabled() && !hasWarnedLocationOff) {
            hasWarnedLocationOff = true
            binding.textArStatus.text = "Turn Location services ON (GPS + network)."
            // Optionally: startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        if (!hasNetwork() && !hasWarnedNoNetwork) {
            hasWarnedNoNetwork = true
            binding.textArStatus.text = "No internet — Geospatial may not localize"
        }

        // Resume AR session and related components
        try {
            session?.resume()
            binding.glSurfaceViewAr.onResume()
            binding.textArStatus.text = getString(R.string.ar_running)
        } catch (_: CameraNotAvailableException) {
            binding.textArStatus.text = getString(R.string.camera_unavailable)
            try {
                session?.pause()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Pause AR session and components
     */
    override fun onPause() {
        super.onPause()
        binding.glSurfaceViewAr.onPause()
        try {
            session?.pause()
        } catch (_: Exception) {
        }
    }

    /**
     * Clean up AR resources when view is destroyed
     */
    override fun onDestroyView() {
        super.onDestroyView()
        prefs = null
        // Clean up anchors
        try {
            demoAnchor?.detach()
        } catch (_: Exception) {
        }
        demoAnchor = null
        for ((anchor, _) in geoAnchors) try {
            anchor.detach()
        } catch (_: Exception) {
        }
        geoAnchors.clear()
        // Clean up AR session
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        _binding = null
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
     * Create a new ARCore session with required configuration
     */
    private fun tryCreateSession() {
        try {
            val ses = Session(requireContext())
            session = ses
            configureSession()
        } catch (_: CameraNotAvailableException) {
            binding.textArStatus.text = getString(R.string.camera_unavailable)
        } catch (e: Exception) {
            binding.textArStatus.text = "Session error: ${e.message ?: "Unknown"}"
        }
    }

    /**
     * Configure ARCore session with geospatial and other features
     */
    private fun configureSession() {
        val ses = session ?: return
        val config = Config(ses).apply {
            // Enable geospatial mode for GPS anchoring
            if (ses.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
                geospatialMode = Config.GeospatialMode.ENABLED
            } else {
                binding.textArStatus.text = "Geospatial not supported on this device"
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
        ses.configure(config)
        if (cameraTextureId > 0) ses.setCameraTextureName(cameraTextureId)
        binding.textArStatus.text = getString(R.string.ar_session_created)
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
                    binding.textArStatus.text = "Requesting ARCore install..."
                }

                ArCoreApk.InstallStatus.INSTALLED -> tryCreateSession()
            }
        } catch (_: UnavailableUserDeclinedInstallationException) {
            binding.textArStatus.text = "ARCore install declined"
        } catch (e: Exception) {
            binding.textArStatus.text = "Install check failed: ${e.message ?: "Unknown"}"
        }
    }

    /**
     * Check ARCore availability and trigger installation if needed
     */
    private fun checkAvailabilityAndInstall() {
        val availability = ArCoreApk.getInstance().checkAvailability(requireContext())
        when (availability) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> tryCreateSession()
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> checkInstallation()

            ArCoreApk.Availability.UNKNOWN_CHECKING -> {
                // Poll until availability is determined
                if (!availabilityPolling) {
                    availabilityPolling = true
                    binding.textArStatus.postDelayed({
                        availabilityPolling = false
                        if (isAdded) checkAvailabilityAndInstall()
                    }, 200)
                }
            }

            else -> {
                binding.textArStatus.text = when (availability) {
                    ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> "Device not compatible"
                    else -> "AR not supported"
                }
            }
        }
    }

    /**
     * Convert Android ARGB color int to normalized RGBA float array
     * Ensures minimum alpha of 0.2 for visibility
     */
    private fun argbIntToRgba(argb: Int): FloatArray {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = (argb) and 0xFF
        return floatArrayOf(r / 255f, g / 255f, b / 255f, max(0.2f, a / 255f))
    }

    // ---------------------------------------------------------------------------------------------
    // Geospatial Anchoring & Earth Tracking

    /**
     * Create geospatial anchors for all coordinate points when Earth tracking is stable
     */
    private fun rebuildGeoAnchorsIfNeeded() {
        if (geoAnchorsCreated || geoItems.isEmpty()) return
        val earth = session?.earth ?: return
        if (earth.trackingState != TrackingState.TRACKING) return

        val camGeo = earth.cameraGeospatialPose
        // Wait for reasonable accuracy before creating anchors
        if (camGeo.horizontalAccuracy > 20.0) return

        // Clean up existing anchors
        for ((a, _) in geoAnchors) try {
            a.detach()
        } catch (_: Exception) {
        }
        geoAnchors.clear()

        // Create new anchors for each coordinate
        for (item in geoItems) {
            val altToUse = item.alt ?: camGeo.altitude
            try {
                // Create anchor with identity rotation (no specific orientation needed for pins)
                val anchor = earth.createAnchor(item.lat, item.lng, altToUse, 0f, 0f, 0f, 1f)
                geoAnchors.add(anchor to item.rgba)
            } catch (_: Exception) {
                // Skip invalid coordinates silently
            }
        }
        geoAnchorsCreated = true
    }

    /**
     * Attempt to restart Earth tracking if it stops (one-time recovery)
     */
    private fun tryRestartEarthOnce() {
        val s = session ?: return
        if (attemptedEarthRestart) return
        attemptedEarthRestart = true
        try {
            s.pause()
            configureSession()  // Ensure geospatial stays enabled
            s.resume()
            geoAnchorsCreated = false
        } catch (_: Exception) {
            // Ignore restart failures
        }
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

    /**
     * Apply the high accuracy preference based on user settings
     */
    private fun applyHighAccuracyPreference() {
        val wantHigh = prefs?.getBoolean(SettingsFragment.PREF_HIGH_ACCURACY, true) ?: true
        if (!isAdded || _binding == null) return
        if (wantHigh) {
            // If user wants high accuracy but GPS provider is off, prompt them
            val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val gpsOn = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            if (!gpsOn) {
                binding.textArStatus.text = "Enable GPS for high accuracy AR positioning"
            }
        } else {
            // Balanced mode: we can note that network is sufficient (only update if current text is our prior warning)
            if (binding.textArStatus.text.toString().contains("high accuracy", true)) {
                binding.textArStatus.text = "Balanced accuracy mode"
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // GLSurfaceView.Renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)

        cameraTextureId = createCameraTexture()
        backgroundRenderer = BackgroundRenderer()
        cubeRenderer = SimpleObjectRenderer()
        planeVisualizer = PlaneVisualizer()
        pointCloudRenderer = PointCloudRenderer()

        session?.setCameraTextureName(cameraTextureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        // rotationHelper.onSurfaceChanged(width, height) // removed
        // Fixed landscape: set display geometry once
        try {
            session?.setDisplayGeometry(Surface.ROTATION_90, width, height)
        } catch (_: Exception) {
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val s = session ?: return
        try {
            // rotationHelper.updateSessionIfNeeded(s) // removed (fixed landscape)
            if (cameraTextureId > 0) s.setCameraTextureName(cameraTextureId)

            val frame: Frame = s.update()
            val camera = frame.camera

            // 1) Background
            backgroundRenderer?.draw(frame, cameraTextureId)

            // 2) Earth state handling
            val earth = s.earth
            var earthStatus = "Earth: none"
            if (earth != null) {
                when (earth.trackingState) {
                    TrackingState.TRACKING -> {
                        earthStatus = "Earth: TRACKING"
                        attemptedEarthRestart = false
                        // Create anchors once when ready
                        rebuildGeoAnchorsIfNeeded()
                    }

                    TrackingState.PAUSED -> {
                        earthStatus = "Earth: PAUSED (localizing…)"
                        binding.textArStatus.post {
                            binding.textArStatus.text =
                                "Localizing... Please wait or move to an open area with a clear view of the sky."
                        }
                    }

                    TrackingState.STOPPED -> {
                        earthStatus = "Earth: STOPPED"
                        tryRestartEarthOnce()
                        binding.textArStatus.post {
                            binding.textArStatus.text = getString(R.string.geospatial_stopped)
                        }
                    }
                }
            }

            // 3) Tap-to-place local cube
            queuedTap?.let { pt ->
                if (camera.trackingState == TrackingState.TRACKING) {
                    val hits = frame.hitTest(pt.x, pt.y)
                    for (hit in hits) {
                        val trackable = hit.trackable
                        val isPlaneHit =
                            trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)
                        val isPointHit = trackable is Point &&
                                trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                        if (isPlaneHit || isPointHit) {
                            try {
                                demoAnchor?.detach()
                            } catch (_: Exception) {
                            }
                            demoAnchor = hit.createAnchor()
                            break
                        }
                    }
                }
                queuedTap = null
            }

            var planeCount = 0
            var pointCount = 0

            // 4) Draw helpers & objects when tracking
            if (camera.trackingState == TrackingState.TRACKING) {
                camera.getViewMatrix(view, 0)
                camera.getProjectionMatrix(proj, 0, 0.1f, 2000f)
                Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

                // helpers
                pointCount = pointCloudRenderer?.draw(frame, vp) ?: 0
                planeCount = planeVisualizer?.drawAllPlanes(s, vp) ?: 0

                // local cube
                demoAnchor?.let { anchor ->
                    if (anchor.trackingState == TrackingState.TRACKING) {
                        anchor.pose.toMatrix(model, 0)
                        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
                        cubeRenderer?.draw(mvp, 0.6f, 0.4f, 0.9f, 1f)
                    }
                }

                // geospatial pins
                if (earth?.trackingState == TrackingState.TRACKING) {
                    for ((anchor, rgba) in geoAnchors) {
                        if (anchor.trackingState != TrackingState.TRACKING) continue
                        anchor.pose.toMatrix(model, 0)
                        System.arraycopy(model, 0, modelScaled, 0, 16)
                        Matrix.scaleM(modelScaled, 0, 0.1f, 0.3f, 0.1f) // thin tall pin
                        Matrix.multiplyMM(mvp, 0, vp, 0, modelScaled, 0)
                        cubeRenderer?.draw(mvp, rgba[0], rgba[1], rgba[2], rgba[3])
                    }
                }
            }

            // 5) UI status
            binding.textArStatus.post {
                binding.textArStatus.text =
                    "AR running • Planes: $planeCount • Points: $pointCount • GeoPins: ${geoAnchors.size} • $earthStatus"
            }

        } catch (_: CameraNotAvailableException) {
            // ignore
        } catch (_: Exception) {
            // ignore
        }
    }

    // ---------------------------------------------------------------------------------------------
    // GL helpers & renderers

    private fun createCameraTexture(): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val texId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES30.GL_TEXTURE_MAG_FILTER,
            GLES30.GL_LINEAR
        )
        return texId
    }

    // Background renderer using ARCore's UV transform (correct orientation)
    private class BackgroundRenderer {
        private val ndcQuad = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
        private val quadPos = floatArrayOf(
            -1f, -1f, 0f,
            1f, -1f, 0f,
            -1f, 1f, 0f,
            1f, 1f, 0f
        )
        private val posBuffer: FloatBuffer = ByteBuffer.allocateDirect(quadPos.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(quadPos); position(0) }

        private val uvData = FloatArray(8)
        private val uvBuffer: FloatBuffer = ByteBuffer.allocateDirect(uvData.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

        private val program: Int
        private val attribPos = 0
        private val attribUv = 1
        private var haveValidUvs = false

        init {
            program = createProgram(VS_BG, FS_BG)
        }

        fun draw(frame: Frame, oesTexId: Int) {
            if (oesTexId <= 0) return
            if (frame.hasDisplayGeometryChanged() || !haveValidUvs) {
                frame.transformCoordinates2d(
                    Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndcQuad,
                    Coordinates2d.TEXTURE_NORMALIZED, uvData
                )
                uvBuffer.clear(); uvBuffer.put(uvData); uvBuffer.rewind()
                haveValidUvs = true
            }

            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glUseProgram(program)

            posBuffer.position(0)
            GLES30.glVertexAttribPointer(attribPos, 3, GLES30.GL_FLOAT, false, 3 * 4, posBuffer)
            GLES30.glEnableVertexAttribArray(attribPos)

            uvBuffer.position(0)
            GLES30.glVertexAttribPointer(attribUv, 2, GLES30.GL_FLOAT, false, 0, uvBuffer)
            GLES30.glEnableVertexAttribArray(attribUv)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            GLES30.glDisableVertexAttribArray(attribPos)
            GLES30.glDisableVertexAttribArray(attribUv)
            GLES30.glUseProgram(0)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        }

        companion object {
            private const val VS_BG = """#version 300 es
                in vec3 aPos;
                in vec2 aUv;
                out vec2 vUv;
                void main(){ vUv = aUv; gl_Position = vec4(aPos, 1.0); }"""
            private const val FS_BG = """#version 300 es
                #extension GL_OES_EGL_image_external_essl3 : require
                precision mediump float;
                in vec2 vUv;
                uniform samplerExternalOES uTexOes;
                out vec4 fragColor;
                void main(){ fragColor = texture(uTexOes, vUv); }"""

            private fun createShader(type: Int, src: String): Int {
                val sh = GLES30.glCreateShader(type)
                GLES30.glShaderSource(sh, src)
                GLES30.glCompileShader(sh)
                val ok = IntArray(1); GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) {
                    val log = GLES30.glGetShaderInfoLog(sh)
                    GLES30.glDeleteShader(sh); throw RuntimeException("BG shader: $log")
                }
                return sh
            }

            private fun createProgram(vsSrc: String, fsSrc: String): Int {
                val vs = createShader(GLES30.GL_VERTEX_SHADER, vsSrc)
                val fs = createShader(GLES30.GL_FRAGMENT_SHADER, fsSrc)
                val prog = GLES30.glCreateProgram()
                GLES30.glAttachShader(prog, vs); GLES30.glAttachShader(prog, fs)
                GLES30.glBindAttribLocation(prog, 0, "aPos")
                GLES30.glBindAttribLocation(prog, 1, "aUv")
                GLES30.glLinkProgram(prog)
                val link = IntArray(1); GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, link, 0)
                if (link[0] == 0) {
                    val log = GLES30.glGetProgramInfoLog(prog)
                    GLES30.glDeleteProgram(prog); throw RuntimeException("BG link: $log")
                }
                GLES30.glUseProgram(prog)
                val texLoc = GLES30.glGetUniformLocation(prog, "uTexOes")
                GLES30.glUniform1i(texLoc, 0)
                GLES30.glUseProgram(0)
                GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
                return prog
            }
        }
    }

    // Plane boundary visualizer (yellow GL_LINE_LOOP)
    private class PlaneVisualizer {
        private val program: Int
        private val attribPos = 0
        private val uMvpLoc: Int
        private val uColorLoc: Int
        private var lineBuffer: FloatBuffer =
            ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer()

        init {
            program = createProgram(VS, FS)
            GLES30.glUseProgram(program)
            uMvpLoc = GLES30.glGetUniformLocation(program, "uMvp")
            uColorLoc = GLES30.glGetUniformLocation(program, "uColor")
            GLES30.glUseProgram(0)
        }

        fun drawAllPlanes(session: Session, vp: FloatArray): Int {
            var drawn = 0
            val planes = session.getAllTrackables(Plane::class.java)
            GLES30.glUseProgram(program)
            GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, vp, 0)
            GLES30.glUniform4f(uColorLoc, 1.0f, 0.9f, 0.0f, 1.0f)
            GLES30.glLineWidth(3f)

            for (plane in planes) {
                if (plane.trackingState != TrackingState.TRACKING) continue
                if (plane.subsumedBy != null) continue
                val poly: FloatBuffer? = plane.polygon
                if (poly == null || poly.limit() < 6) continue

                val vertCount = poly.limit() / 2
                val worldVerts = FloatArray(vertCount * 3)
                poly.rewind()
                for (i in 0 until vertCount) {
                    val x = poly.get(2 * i)
                    val z = poly.get(2 * i + 1)
                    val world = plane.centerPose.transformPoint(floatArrayOf(x, 0f, z))
                    worldVerts[3 * i] = world[0]
                    worldVerts[3 * i + 1] = world[1]
                    worldVerts[3 * i + 2] = world[2]
                }

                val needed = worldVerts.size * 4
                if (lineBuffer.capacity() * 4 < needed) {
                    lineBuffer = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
                        .asFloatBuffer()
                }
                lineBuffer.clear(); lineBuffer.put(worldVerts); lineBuffer.rewind()

                lineBuffer.position(0)
                GLES30.glVertexAttribPointer(
                    attribPos,
                    3,
                    GLES30.GL_FLOAT,
                    false,
                    3 * 4,
                    lineBuffer
                )
                GLES30.glEnableVertexAttribArray(attribPos)
                GLES30.glDrawArrays(GLES30.GL_LINE_LOOP, 0, vertCount)
                GLES30.glDisableVertexAttribArray(attribPos)

                drawn++
            }
            GLES30.glUseProgram(0)
            return drawn
        }

        companion object {
            private const val VS = """#version 300 es
                uniform mat4 uMvp;
                in vec3 aPos;
                void main(){ gl_Position = uMvp * vec4(aPos, 1.0); }"""
            private const val FS = """#version 300 es
                precision mediump float;
                uniform vec4 uColor;
                out vec4 fragColor;
                void main(){ fragColor = uColor; }"""

            private fun createShader(type: Int, src: String): Int {
                val sh = GLES30.glCreateShader(type)
                GLES30.glShaderSource(sh, src)
                GLES30.glCompileShader(sh)
                val ok = IntArray(1); GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) {
                    val log = GLES30.glGetShaderInfoLog(sh)
                    GLES30.glDeleteShader(sh); throw RuntimeException("Plane shader: $log")
                }
                return sh
            }

            private fun createProgram(vsSrc: String, fsSrc: String): Int {
                val vs = createShader(GLES30.GL_VERTEX_SHADER, vsSrc)
                val fs = createShader(GLES30.GL_FRAGMENT_SHADER, fsSrc)
                val prog = GLES30.glCreateProgram()
                GLES30.glAttachShader(prog, vs); GLES30.glAttachShader(prog, fs)
                GLES30.glBindAttribLocation(prog, 0, "aPos")
                GLES30.glLinkProgram(prog)
                val link = IntArray(1); GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, link, 0)
                if (link[0] == 0) {
                    val log = GLES30.glGetProgramInfoLog(prog)
                    GLES30.glDeleteProgram(prog); throw RuntimeException("Plane link: $log")
                }
                GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
                return prog
            }
        }
    }

    // Point cloud visualizer (white GL_POINTS)
    private class PointCloudRenderer {
        private val program: Int
        private val attribPos = 0
        private val uMvpLoc: Int
        private val uColorLoc: Int
        private val uPtSizeLoc: Int
        private var pointBuffer: FloatBuffer =
            ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer()

        init {
            program = createProgram(VS, FS)
            GLES30.glUseProgram(program)
            uMvpLoc = GLES30.glGetUniformLocation(program, "uMvp")
            uColorLoc = GLES30.glGetUniformLocation(program, "uColor")
            uPtSizeLoc = GLES30.glGetUniformLocation(program, "uPointSize")
            GLES30.glUseProgram(0)
        }

        fun draw(frame: Frame, vp: FloatArray): Int {
            val pointCloud = frame.acquirePointCloud()
            val pts = pointCloud.points
            val totalFloats = pts.limit()
            val count = totalFloats / 4
            if (count <= 0) {
                pointCloud.release(); return 0
            }

            val xyz = FloatArray(count * 3)
            pts.rewind()
            var j = 0
            while (pts.hasRemaining()) {
                xyz[j++] = pts.get()
                xyz[j++] = pts.get()
                xyz[j++] = pts.get()
                if (pts.hasRemaining()) pts.get()
            }

            val needed = xyz.size * 4
            if (pointBuffer.capacity() * 4 < needed) {
                pointBuffer =
                    ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder()).asFloatBuffer()
            }
            pointBuffer.clear(); pointBuffer.put(xyz); pointBuffer.rewind()

            val range = FloatArray(2)
            GLES30.glGetFloatv(GLES30.GL_ALIASED_POINT_SIZE_RANGE, range, 0)
            val desired = 6f
            val size = if (range[1] > 0f) desired.coerceIn(range[0], range[1]) else desired

            GLES30.glUseProgram(program)
            GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, vp, 0)
            GLES30.glUniform4f(uColorLoc, 1f, 1f, 1f, 1f)
            GLES30.glUniform1f(uPtSizeLoc, size)

            pointBuffer.position(0)
            GLES30.glVertexAttribPointer(attribPos, 3, GLES30.GL_FLOAT, false, 3 * 4, pointBuffer)
            GLES30.glEnableVertexAttribArray(attribPos)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, count)
            GLES30.glDisableVertexAttribArray(attribPos)
            GLES30.glUseProgram(0)

            pointCloud.release()
            return count
        }

        companion object {
            private const val VS = """#version 300 es
                uniform mat4 uMvp;
                uniform float uPointSize;
                in vec3 aPos;
                void main(){ gl_Position = uMvp * vec4(aPos,1.0); gl_PointSize = uPointSize; }"""
            private const val FS = """#version 300 es
                precision mediump float;
                uniform vec4 uColor;
                out vec4 fragColor;
                void main(){ fragColor = uColor; }"""

            private fun createShader(type: Int, src: String): Int {
                val sh = GLES30.glCreateShader(type)
                GLES30.glShaderSource(sh, src)
                GLES30.glCompileShader(sh)
                val ok = IntArray(1); GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) {
                    val log = GLES30.glGetShaderInfoLog(sh)
                    GLES30.glDeleteShader(sh); throw RuntimeException("PC shader: $log")
                }
                return sh
            }

            private fun createProgram(vsSrc: String, fsSrc: String): Int {
                val vs = createShader(GLES30.GL_VERTEX_SHADER, vsSrc)
                val fs = createShader(GLES30.GL_FRAGMENT_SHADER, fsSrc)
                val prog = GLES30.glCreateProgram()
                GLES30.glAttachShader(prog, vs); GLES30.glAttachShader(prog, fs)
                GLES30.glBindAttribLocation(prog, 0, "aPos")
                GLES30.glLinkProgram(prog)
                val link = IntArray(1); GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, link, 0)
                if (link[0] == 0) {
                    val log = GLES30.glGetProgramInfoLog(prog)
                    GLES30.glDeleteProgram(prog); throw RuntimeException("PC link: $log")
                }
                GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
                return prog
            }
        }
    }

    // Simple solid-color cube renderer (used as cube OR scaled "pin")
    private class SimpleObjectRenderer {
        private val program: Int
        private val attribPos = 0
        private val uMvpLoc: Int
        private val uColorLoc: Int

        private val s = 0.05f // 10 cm half-size
        private val cubeVerts = floatArrayOf(
            // Front
            -s, -s, s, s, -s, s, s, s, s,
            -s, -s, s, s, s, s, -s, s, s,
            // Back
            -s, -s, -s, -s, s, -s, s, s, -s,
            -s, -s, -s, s, s, -s, s, -s, -s,
            // Left
            -s, -s, -s, -s, -s, s, -s, s, s,
            -s, -s, -s, -s, s, s, -s, s, -s,
            // Right
            s, -s, -s, s, s, -s, s, s, s,
            s, -s, -s, s, s, s, s, -s, s,
            // Top
            -s, s, -s, -s, s, s, s, s, s,
            -s, s, -s, s, s, s, s, s, -s,
            // Bottom
            -s, -s, -s, s, -s, -s, s, -s, s,
            -s, -s, -s, s, -s, s, -s, -s, s
        )

        private val vb: FloatBuffer =
            ByteBuffer.allocateDirect(cubeVerts.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply {
                put(cubeVerts); position(0)
            }

        init {
            program = createProgram(VS_OBJ, FS_OBJ)
            GLES30.glUseProgram(program)
            uMvpLoc = GLES30.glGetUniformLocation(program, "uMvp")
            uColorLoc = GLES30.glGetUniformLocation(program, "uColor")
            GLES30.glUseProgram(0)
        }

        fun draw(mvp: FloatArray, r: Float, g: Float, b: Float, a: Float) {
            GLES30.glUseProgram(program)
            GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, mvp, 0)
            GLES30.glUniform4f(uColorLoc, r, g, b, a)

            vb.position(0)
            GLES30.glVertexAttribPointer(attribPos, 3, GLES30.GL_FLOAT, false, 3 * 4, vb)
            GLES30.glEnableVertexAttribArray(attribPos)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 36)

            GLES30.glDisableVertexAttribArray(attribPos)
            GLES30.glUseProgram(0)
        }

        companion object {
            private const val VS_OBJ = """#version 300 es
                uniform mat4 uMvp;
                in vec3 aPos;
                void main(){ gl_Position = uMvp * vec4(aPos, 1.0); }"""
            private const val FS_OBJ = """#version 300 es
                precision mediump float;
                uniform vec4 uColor;
                out vec4 fragColor;
                void main(){ fragColor = uColor; }"""

            private fun createShader(type: Int, src: String): Int {
                val sh = GLES30.glCreateShader(type)
                GLES30.glShaderSource(sh, src)
                GLES30.glCompileShader(sh)
                val ok = IntArray(1); GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) {
                    val log = GLES30.glGetShaderInfoLog(sh)
                    GLES30.glDeleteShader(sh); throw RuntimeException("OBJ shader: $log")
                }
                return sh
            }

            private fun createProgram(vsSrc: String, fsSrc: String): Int {
                val vs = createShader(GLES30.GL_VERTEX_SHADER, vsSrc)
                val fs = createShader(GLES30.GL_FRAGMENT_SHADER, fsSrc)
                val prog = GLES30.glCreateProgram()
                GLES30.glAttachShader(prog, vs); GLES30.glAttachShader(prog, fs)
                GLES30.glBindAttribLocation(prog, 0, "aPos")
                GLES30.glLinkProgram(prog)
                val link = IntArray(1); GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, link, 0)
                if (link[0] == 0) {
                    val log = GLES30.glGetProgramInfoLog(prog)
                    GLES30.glDeleteProgram(prog); throw RuntimeException("OBJ link: $log")
                }
                GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
                return prog
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Coordinate data observation / DB hookup

    /** Provide DAO from hosting component so AR view can reflect stored coordinates. */
    fun attachCoordinateDao(dao: CoordinateDao) {
        coordinateDao = dao
        attachObserverIfPossible()
    }

    /** Attach LiveData observer exactly once (after binding + DAO available). */
    private fun attachObserverIfPossible() {
        if (liveObserverAttached) return
        val dao = coordinateDao ?: return
        if (!isAdded || _binding == null) return
        dao.getAllCoordinates().observe(viewLifecycleOwner) { entities ->
            val coords = entities.map { it.toDomainForAr() }
            setCoordinates(coords)
        }
        liveObserverAttached = true
    }

    /** Map DB rows to internal GeoItem list and mark anchors for rebuild. */
    private fun setCoordinates(coords: List<Coordinate>) {
        geoItems.clear()
        coords.forEach { c ->
            val altitude = if (c.altitude == 0.0 || c.altitude.isNaN()) null else c.altitude
            geoItems += GeoItem(c.latitude, c.longitude, altitude, c.name, argbIntToRgba(c.color))
        }
        geoAnchorsCreated = false // Force rebuild next frame when Earth TRACKING
    }

    /** Configure GL surface view and queue taps for processing on GL thread. */
    private fun setupGlSurface() {
        val gl = binding.glSurfaceViewAr
        gl.preserveEGLContextOnPause = true
        gl.setEGLContextClientVersion(3)
        gl.setRenderer(this)
        gl.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        gl.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                queuedTap = PointF(ev.x, ev.y)
                v.performClick(); true
            } else false
        }
    }

    private fun CoordinateEntity.toDomainForAr(): Coordinate = Coordinate(
        id = id,
        name = name,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        timestamp = timestamp,
        icon = icon,
        color = color,
        provider = provider.name,
        rtkStatus = rtkStatus?.name,
        satsUsed = satsUsed,
        hdop = hdop,
        horizontalAccuracyM = horizontalAccuracyM,
        verticalAccuracyM = verticalAccuracyM,
        correctionSource = correctionSource?.name,
        correctionAgeS = correctionAgeS,
        altitudeMsl = altitudeMsl,
        geoidSeparationM = geoidSeparationM,
        crsEpsg = crsEpsg,
        easting = easting,
        northing = northing,
        utmZone = utmZone,
        note = note,
        averagedSamples = averagedSamples,
        averageDurationMs = averageDurationMs,
        stdLatM = stdLatM,
        stdLonM = stdLonM,
        stdAltM = stdAltM,
        sourceDevice = sourceDevice,
        appVersion = appVersion
    )
}