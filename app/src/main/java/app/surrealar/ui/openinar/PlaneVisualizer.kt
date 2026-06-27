package app.surrealar.ui.openinar

import android.opengl.GLES30
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the boundary polygon of every detected ARCore plane as a yellow `GL_LINE_LOOP`.
 *
 * Call [drawAllPlanes] once per frame after obtaining matrices from the camera.
 * Returns the number of planes actually drawn.
 */
internal class PlaneVisualizer {
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

