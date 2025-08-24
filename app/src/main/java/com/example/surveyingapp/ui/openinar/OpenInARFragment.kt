package com.example.surveyingapp.ui.openinar

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.surveyingapp.databinding.FragmentOpenInArBinding
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
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

    // Camera background renderer only
    private var backgroundRenderer: BackgroundRenderer? = null

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) tryCreateSession()
            else binding.textArStatus.text = "Camera permission denied"
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOpenInArBinding.inflate(inflater, container, false)
        setupGlSurface()
        binding.textArStatus.text = "Checking AR availability…"
        return binding.root
    }

    private fun setupGlSurface() {
        val gl = binding.glSurfaceViewAr
        gl.preserveEGLContextOnPause = true
        gl.setEGLContextClientVersion(2) // Use GLES 2.0 for better compatibility

        gl.setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(glUnused: GL10?, config: EGLConfig?) {
                GLES20.glClearColor(0f, 0f, 0f, 1f)

                // Create camera texture
                cameraTextureId = createCameraTexture()

                // Create background renderer
                backgroundRenderer = BackgroundRenderer()

                // Bind texture to session if it exists
                session?.setCameraTextureName(cameraTextureId)
            }

            override fun onSurfaceChanged(glUnused: GL10?, width: Int, height: Int) {
                GLES20.glViewport(0, 0, width, height)
            }

            override fun onDrawFrame(glUnused: GL10?) {
                // Clear screen first
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                val s = session ?: return
                try {
                    // Ensure texture is bound
                    if (cameraTextureId > 0) {
                        s.setCameraTextureName(cameraTextureId)
                    }

                    // Update AR frame
                    val frame: Frame = s.update()

                    // Draw camera background
                    backgroundRenderer?.draw(frame, cameraTextureId)

                } catch (_: CameraNotAvailableException) {
                    // Camera temporarily unavailable
                } catch (e: Exception) {
                    // Log other issues but keep rendering
                    android.util.Log.w("OpenInAR", "Frame update error: ${e.message}")
                }
            }
        })

        gl.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    override fun onResume() {
        super.onResume()
        if (!checkAndRequestCameraPermission()) return
        if (session == null) tryCreateSession()
        resumeSession()
    }

    override fun onPause() {
        super.onPause()
        // Pause GL before session to avoid driver races
        binding.glSurfaceViewAr.onPause()
        try { session?.pause() } catch (_: Exception) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
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
            binding.textArStatus.text = "AR not supported on this device"
            return
        }

        // Ensure Google Play Services for AR is present
        try {
            when (ArCoreApk.getInstance().requestInstall(requireActivity(), !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    binding.textArStatus.text = "Requesting ARCore install…"
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }
        } catch (e: UnavailableUserDeclinedInstallationException) {
            binding.textArStatus.text = "ARCore install declined"
            return
        } catch (e: Exception) {
            binding.textArStatus.text = "Install check failed: ${e.javaClass.simpleName}"
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
            binding.textArStatus.text = "AR session created"
        } catch (e: UnavailableArcoreNotInstalledException) {
            binding.textArStatus.text = "ARCore not installed"
        } catch (e: UnavailableApkTooOldException) {
            binding.textArStatus.text = "ARCore update required"
        } catch (e: UnavailableSdkTooOldException) {
            binding.textArStatus.text = "App update required"
        } catch (e: UnavailableDeviceNotCompatibleException) {
            binding.textArStatus.text = "Device not compatible"
        } catch (e: SecurityException) {
            binding.textArStatus.text = "Camera permission needed"
        } catch (e: Exception) {
            binding.textArStatus.text = "Session error: ${e.javaClass.simpleName}"
        }
    }

    private fun resumeSession() {
        val s = session ?: return
        try {
            // Resume GL first
            binding.glSurfaceViewAr.onResume()

            // Ensure ARCore knows the camera texture BEFORE resume (prevents blank first frames)
            if (cameraTextureId > 0) s.setCameraTextureName(cameraTextureId)

            s.resume()
            binding.textArStatus.text = "AR running"
        } catch (e: CameraNotAvailableException) {
            binding.textArStatus.text = "Camera unavailable"
            try { s.pause() } catch (_: Exception) {}
        } catch (e: Exception) {
            binding.textArStatus.text = "Resume failed: ${e.javaClass.simpleName}"
        }
    }

    // ---- Helpers ----

    private fun createCameraTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val texId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        return texId
    }

    // --- Background renderer with proper ARCore UV transform for rotation ---
    private class BackgroundRenderer {
        private val quadCoords = floatArrayOf(
            -1f, -1f, 0f, 0f, 1f,
             1f, -1f, 0f, 1f, 1f,
            -1f,  1f, 0f, 0f, 0f,
             1f,  1f, 0f, 1f, 0f,
        )

        private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(quadCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(quadCoords)
                position(0)
            }

        // For ARCore UV transformation
        private val uvCoords = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )
        private val transformedUvCoords = FloatArray(8)
        private val uvBuffer: FloatBuffer = ByteBuffer.allocateDirect(transformedUvCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val program: Int
        private val attribPos = 0
        private val attribUv = 1

        init {
            program = createProgram(VS_BG, FS_BG)
        }

        /** Call once per frame after `session.update(frame)` */
        fun draw(frame: Frame, cameraTexId: Int) {
            if (cameraTexId <= 0) return

            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glUseProgram(program)

            quadBuffer.position(0)
            GLES20.glVertexAttribPointer(attribPos, 3, GLES20.GL_FLOAT, false, 5 * 4, quadBuffer)
            GLES20.glEnableVertexAttribArray(attribPos)

            quadBuffer.position(3)
            GLES20.glVertexAttribPointer(attribUv, 2, GLES20.GL_FLOAT, false, 5 * 4, quadBuffer)
            GLES20.glEnableVertexAttribArray(attribUv)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // Clean up
            GLES20.glDisableVertexAttribArray(attribPos)
            GLES20.glDisableVertexAttribArray(attribUv)
        }

        companion object {
            private const val VS_BG = """
                attribute vec3 aPos;
                attribute vec2 aUv;
                varying vec2 vUv;
                void main(){
                  vUv = aUv;
                  gl_Position = vec4(aPos,1.0);
                }"""

            private const val FS_BG = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vUv;
                uniform samplerExternalOES uTexOes;
                void main(){
                  gl_FragColor = texture2D(uTexOes, vUv);
                }"""

            private fun createShader(type: Int, src: String): Int {
                val shader = GLES20.glCreateShader(type)
                GLES20.glShaderSource(shader, src)
                GLES20.glCompileShader(shader)
                val ok = IntArray(1)
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) {
                    val log = GLES20.glGetShaderInfoLog(shader)
                    GLES20.glDeleteShader(shader)
                    throw RuntimeException("BG shader compile: $log")
                }
                return shader
            }

            private fun createProgram(vsSrc: String, fsSrc: String): Int {
                val vs = createShader(GLES20.GL_VERTEX_SHADER, vsSrc)
                val fs = createShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
                val prog = GLES20.glCreateProgram()
                GLES20.glAttachShader(prog, vs)
                GLES20.glAttachShader(prog, fs)
                GLES20.glBindAttribLocation(prog, 0, "aPos")
                GLES20.glBindAttribLocation(prog, 1, "aUv")
                GLES20.glLinkProgram(prog)
                val link = IntArray(1)
                GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, link, 0)
                if (link[0] == 0) {
                    val log = GLES20.glGetProgramInfoLog(prog)
                    GLES20.glDeleteProgram(prog)
                    throw RuntimeException("BG program link: $log")
                }
                // Bind sampler unit 0
                val texLoc = GLES20.glGetUniformLocation(prog, "uTexOes")
                GLES20.glUseProgram(prog)
                GLES20.glUniform1i(texLoc, 0)
                GLES20.glUseProgram(0)
                return prog
            }
        }
    }
}
