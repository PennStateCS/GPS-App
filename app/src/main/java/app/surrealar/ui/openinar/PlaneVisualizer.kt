package app.surrealar.ui.openinar

import android.opengl.GLES30
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Debug overlay: draws every detected ARCore plane as a filled, translucent surface with a 0.5 m grid
 * in plane-local coordinates and a thin outline, coloured by type (floor teal, ceiling purple, wall
 * orange — see [PlaneFill.Colors]). Planes fade out with distance and tiny patches are skipped.
 *
 * All maths lives in the GL-free [PlaneFill]; this class only uploads and draws. Planes are drawn far
 * to near with alpha blending and the depth mask off; every GL state it changes is restored, so the
 * point cloud and pins drawn afterwards are unaffected. Buffers are reused and only grow.
 *
 * Call [drawAllPlanes] once per frame on the GL thread after obtaining the camera matrices.
 */
internal class PlaneVisualizer {
    private val program: Int = createProgram(VS, FS)
    private val uMvpLoc: Int
    private val uColorLoc: Int
    private val uFadeLoc: Int
    private val uOutlineLoc: Int
    private val uGridLoc: Int
    private val uAlphaLoc: Int
    private val uCenterLoc: Int
    private val uEdgeLoc: Int

    // Reused per frame; grown only when a bigger plane comes along.
    private var polygon = FloatArray(64)
    private var verts = FloatArray(PlaneFill.fanVertexCount(32) * PlaneFill.FLOATS_PER_VERTEX)
    private var indices = ShortArray(PlaneFill.fanIndexCount(32))
    private var vertexBuffer: FloatBuffer = floatBuffer(verts.size)
    private var indexBuffer: ShortBuffer = shortBuffer(indices.size)

    private class Entry {
        var plane: Plane? = null
        val pose = FloatArray(16)
        var centerDistance = 0f
        var fade = 1f
    }
    private val entries = ArrayList<Entry>()
    private var lastStatsLogMs = 0L
    private val pool = ArrayList<Entry>()

    // Saved GL state.
    private val savedInts = IntArray(8)
    private val savedBool = BooleanArray(1)
    private val savedFloat = FloatArray(1)

    init {
        GLES30.glUseProgram(program)
        uMvpLoc = GLES30.glGetUniformLocation(program, "uMvp")
        uColorLoc = GLES30.glGetUniformLocation(program, "uColor")
        uFadeLoc = GLES30.glGetUniformLocation(program, "uFade")
        uOutlineLoc = GLES30.glGetUniformLocation(program, "uOutline")
        uGridLoc = GLES30.glGetUniformLocation(program, "uGrid")
        uAlphaLoc = GLES30.glGetUniformLocation(program, "uAlpha")
        uCenterLoc = GLES30.glGetUniformLocation(program, "uCenterAlpha")
        uEdgeLoc = GLES30.glGetUniformLocation(program, "uEdgeAlpha")
        GLES30.glUseProgram(0)
    }

    /**
     * Draws all tracked, non-subsumed, large-enough planes within the fade range. [camX]/[camY]/[camZ]
     * is the camera position in world space (for the distance fade and far-to-near order).
     * Returns the number of planes drawn.
     */
    fun drawAllPlanes(session: Session, vp: FloatArray, camX: Float, camY: Float, camZ: Float): Int {
        // ── Collect visible planes with their distance and fade ──
        recycleEntries()
        var floors = 0; var walls = 0; var ceilings = 0; var small = 0; var faded = 0
        var nearestWallM = Float.POSITIVE_INFINITY
        // Raw tallies over ALL planes (any state) to tell "ARCore never made a wall" from "we filtered it".
        var rawVertical = 0; var rawVerticalTracking = 0; var rawSubsumed = 0; var rawNotTracking = 0
        val all = session.getAllTrackables(Plane::class.java)
        for (plane in all) {
            if (plane.type == Plane.Type.VERTICAL) {
                rawVertical++
                if (plane.trackingState == TrackingState.TRACKING) rawVerticalTracking++
            }
            if (plane.subsumedBy != null) rawSubsumed++
            if (plane.trackingState != TrackingState.TRACKING) rawNotTracking++
        }
        for (plane in all) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
            val kind = kindOf(plane)
            when (kind) { PlaneFill.Kind.FLOOR -> floors++; PlaneFill.Kind.WALL -> walls++; PlaneFill.Kind.CEILING -> ceilings++ }
            if (!PlaneFill.isLargeEnough(plane.extentX, plane.extentZ)) { small++; continue }
            val poly = plane.polygon ?: continue
            val n = poly.limit() / 2
            if (n < 3) continue
            val e = obtainEntry()
            plane.centerPose.toMatrix(e.pose, 0)
            readPolygon(poly, n)
            val closest = PlaneFill.closestDistance(polygon, n, e.pose, camX, camY, camZ)
            if (kind == PlaneFill.Kind.WALL && closest < nearestWallM) nearestWallM = closest
            val fade = PlaneFill.distanceFade(closest)
            if (fade <= 0f) { faded++; pool.add(e); continue }
            e.plane = plane
            e.fade = fade
            e.centerDistance = PlaneFill.centerDistance(e.pose, camX, camY, camZ)
            entries.add(e)
        }
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (nowMs - lastStatsLogMs >= STATS_LOG_INTERVAL_MS) {
            lastStatsLogMs = nowMs
            // Counts and distances only — no positions.
            app.surrealar.util.DiagnosticsLogger.i(ArFilamentRenderer.DIAG,
                "AR_PLANES floors=$floors walls=$walls ceilings=$ceilings drawn=${entries.size} " +
                    "hiddenSmall=$small hiddenFar=$faded " +
                    "raw=${all.size} rawVertical=$rawVertical rawVerticalTracking=$rawVerticalTracking " +
                    "rawSubsumed=$rawSubsumed rawNotTracking=$rawNotTracking " +
                    "nearestWallM=${if (nearestWallM.isFinite()) "%.1f".format(nearestWallM) else "n/a"}")
        }
        if (entries.isEmpty()) return 0
        PlaneFill.sortFarToNear(entries, entries.size) { it.centerDistance }

        // ── Draw, restoring GL state afterwards ──
        saveState()
        try {
            GLES30.glBindVertexArray(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glDepthMask(false)
            GLES30.glUseProgram(program)
            GLES30.glUniformMatrix4fv(uMvpLoc, 1, false, vp, 0)
            GLES30.glUniform1f(uGridLoc, PlaneFill.GRID_SPACING_M)
            GLES30.glLineWidth(PlaneFill.OUTLINE_WIDTH_PX)
            GLES30.glEnableVertexAttribArray(A_POS)
            GLES30.glEnableVertexAttribArray(A_LOCAL)
            GLES30.glEnableVertexAttribArray(A_EDGE)

            var drawn = 0
            for (e in entries) {
                val plane = e.plane ?: continue
                val poly = plane.polygon ?: continue
                val n = poly.limit() / 2
                if (n < 3) continue
                readPolygon(poly, n)
                ensureCapacity(n)
                val vCount = PlaneFill.buildFanVertices(polygon, n, e.pose, verts)
                val iCount = PlaneFill.buildFanIndices(n, indices)
                vertexBuffer.clear(); vertexBuffer.put(verts, 0, vCount * PlaneFill.FLOATS_PER_VERTEX); vertexBuffer.position(0)
                indexBuffer.clear(); indexBuffer.put(indices, 0, iCount); indexBuffer.position(0)

                val stride = PlaneFill.FLOATS_PER_VERTEX * 4
                vertexBuffer.position(0)
                GLES30.glVertexAttribPointer(A_POS, 3, GLES30.GL_FLOAT, false, stride, vertexBuffer)
                vertexBuffer.position(3)
                GLES30.glVertexAttribPointer(A_LOCAL, 2, GLES30.GL_FLOAT, false, stride, vertexBuffer)
                vertexBuffer.position(5)
                GLES30.glVertexAttribPointer(A_EDGE, 1, GLES30.GL_FLOAT, false, stride, vertexBuffer)
                vertexBuffer.position(0)

                val kind = kindOf(plane)
                val c = PlaneFill.colorFor(kind)
                GLES30.glUniform3f(uColorLoc, c[0], c[1], c[2])
                GLES30.glUniform1f(uCenterLoc, PlaneFill.centerAlphaFor(kind))
                GLES30.glUniform1f(uEdgeLoc, PlaneFill.edgeAlphaFor(kind))
                GLES30.glUniform1f(uFadeLoc, e.fade)

                // Fill (centre → edge alpha fade + grid).
                GLES30.glUniform1f(uOutlineLoc, 0f)
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, iCount, GLES30.GL_UNSIGNED_SHORT, indexBuffer)
                // Thin outline over the polygon points (vertices 1..n).
                GLES30.glUniform1f(uOutlineLoc, 1f)
                GLES30.glUniform1f(uAlphaLoc, PlaneFill.OUTLINE_ALPHA)
                GLES30.glDrawArrays(GLES30.GL_LINE_LOOP, 1, n)
                drawn++
            }
            return drawn
        } finally {
            GLES30.glDisableVertexAttribArray(A_POS)
            GLES30.glDisableVertexAttribArray(A_LOCAL)
            GLES30.glDisableVertexAttribArray(A_EDGE)
            restoreState()
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private fun readPolygon(poly: FloatBuffer, n: Int) {
        if (polygon.size < n * 2) polygon = FloatArray(n * 2 * 2)
        poly.rewind()
        poly.get(polygon, 0, n * 2)
        poly.rewind()
    }

    private fun ensureCapacity(n: Int) {
        val needV = PlaneFill.fanVertexCount(n) * PlaneFill.FLOATS_PER_VERTEX
        if (verts.size < needV) {
            verts = FloatArray(needV * 2)
            vertexBuffer = floatBuffer(verts.size)
        }
        val needI = PlaneFill.fanIndexCount(n)
        if (indices.size < needI) {
            indices = ShortArray(needI * 2)
            indexBuffer = shortBuffer(indices.size)
        }
    }

    private fun obtainEntry(): Entry = if (pool.isEmpty()) Entry() else pool.removeAt(pool.size - 1)

    private fun recycleEntries() {
        for (e in entries) { e.plane = null; pool.add(e) }
        entries.clear()
    }

    private fun saveState() {
        savedBool[0] = GLES30.glIsEnabled(GLES30.GL_BLEND)
        GLES30.glGetIntegerv(GLES30.GL_BLEND_SRC_RGB, savedInts, 0)
        GLES30.glGetIntegerv(GLES30.GL_BLEND_DST_RGB, savedInts, 1)
        GLES30.glGetIntegerv(GLES30.GL_BLEND_SRC_ALPHA, savedInts, 2)
        GLES30.glGetIntegerv(GLES30.GL_BLEND_DST_ALPHA, savedInts, 3)
        GLES30.glGetIntegerv(GLES30.GL_CURRENT_PROGRAM, savedInts, 4)
        GLES30.glGetIntegerv(GLES30.GL_ARRAY_BUFFER_BINDING, savedInts, 5)
        GLES30.glGetIntegerv(GLES30.GL_VERTEX_ARRAY_BINDING, savedInts, 6)
        GLES30.glGetIntegerv(GLES30.GL_ELEMENT_ARRAY_BUFFER_BINDING, savedInts, 7)
        GLES30.glGetFloatv(GLES30.GL_LINE_WIDTH, savedFloat, 0)
        GLES30.glGetBooleanv(GLES30.GL_DEPTH_WRITEMASK, depthMaskScratch, 0)
    }

    private val depthMaskScratch = BooleanArray(1)

    private fun restoreState() {
        if (savedBool[0]) GLES30.glEnable(GLES30.GL_BLEND) else GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(savedInts[0], savedInts[1], savedInts[2], savedInts[3])
        GLES30.glDepthMask(depthMaskScratch[0])
        GLES30.glUseProgram(savedInts[4])
        GLES30.glBindVertexArray(savedInts[6])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, savedInts[5])
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, savedInts[7])
        GLES30.glLineWidth(savedFloat[0])
    }

    companion object {
        private const val A_POS = 0
        private const val A_LOCAL = 1
        private const val A_EDGE = 2
        /** AR_PLANES summary log interval while the overlay is on. */
        private const val STATS_LOG_INTERVAL_MS = 5_000L

        private fun isCandidate(plane: Plane): Boolean =
            plane.trackingState == TrackingState.TRACKING && plane.subsumedBy == null &&
                PlaneFill.isLargeEnough(plane.extentX, plane.extentZ)

        private fun kindOf(plane: Plane): PlaneFill.Kind = when (plane.type) {
            Plane.Type.HORIZONTAL_DOWNWARD_FACING -> PlaneFill.Kind.CEILING
            Plane.Type.VERTICAL -> PlaneFill.Kind.WALL
            else -> PlaneFill.Kind.FLOOR
        }

        /** Planes the overlay would draw (tracked, not subsumed, not tiny), without drawing them. */
        fun count(session: Session): Int = session.getAllTrackables(Plane::class.java).count(::isCandidate)

        private fun floatBuffer(floats: Int): FloatBuffer =
            ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        private fun shortBuffer(shorts: Int): ShortBuffer =
            ByteBuffer.allocateDirect(shorts * 2).order(ByteOrder.nativeOrder()).asShortBuffer()

        private const val VS = """#version 300 es
            uniform mat4 uMvp;
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec2 aLocal;
            layout(location = 2) in float aEdge;
            out vec2 vLocal;
            out float vEdge;
            void main(){
                vLocal = aLocal;
                vEdge = aEdge;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }"""

        // Fill: alpha fades from CENTER_ALPHA to EDGE_ALPHA; anti-aliased grid lines every uGrid metres in
        // plane-local x/z (fwidth keeps them ~1 px wide at any distance). Outline: flat colour at uAlpha.
        private val FS = """#version 300 es
            precision mediump float;
            uniform vec3 uColor;
            uniform float uFade;
            uniform float uOutline;
            uniform float uGrid;
            uniform float uAlpha;
            uniform float uCenterAlpha;
            uniform float uEdgeAlpha;
            in vec2 vLocal;
            in float vEdge;
            out vec4 fragColor;
            void main(){
                if (uOutline > 0.5) { fragColor = vec4(uColor, uAlpha * uFade); return; }
                float base = mix(uCenterAlpha, uEdgeAlpha, clamp(vEdge, 0.0, 1.0));
                vec2 g = vLocal / uGrid;
                vec2 d = abs(fract(g - 0.5) - 0.5) / max(fwidth(g), vec2(1e-4));
                float line = 1.0 - min(min(d.x, d.y), 1.0);
                // Grid lines pushed toward white so they read on brick, wood or paint alike.
                vec3 rgb = mix(uColor, vec3(1.0), ${PlaneFill.GRID_WHITENESS} * line);
                fragColor = vec4(rgb, min(base + ${PlaneFill.GRID_LINE_ALPHA} * line, 1.0) * uFade);
            }"""

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
