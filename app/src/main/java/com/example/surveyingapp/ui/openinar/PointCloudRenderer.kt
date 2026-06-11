package com.example.surveyingapp.ui.openinar

import android.opengl.GLES30
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders the ARCore point cloud as white [GL_POINTS].
 *
 * Call [draw] once per frame; returns the number of points rendered.
 * Automatically adjusts point size to the device's [GL_ALIASED_POINT_SIZE_RANGE].
 */
internal class PointCloudRenderer {
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

