package com.example.surveyingapp.ui.openinar

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders the ARCore camera feed as a full-screen background quad.
 *
 * Owns the OES external texture used for the camera feed. Call [textureId] after
 * construction to pass the ID to [Session.setCameraTextureName].
 *
 * Uses ARCore's [Frame.transformCoordinates2d] to correctly orient the camera
 * texture UVs for any device rotation, then draws with an OES external texture
 * sampler so no pixel copies are needed.
 */
internal class BackgroundRenderer {
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

    /**
     * The OpenGL OES texture ID used for the camera feed.
     * Created in [init] on the GL thread; pass to [Session.setCameraTextureName] before use.
     */
    val textureId: Int

    init {
        // Create the OES camera texture.
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        program = createProgram(VS_BG, FS_BG)
    }

    fun draw(frame: Frame) {
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
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

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
