package com.example.surveyingapp.ui.openinar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PointF
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.surveyingapp.R
import com.example.surveyingapp.databinding.FragmentOpenInArBinding
import com.google.ar.core.*
import com.google.ar.core.Point
import com.google.ar.core.Plane
import com.google.ar.core.exceptions.*
import com.google.ar.core.Coordinates2d
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class OpenInARFragment : Fragment() {

    private var _binding: FragmentOpenInArBinding? = null
    private val binding get() = _binding!!

    private var session: Session? = null
    private var installRequested = false
    private var availabilityPolling = false

    // External camera texture for ARCore
    private var cameraTextureId: Int = -1

    // Renderers
    private var backgroundRenderer: BackgroundRenderer? = null
    private var objectRenderer: SimpleObjectRenderer? = null
    private var planeVisualizer: PlaneVisualizer? = null
    private var pointCloudRenderer: PointCloudRenderer? = null

    // Demo anchor (tap to place; falls back to 1m ahead on first track)
    private var demoAnchor: Anchor? = null

    // Matrices
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)

    // Rotation/viewport helper to update display geometry every frame
    private lateinit var rotationHelper: DisplayRotationHelper

    // Tap queue shared between UI thread and GL thread
    @Volatile private var queuedTap: PointF? = null

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) tryCreateSession()
            else binding.textArStatus.text = getString(R.string.camera_permission_denied)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOpenInArBinding.inflate(inflater, container, false)
        rotationHelper = DisplayRotationHelper(this)
        setupGlSurface()
        binding.textArStatus.text = getString(R.string.checking_ar_availability)
        return binding.root
    }

    private fun setupGlSurface() {
        val gl = binding.glSurfaceViewAr
        gl.preserveEGLContextOnPause = true
        gl.setEGLContextClientVersion(3) // GLES 3.0

        gl.setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(glUnused: GL10?, config: EGLConfig?) {
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glEnable(GLES30.GL_DEPTH_TEST)
                GLES30.glDepthFunc(GLES30.GL_LEQUAL)
                // NOTE: Do NOT enable GL_PROGRAM_POINT_SIZE (not in GLES)

                // Create camera texture
                cameraTextureId = createCameraTexture()

                // Create renderers
                backgroundRenderer = BackgroundRenderer()
                objectRenderer = SimpleObjectRenderer() // small solid cube
                planeVisualizer = PlaneVisualizer()
                pointCloudRenderer = PointCloudRenderer()

                // Bind texture to session if it exists
                session?.setCameraTextureName(cameraTextureId)
            }

            override fun onSurfaceChanged(glUnused: GL10?, width: Int, height: Int) {
                GLES30.glViewport(0, 0, width, height)
                rotationHelper.onSurfaceChanged(width, height)
            }

            override fun onDrawFrame(glUnused: GL10?) {
                // Clear both color and depth
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

                val s = session ?: return
                try {
                    // Ensure ARCore uses correct rotation/viewport for THIS frame
                    rotationHelper.updateSessionIfNeeded(s)

                    // Ensure texture is bound
                    if (cameraTextureId > 0) s.setCameraTextureName(cameraTextureId)

                    // Update AR frame
                    val frame: Frame = s.update()
                    val camera = frame.camera

                    // 1) Draw the camera background
                    backgroundRenderer?.draw(frame, cameraTextureId)

                    // 2) Handle a queued tap to place/move the cube
                    queuedTap?.let { pt ->
                        if (camera.trackingState == TrackingState.TRACKING) {
                            val hits = frame.hitTest(pt.x, pt.y)
                            for (hit in hits) {
                                val trackable = hit.trackable
                                val isPlaneHit = trackable is Plane && trackable.isPoseInPolygon(hit.hitPose)
                                val isPointHit = trackable is Point && trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                                if (isPlaneHit || isPointHit) {
                                    try { demoAnchor?.detach() } catch (_: Exception) {}
                                    demoAnchor = hit.createAnchor()
                                    break
                                }
                            }
                        }
                        queuedTap = null
                    }

                    var planeCount = 0
                    var pointCount = 0

                    // 3) If tracking, draw helpers + cube
                    if (camera.trackingState == TrackingState.TRACKING) {
                        // Common matrices
                        camera.getViewMatrix(view, 0)
                        camera.getProjectionMatrix(proj, 0, 0.1f, 100f)
                        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

                        // a) Point cloud (white dots)
                        pointCount = pointCloudRenderer?.draw(frame, vp) ?: 0

                        // b) Plane boundaries (yellow outlines)
                        planeCount = planeVisualizer?.drawAllPlanes(s, vp) ?: 0

                        // c) Floating cube at anchor
                        demoAnchor?.let { anchor ->
                            if (anchor.trackingState == TrackingState.TRACKING) {
                                anchor.pose.toMatrix(model, 0)
                                Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
                                objectRenderer?.draw(mvp)
                            }
                        }
                    }

                    // 4) Update a tiny status line (post to UI thread)
                    binding.textArStatus.post {
                        binding.textArStatus.text = "AR running • Planes: $planeCount • Points: $pointCount"
                    }

                } catch (_: CameraNotAvailableException) {
                    // Camera temporarily unavailable
                } catch (e: Exception) {
                    android.util.Log.w("OpenInAR", "Frame update error: ${e.message}")
                }
            }
        })

        // Capture taps on the GLSurfaceView (UI thread)
        gl.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                queuedTap = PointF(ev.x, ev.y)
                true
            } else {
                false
            }
        }

        gl.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    override fun onResume() {
        super.onResume()
        if (!checkAndRequestCameraPermission()) return
        if (session == null) tryCreateSession()

        // Recommended ordering: resume session first, then helper, then GL
        try {
            session?.resume()
            rotationHelper.onResume()
            binding.glSurfaceViewAr.onResume()
            binding.textArStatus.text = getString(R.string.ar_running)
        } catch (e: CameraNotAvailableException) {
            binding.textArStatus.text = getString(R.string.camera_unavailable)
            try { session?.pause() } catch (_: Exception) {}
        }
    }

    override fun onPause() {
        super.onPause()
        // Pause GL first to stop rendering thread, then helper, then session
        binding.glSurfaceViewAr.onPause()
        rotationHelper.onPause()
        try { session?.pause() } catch (_: Exception) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try { demoAnchor?.detach() } catch (_: Exception) {}
        demoAnchor = null
        try { session?.close() } catch (_: Exception) {}
        session = null
        _binding = null
    }

    private fun checkAndRequestCameraPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) true else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            false
        }
    }

    private fun tryCreateSession() {
        if (session != null) return

        val availability = ArCoreApk.getInstance().checkAvailability(requireContext())
        if (availability.isTransient) {
            if (!availabilityPolling) {
                availabilityPolling = true
                binding.textArStatus.postDelayed({
                    availabilityPolling = false
                    tryCreateSession()
                }, 300)
            }
            return
        }
        if (!availability.isSupported) {
            binding.textArStatus.text = getString(R.string.ar_not_supported)
            return
        }

        // Ensure Google Play Services for AR is present
        try {
            when (ArCoreApk.getInstance().requestInstall(requireActivity(), !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    binding.textArStatus.text = getString(R.string.requesting_arcore_install)
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }
        } catch (e: UnavailableUserDeclinedInstallationException) {
            binding.textArStatus.text = getString(R.string.arcore_install_declined)
            return
        } catch (e: Exception) {
            binding.textArStatus.text = String.format(
                Locale.US,
                getString(R.string.install_check_failed),
                e.javaClass.simpleName
            )
            return
        }

        // Create and configure the AR session
        try {
            val s = Session(requireContext())
            val cfg = Config(s).apply {
                lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            }
            s.configure(cfg)

            // Bind camera texture if GL is ready
            if (cameraTextureId > 0) s.setCameraTextureName(cameraTextureId)

            session = s
            binding.textArStatus.text = getString(R.string.ar_session_created)
        } catch (e: UnavailableArcoreNotInstalledException) {
            binding.textArStatus.text = getString(R.string.arcore_not_installed)
        } catch (e: UnavailableApkTooOldException) {
            binding.textArStatus.text = getString(R.string.arcore_update_required)
        } catch (e: UnavailableSdkTooOldException) {
            binding.textArStatus.text = getString(R.string.app_update_required)
        } catch (e: UnavailableDeviceNotCompatibleException) {
            binding.textArStatus.text = getString(R.string.device_not_compatible)
        } catch (e: SecurityException) {
            binding.textArStatus.text = getString(R.string.camera_permission_needed)
        } catch (e: Exception) {
            binding.textArStatus.text = String.format(
                Locale.US,
                getString(R.string.session_error),
                e.javaClass.simpleName
            )
        }
    }

    // ---- Helpers ----

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

    // --- Background renderer using ARCore's UV transform so the feed matches device orientation ---
    private class BackgroundRenderer {
        // Fullscreen quad in NDC (triangle strip order)
        private val ndcQuad = floatArrayOf(
            -1f, -1f,   // bottom-left
            1f, -1f,   // bottom-right
            -1f,  1f,   // top-left
            1f,  1f    // top-right
        )

        // Positions (x,y,z) to draw the fullscreen quad
        private val quadPos = floatArrayOf(
            -1f, -1f, 0f,
            1f, -1f, 0f,
            -1f,  1f, 0f,
            1f,  1f, 0f
        )

        private val posBuffer: FloatBuffer = ByteBuffer.allocateDirect(quadPos.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(quadPos)
                position(0)
            }

        private val uvData = FloatArray(8) // 4 verts * (u,v)
        private val uvBuffer: FloatBuffer = ByteBuffer.allocateDirect(uvData.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

        private val program: Int
        private val attribPos = 0
        private val attribUv  = 1
        private var haveValidUvs = false

        init {
            program = createProgram(VS_BG, FS_BG)
        }

        fun draw(frame: Frame, oesTexId: Int) {
            if (oesTexId <= 0) return

            // Recompute UVs if display geometry changed or on first frame.
            if (frame.hasDisplayGeometryChanged() || !haveValidUvs) {
                frame.transformCoordinates2d(
                    Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                    ndcQuad,                          // input: 4 (x,y) pairs in NDC
                    Coordinates2d.TEXTURE_NORMALIZED,
                    uvData                             // output: 4 (u,v) pairs in [0,1]
                )
                uvBuffer.clear()
                uvBuffer.put(uvData)
                uvBuffer.rewind()
                haveValidUvs = true
            }

            // Draw background with depth test disabled
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glUseProgram(program)

            // Positions (xyz)
            posBuffer.position(0)
            GLES30.glVertexAttribPointer(attribPos, 3, GLES30.GL_FLOAT, false, 3 * 4, posBuffer)
            GLES30.glEnableVertexAttribArray(attribPos)

            // UVs
            uvBuffer.position(0)
            GLES30.glVertexAttribPointer(attribUv, 2, GLES30.GL_FLOAT, false, 0, uvBuffer)
            GLES30.glEnableVertexAttribArray(attribUv)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            // Clean up
            GLES30.glDisableVertexAttribArray(attribPos)
            GLES30.glDisableVertexAttribArray(attribUv)
            GLES30.glUseProgram(0)

            // Re-enable depth testing for 3D content
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        }

        companion object {
            private const val VS_BG = """#version 300 es
                in vec3 aPos;
                in vec2 aUv;
                out vec2 vUv;
                void main() {
                  vUv = aUv;
                  gl_Position = vec4(aPos, 1.0);
                }"""

            private const val FS_BG = """#version 300 es
                #extension GL_OES_EGL_image_external_essl3 : require
                precision mediump float;
                in vec2 vUv;
                uniform samplerExternalOES uTexOes;
                out vec4 fragColor;
                void main() {
                  fragColor = texture(uTexOes, vUv);
                }"""

            private fun createShader(type: Int, src: String): Int {
                val sh = GLES30.glCreateShader(type)
                GLES30.glShaderSource(sh, src)
                GLES30.glCompileShader(sh)
                val ok = IntArray(1)
                GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) {
                    val log = GLES30.glGetShaderInfoLog(sh)
                    GLES30.glDeleteShader(sh)
                    throw RuntimeException("BG shader compile: $log")
                }
                return sh
            }

            private fun createProgram(vsSrc: String, fsSrc: String): Int {
                val vs = createShader(GLES30.GL_VERTEX_SHADER, vsSrc)
                val fs = createShader(GLES30.GL_FRAGMENT_SHADER, fsSrc)
                val prog = GLES30.glCreateProgram()
                GLES30.glAttachShader(prog, vs)
                GLES30.glAttachShader(prog, fs)
                GLES30.glBindAttribLocation(prog, 0, "aPos")
                GLES30.glBindAttribLocation(prog, 1, "aUv")
                GLES30.glLinkProgram(prog)
                val link = IntArray(1)
                GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, link, 0)
                if (link[0] == 0) {
                    val log = GLES30.glGetProgramInfoLog(prog)
                    GLES30.glDeleteProgram(prog)
                    throw RuntimeException("BG program link: $log")
                }
                // Bind sampler unit 0
                GLES30.glUseProgram(prog)
                val texLoc = GLES30.glGetUniformLocation(prog, "uTexOes")
                GLES30.glUniform1i(texLoc, 0)
                GLES30.glUseProgram(0)

                GLES30.glDeleteShader(vs)
                GLES30.glDeleteShader(fs)
                return prog
            }
        }
    }

    // --- Plane boundary visualizer (yellow GL_LINE_LOOP outlines) ---
    private class PlaneVisualizer {
        private val program: Int
        private val attribPos = 0
        private val uMvpLoc: Int
        private val uColorLoc: Int

        // dynamic buffer reused per plane
        private var lineBuffer: FloatBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer()

        init {
            program = createProgram(VS, FS)
            GLES30.glUseProgram(program)
            uMvpLoc = GLES30.glGetUniformLocation(program, "uMvp")
            uColorLoc = GLES30.glGetUniformLocation(program, "uColor")
            GLES30.glUseProgram(0)
        }

        /**
         * Draws outlines for all TRACKING planes. Returns count of planes drawn.
         */
        fun drawAllPlanes(session: Session, vp: FloatArray): Int {
            var drawn = 0
            val planes = session.getAllTrackables(Plane::class.java)
            GLES30.glUseProgram(program)
            GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, vp, 0)
            GLES30.glUniform4f(uColorLoc, 1.0f, 0.9f, 0.0f, 1.0f) // yellow

            // Many devices clamp line width to 1; set a hint anyway
            GLES30.glLineWidth(3f)

            for (plane in planes) {
                if (plane.trackingState != TrackingState.TRACKING) continue
                if (plane.subsumedBy != null) continue
                val poly: FloatBuffer? = plane.polygon
                if (poly == null || poly.limit() < 6) continue // need at least 3 vertices

                // polygon is X,Z pairs in plane local coords
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

                // Ensure buffer capacity
                val neededBytes = worldVerts.size * 4
                if (lineBuffer.capacity() * 4 < neededBytes) {
                    lineBuffer = ByteBuffer.allocateDirect(neededBytes).order(ByteOrder.nativeOrder()).asFloatBuffer()
                }
                lineBuffer.clear()
                lineBuffer.put(worldVerts)
                lineBuffer.rewind()

                // Draw as line loop
                lineBuffer.position(0)
                GLES30.glVertexAttribPointer(attribPos, 3, GLES30.GL_FLOAT, false, 3 * 4, lineBuffer)
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
                void main(){
                  gl_Position = uMvp * vec4(aPos, 1.0);
                }"""
            private const val FS = """#version 300 es
                precision mediump float;
                uniform vec4 uColor;
                out vec4 fragColor;
                void main(){
                  fragColor = uColor;
                }"""

            private fun createShader(type: Int, src: String): Int {
                val sh = GLES30.glCreateShader(type)
                GLES30.glShaderSource(sh, src)
                GLES30.glCompileShader(sh)
                val ok = IntArray(1)
                GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) {
                    val log = GLES30.glGetShaderInfoLog(sh)
                    GLES30.glDeleteShader(sh)
                    throw RuntimeException("Plane shader compile: $log")
                }
                return sh
            }
            private fun createProgram(vsSrc: String, fsSrc: String): Int {
                val vs = createShader(GLES30.GL_VERTEX_SHADER, vsSrc)
                val fs = createShader(GLES30.GL_FRAGMENT_SHADER, fsSrc)
                val prog = GLES30.glCreateProgram()
                GLES30.glAttachShader(prog, vs)
                GLES30.glAttachShader(prog, fs)
                GLES30.glBindAttribLocation(prog, 0, "aPos")
                GLES30.glLinkProgram(prog)
                val link = IntArray(1)
                GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, link, 0)
                if (link[0] == 0) {
                    val log = GLES30.glGetProgramInfoLog(prog)
                    GLES30.glDeleteProgram(prog)
                    throw RuntimeException("Plane program link: $log")
                }
                GLES30.glDeleteShader(vs)
                GLES30.glDeleteShader(fs)
                return prog
            }
        }
    }

    // --- Point cloud visualizer (white GL_POINTS) ---
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

        /**
         * Draws the current point cloud. Returns the number of points drawn.
         */
        fun draw(frame: Frame, vp: FloatArray): Int {
            val pointCloud = frame.acquirePointCloud()
            val pts = pointCloud.points // FloatBuffer of (x,y,z,confidence) repeated
            val totalFloats = pts.limit()
            val count = totalFloats / 4
            if (count <= 0) {
                pointCloud.release()
                return 0
            }

            // Pack only xyz into a contiguous float array
            val xyz = FloatArray(count * 3)
            pts.rewind()
            var j = 0
            while (pts.hasRemaining()) {
                xyz[j++] = pts.get() // x
                xyz[j++] = pts.get() // y
                xyz[j++] = pts.get() // z
                if (pts.hasRemaining()) pts.get() // skip confidence
            }

            // Ensure buffer capacity
            val neededBytes = xyz.size * 4
            if (pointBuffer.capacity() * 4 < neededBytes) {
                pointBuffer = ByteBuffer.allocateDirect(neededBytes).order(ByteOrder.nativeOrder()).asFloatBuffer()
            }
            pointBuffer.clear()
            pointBuffer.put(xyz)
            pointBuffer.rewind()

            // Clamp point size to device range
            val range = FloatArray(2)
            GLES30.glGetFloatv(GLES30.GL_ALIASED_POINT_SIZE_RANGE, range, 0) // [min, max]
            val desired = 6f
            val size = if (range[1] > 0f) desired.coerceIn(range[0], range[1]) else desired

            // Draw
            GLES30.glUseProgram(program)
            GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, vp, 0)
            GLES30.glUniform4f(uColorLoc, 1f, 1f, 1f, 1f) // white
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
                void main(){
                  gl_Position = uMvp * vec4(aPos, 1.0);
                  gl_PointSize = uPointSize;
                }"""
            private const val FS = """#version 300 es
                precision mediump float;
                uniform vec4 uColor;
                out vec4 fragColor;
                void main(){
                  fragColor = uColor;
                }"""

            private fun createShader(type: Int, src: String): Int {
                val sh = GLES30.glCreateShader(type)
                GLES30.glShaderSource(sh, src)
                GLES30.glCompileShader(sh)
                val ok = IntArray(1)
                GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) {
                    val log = GLES30.glGetShaderInfoLog(sh)
                    GLES30.glDeleteShader(sh)
                    throw RuntimeException("PC shader compile: $log")
                }
                return sh
            }
            private fun createProgram(vsSrc: String, fsSrc: String): Int {
                val vs = createShader(GLES30.GL_VERTEX_SHADER, vsSrc)
                val fs = createShader(GLES30.GL_FRAGMENT_SHADER, fsSrc)
                val prog = GLES30.glCreateProgram()
                GLES30.glAttachShader(prog, vs)
                GLES30.glAttachShader(prog, fs)
                GLES30.glBindAttribLocation(prog, 0, "aPos")
                GLES30.glLinkProgram(prog)
                val link = IntArray(1)
                GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, link, 0)
                if (link[0] == 0) {
                    val log = GLES30.glGetProgramInfoLog(prog)
                    GLES30.glDeleteProgram(prog)
                    throw RuntimeException("PC program link: $log")
                }
                GLES30.glDeleteShader(vs)
                GLES30.glDeleteShader(fs)
                return prog
            }
        }
    }

    // --- Simple solid-color cube renderer (10 cm cube) ---
    private class SimpleObjectRenderer {
        private val program: Int
        private val attribPos = 0
        private val uMvpLoc: Int
        private val uColorLoc: Int

        // 10 cm cube centered at origin
        private val s = 0.05f
        private val cubeVerts = floatArrayOf(
            // Front (+Z)
            -s,-s, s,   s,-s, s,   s, s, s,
            -s,-s, s,   s, s, s,  -s, s, s,
            // Back (-Z)
            -s,-s,-s,  -s, s,-s,   s, s,-s,
            -s,-s,-s,   s, s,-s,   s,-s,-s,
            // Left (-X)
            -s,-s,-s,  -s,-s, s,  -s, s, s,
            -s,-s,-s,  -s, s, s,  -s, s,-s,
            // Right (+X)
            s,-s,-s,   s, s,-s,   s, s, s,
            s,-s,-s,   s, s, s,   s,-s, s,
            // Top (+Y)
            -s, s,-s,  -s, s, s,   s, s, s,
            -s, s,-s,   s, s, s,   s, s,-s,
            // Bottom (-Y)
            -s,-s,-s,   s,-s,-s,   s,-s, s,
            -s,-s,-s,   s,-s, s,  -s,-s, s
        )

        private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(cubeVerts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(cubeVerts)
                position(0)
            }

        init {
            program = createProgram(VS_OBJ, FS_OBJ)
            GLES30.glUseProgram(program)
            uMvpLoc = GLES30.glGetUniformLocation(program, "uMvp")
            uColorLoc = GLES30.glGetUniformLocation(program, "uColor")
            GLES30.glUseProgram(0)
        }

        fun draw(mvp: FloatArray) {
            GLES30.glUseProgram(program)

            // Enable depth test for 3D object
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)

            // Upload uniforms
            GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, mvp, 0)
            // Soft purple-ish color (opaque)
            GLES30.glUniform4f(uColorLoc, 0.6f, 0.4f, 0.9f, 1.0f)

            // Positions
            vertexBuffer.position(0)
            GLES30.glVertexAttribPointer(attribPos, 3, GLES30.GL_FLOAT, false, 3 * 4, vertexBuffer)
            GLES30.glEnableVertexAttribArray(attribPos)

            // Draw 12 triangles (36 verts)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 36)

            GLES30.glDisableVertexAttribArray(attribPos)
            GLES30.glUseProgram(0)
        }

        companion object {
            private const val VS_OBJ = """#version 300 es
                uniform mat4 uMvp;
                in vec3 aPos;
                void main(){
                  gl_Position = uMvp * vec4(aPos, 1.0);
                }"""

            private const val FS_OBJ = """#version 300 es
                precision mediump float;
                uniform vec4 uColor;
                out vec4 fragColor;
                void main(){
                  fragColor = uColor;
                }"""

            private fun createShader(type: Int, src: String): Int {
                val sh = GLES30.glCreateShader(type)
                GLES30.glShaderSource(sh, src)
                GLES30.glCompileShader(sh)
                val ok = IntArray(1)
                GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) {
                    val log = GLES30.glGetShaderInfoLog(sh)
                    GLES30.glDeleteShader(sh)
                    throw RuntimeException("OBJ shader compile: $log")
                }
                return sh
            }

            private fun createProgram(vsSrc: String, fsSrc: String): Int {
                val vs = createShader(GLES30.GL_VERTEX_SHADER, vsSrc)
                val fs = createShader(GLES30.GL_FRAGMENT_SHADER, fsSrc)
                val prog = GLES30.glCreateProgram()
                GLES30.glAttachShader(prog, vs)
                GLES30.glAttachShader(prog, fs)
                GLES30.glBindAttribLocation(prog, 0, "aPos")
                GLES30.glLinkProgram(prog)
                val link = IntArray(1)
                GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, link, 0)
                if (link[0] == 0) {
                    val log = GLES30.glGetProgramInfoLog(prog)
                    GLES30.glDeleteProgram(prog)
                    throw RuntimeException("OBJ program link: $log")
                }
                GLES30.glDeleteShader(vs)
                GLES30.glDeleteShader(fs)
                return prog
            }
        }
    }

    // --- Helper to keep ARCore's display geometry in sync every frame ---
    private class DisplayRotationHelper(private val fragment: Fragment) {
        private var viewportWidth = 0
        private var viewportHeight = 0
        private var isActive = false

        fun onResume() { isActive = true }
        fun onPause() { isActive = false }

        fun onSurfaceChanged(width: Int, height: Int) {
            viewportWidth = width
            viewportHeight = height
        }

        fun updateSessionIfNeeded(session: Session) {
            if (!isActive || viewportWidth == 0 || viewportHeight == 0) return
            val rotation = getDisplayRotation(fragment)
            session.setDisplayGeometry(rotation, viewportWidth, viewportHeight)
        }

        private fun getDisplayRotation(fragment: Fragment): Int {
            val activity = fragment.requireActivity()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.display?.rotation ?: Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay.rotation
            }
        }
    }
}
