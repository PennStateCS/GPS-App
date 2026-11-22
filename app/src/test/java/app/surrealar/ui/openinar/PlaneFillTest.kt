package app.surrealar.ui.openinar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaneFillTest {

    /** Column-major 4×4: identity rotation, translation (tx, ty, tz). */
    private fun translation(tx: Float, ty: Float, tz: Float) = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        tx, ty, tz, 1f,
    )

    /** Column-major: 90° about X (plane-local z → world −y… i.e. a wall facing +y→z), translated. */
    private fun rotX90(tx: Float, ty: Float, tz: Float) = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, -1f, 0f, 0f,
        tx, ty, tz, 1f,
    )

    /** 2 m × 2 m square around the plane origin. */
    private val square = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f)

    private fun vertex(out: FloatArray, i: Int) = out.copyOfRange(i * 6, i * 6 + 6)

    @Test fun `fan vertex and index counts`() {
        assertEquals(5, PlaneFill.fanVertexCount(4))
        assertEquals(12, PlaneFill.fanIndexCount(4))
        assertEquals(0, PlaneFill.fanVertexCount(2))
        assertEquals(0, PlaneFill.fanIndexCount(2))
        val out = FloatArray(5 * PlaneFill.FLOATS_PER_VERTEX)
        assertEquals(5, PlaneFill.buildFanVertices(square, 4, translation(0f, 0f, 0f), out))
        val idx = ShortArray(12)
        assertEquals(12, PlaneFill.buildFanIndices(4, idx))
        assertArrayEquals(shortArrayOf(0, 1, 2, 0, 2, 3, 0, 3, 4, 0, 4, 1), idx)
        assertEquals(0, PlaneFill.buildFanVertices(floatArrayOf(0f, 0f, 1f, 1f), 2, translation(0f, 0f, 0f), out))
    }

    @Test fun `centre and edge factors, local coordinates and world transform`() {
        val out = FloatArray(5 * PlaneFill.FLOATS_PER_VERTEX)
        PlaneFill.buildFanVertices(square, 4, translation(10f, 2f, -3f), out)
        // Centre: centroid (0,0) local, edge 0, world = translation.
        assertArrayEquals(floatArrayOf(10f, 2f, -3f, 0f, 0f, 0f), vertex(out, 0), 1e-6f)
        // First polygon point (-1,-1) local, edge 1.
        assertArrayEquals(floatArrayOf(9f, 2f, -4f, -1f, -1f, 1f), vertex(out, 1), 1e-6f)
        for (i in 1..4) assertEquals(1f, vertex(out, i)[5], 0f)

        // Rotated pose: local z maps through the pose's third column.
        PlaneFill.buildFanVertices(square, 4, rotX90(0f, 0f, 0f), out)
        val v = vertex(out, 3)    // local (1, 1)
        assertArrayEquals(floatArrayOf(1f, -1f, 0f, 1f, 1f, 1f), v, 1e-6f)
    }

    @Test fun `colour by plane type`() {
        assertArrayEquals(PlaneFill.Colors.FLOOR, PlaneFill.colorFor(PlaneFill.Kind.FLOOR), 0f)
        assertArrayEquals(PlaneFill.Colors.CEILING, PlaneFill.colorFor(PlaneFill.Kind.CEILING), 0f)
        assertArrayEquals(PlaneFill.Colors.WALL, PlaneFill.colorFor(PlaneFill.Kind.WALL), 0f)
        // Teal / purple / orange: dominant channels.
        PlaneFill.Colors.FLOOR.let { assertTrue(it[1] > it[0] && it[2] > it[0]) }
        PlaneFill.Colors.CEILING.let { assertTrue(it[2] > it[1] && it[0] > it[1]) }
        PlaneFill.Colors.WALL.let { assertTrue(it[0] > it[1] && it[1] > it[2]) }
    }

    @Test fun `walls are filled more strongly than floors and ceilings, and fade less to the edge`() {
        assertTrue(PlaneFill.centerAlphaFor(PlaneFill.Kind.WALL) > PlaneFill.centerAlphaFor(PlaneFill.Kind.FLOOR))
        assertTrue(PlaneFill.edgeAlphaFor(PlaneFill.Kind.WALL) > PlaneFill.edgeAlphaFor(PlaneFill.Kind.FLOOR))
        assertEquals(PlaneFill.centerAlphaFor(PlaneFill.Kind.FLOOR), PlaneFill.centerAlphaFor(PlaneFill.Kind.CEILING), 0f)
        for (k in PlaneFill.Kind.values()) assertTrue(PlaneFill.edgeAlphaFor(k) < PlaneFill.centerAlphaFor(k))
    }

    @Test fun `distance fade at 0, 8, 11_5 and 15+ m`() {
        assertEquals(1f, PlaneFill.distanceFade(0f), 0f)
        assertEquals(1f, PlaneFill.distanceFade(8f), 0f)
        assertEquals(0.5f, PlaneFill.distanceFade(11.5f), 1e-6f)
        assertEquals(0f, PlaneFill.distanceFade(15f), 0f)
        assertEquals(0f, PlaneFill.distanceFade(40f), 0f)
    }

    @Test fun `area cut-off`() {
        assertFalse(PlaneFill.isLargeEnough(0.3f, 0.4f))     // 0.12 m²
        assertTrue(PlaneFill.isLargeEnough(0.5f, 0.3f))      // 0.15 m² exactly
        assertTrue(PlaneFill.isLargeEnough(2f, 3f))
    }

    @Test fun `sort far to near, stable`() {
        data class P(val name: String, val d: Float)
        val list = mutableListOf(P("a", 2f), P("b", 9f), P("c", 5f), P("d", 9f))
        PlaneFill.sortFarToNear(list, list.size) { it.d }
        assertEquals(listOf("b", "d", "c", "a"), list.map { it.name })
        // Only the first `count` entries are sorted.
        val partial = mutableListOf(P("x", 1f), P("y", 3f), P("z", 9f))
        PlaneFill.sortFarToNear(partial, 2) { it.d }
        assertEquals(listOf("y", "x", "z"), partial.map { it.name })
    }

    @Test fun `closest distance uses the polygon area, not just the corners`() {
        val pose = translation(0f, 0f, 0f)
        // Standing 1.5 m above the middle of a big floor: distance is the height, not ~corner distance.
        val big = floatArrayOf(-20f, -20f, 20f, -20f, 20f, 20f, -20f, 20f)
        assertEquals(1.5f, PlaneFill.closestDistance(big, 4, pose, 0f, 1.5f, 0f), 1e-5f)
        // Beside the 2 m square: 3 m from its edge in x, 0 height.
        assertEquals(3f, PlaneFill.closestDistance(square, 4, pose, 4f, 0f, 0f), 1e-5f)
        // Off a corner, raised: sqrt(3² + 4² + 0) in-plane then with 0 height → 5.
        assertEquals(5f, PlaneFill.closestDistance(square, 4, pose, 4f, 0f, 5f), 1e-5f)
        // Translated pose.
        assertEquals(2f, PlaneFill.closestDistance(square, 4, translation(10f, 0f, 0f), 10f, 2f, 0f), 1e-5f)
        assertEquals(5f, PlaneFill.centerDistance(translation(3f, 0f, 4f), 0f, 0f, 0f), 1e-5f)
    }
}
