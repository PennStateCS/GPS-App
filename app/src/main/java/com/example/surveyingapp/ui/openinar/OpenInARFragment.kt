package com.example.surveyingapp.ui.openinar

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.surveyingapp.R
import com.example.surveyingapp.databinding.FragmentOpenInArBinding
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
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

    // Camera background renderer only
    private var backgroundRenderer: BackgroundRenderer? = null

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
        setupGlSurface()
        binding.textArStatus.text = getString(R.string.checking_ar_availability)
        return binding.root
    }

    private fun setupGlSurface() {
        val gl = binding.glSurfaceViewAr
        gl.preserveEGLContextOnPause = true
        gl.setEGLContextClientVersion(3) // Upgrade to GLES 3.0

        gl.setRenderer(object : GLSurfaceView.Renderer {
            override fun onSurfaceCreated(glUnused: GL10?, config: EGLConfig?) {
                GLES30.glClearColor(0f, 0f, 0f, 1f)

                // Create camera texture
                cameraTextureId = createCameraTexture()

                // Create background renderer
                backgroundRenderer = BackgroundRenderer()

                // Bind texture to session if it exists
                session?.setCameraTextureName(cameraTextureId)
            }

            override fun onSurfaceChanged(glUnused: GL10?, width: Int, height: Int) {
                GLES30.glViewport(0, 0, width, height)
            }

            override fun onDrawFrame(glUnused: GL10?) {
                // Clear screen first
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

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
            binding.textArStatus.text = String.format(Locale.US, getString(R.string.install_check_failed), e.javaClass.simpleName)
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
            binding.textArStatus.text = String.format(Locale.US, getString(R.string.session_error), e.javaClass.simpleName)
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
            binding.textArStatus.text = getString(R.string.ar_running)
        } catch (e: CameraNotAvailableException) {
            binding.textArStatus.text = getString(R.string.camera_unavailable)
            try { s.pause() } catch (_: Exception) {}
        } catch (e: Exception) {
            binding.textArStatus.text = String.format(Locale.US, getString(R.string.resume_failed), e.javaClass.simpleName)
        }
    }

    // ---- Helpers ----

    private fun createCameraTexture(): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val texId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
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

            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glUseProgram(program)

            quadBuffer.position(0)
            GLES30.glVertexAttribPointer(attribPos, 3, GLES30.GL_FLOAT, false, 5 * 4, quadBuffer)
            GLES30.glEnableVertexAttribArray(attribPos)

            quadBuffer.position(3)
            GLES30.glVertexAttribPointer(attribUv, 2, GLES30.GL_FLOAT, false, 5 * 4, quadBuffer)
            GLES30.glEnableVertexAttribArray(attribUv)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            // Clean up
            GLES30.glDisableVertexAttribArray(attribPos)
            GLES30.glDisableVertexAttribArray(attribUv)
        }

        companion object {
            // Modern GLSL ES 3.0 vertex shader
            private const val VS_BG = """#version 300 es
                in vec3 aPos;
                in vec2 aUv;
                out vec2 vUv;
                void main(){
                  vUv = aUv;
                  gl_Position = vec4(aPos, 1.0);
                }"""

            // Modern GLSL ES 3.0 fragment shader
            private const val FS_BG = """#version 300 es
                #extension GL_OES_EGL_image_external_essl3 : require
                precision mediump float;
                in vec2 vUv;
                uniform samplerExternalOES uTexOes;
                out vec4 fragColor;
                void main(){
                  fragColor = texture(uTexOes, vUv);
                }"""

            private fun createShader(type: Int, src: String): Int {
                val shader = GLES30.glCreateShader(type)
                GLES30.glShaderSource(shader, src)
                GLES30.glCompileShader(shader)
                val ok = IntArray(1)
                GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) {
                    val log = GLES30.glGetShaderInfoLog(shader)
                    GLES30.glDeleteShader(shader)
                    throw RuntimeException("BG shader compile: $log")
                }
                return shader
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
                val texLoc = GLES30.glGetUniformLocation(prog, "uTexOes")
                GLES30.glUseProgram(prog)
                GLES30.glUniform1i(texLoc, 0)
                GLES30.glUseProgram(0)

                // Clean up shaders
                GLES30.glDeleteShader(vs)
                GLES30.glDeleteShader(fs)

                return prog
            }
        }
    }
}
