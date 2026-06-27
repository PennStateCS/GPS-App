package app.surrealar.ui.openinar

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders a solid-colour unit cube scaled by the caller's MVP matrix.
 *
 * Used both as a generic 3D object and as a scaled survey-pin marker.
 * Call [draw] with a pre-built MVP and an RGBA colour each frame.
 */
internal class SimpleObjectRenderer {
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

