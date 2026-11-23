package io.github.pennstatecs.geokit.ar.model

import io.github.pennstatecs.geokit.ar.frame.ArFrames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ModelTransformTest {

    private val bounds = ModelBounds.fromMinMax(2f, 1f, -3f, 6f, 5f, 1f) // center (4,3,-1), bottom (4,1,-1)

    private fun assertVec(expected: Vec3, actual: Vec3, eps: Float) {
        assertEquals("x", expected.x, actual.x, eps)
        assertEquals("y", expected.y, actual.y, eps)
        assertEquals("z", expected.z, actual.z, eps)
    }

    @Test fun `no rotation and no nudge returns the base correction itself`() {
        val cm = ModelCorrectionMatrix.base(1f, 4f, 3f, -1f)
        assertSame(cm, ModelTransform.compose(cm, 0f, 0f, 0f, 0f, 0f, 0f))
    }

    @Test fun `a nudge only translates`() {
        val cm = ModelCorrectionMatrix.base(2f, 1f, 0f, 0f)
        val m = ModelTransform.compose(cm, 0f, 0f, 0f, 1f, 2f, 3f)
        val expected = cm.copyOf().also { it[12] += 1f; it[13] += 2f; it[14] += 3f }
        assertArrayEquals(expected, m, 1e-6f)
    }

    @Test fun `yaw 90 is counter-clockwise seen from above`() {
        // Model +Z (north) lands on anchor -Z; a +90° yaw turns north toward west (-X).
        val m = ModelTransform.compose(ModelCorrectionMatrix.base(1f, 0f, 0f, 0f), 90f, 0f, 0f, 0f, 0f, 0f)
        val p = ArFrames.transformPoint(m, 0f, 0f, 1f)
        val enu = ArFrames.anchorToEnu(p[0], p[1], p[2])
        assertArrayEquals(floatArrayOf(-1f, 0f, 0f), enu, 1e-6f)
    }

    @Test fun `move north shifts along anchor -Z`() {
        val offsets = PlacementOffsets(Vec3(0f, 0f, 0f), Vec3(0.5f, 1f, 0.25f))
        val n = ModelTransform.nudge(offsets, ModelPlacement(moveEastM = 1f, moveNorthM = 2f, moveUpM = 3f))
        assertEquals(Vec3(1.5f, 4f, 0.25f - 2f), n)
    }

    @Test fun `normalisation shrinks only beyond 2 km half-extent`() {
        assertEquals(1f, ModelTransform.normalizationScale(2000f), 0f)
        assertEquals(1f, ModelTransform.normalizationScale(150f), 0f)
        assertEquals(1f / 5000f, ModelTransform.normalizationScale(2500f), 0f)
    }

    @Test fun `toLocal inverts compose for many placements`() {
        val angles = listOf(0f, 90f, 180f, -90f, 37.5f, -123.25f)
        val pts = listOf(Vec3(0f, 0f, 0f), Vec3(1f, 2f, 3f), Vec3(-4.5f, 0.25f, 7f))
        for (origin in ModelPlacementOrigin.entries) for (yaw in angles) for (pitch in listOf(0f, 12f, -90f))
            for (roll in listOf(0f, 7.5f, 180f)) for (scale in listOf(1f, 0.25f, 3f)) {
                val offsets = bounds.resolveOffsets(origin, Vec3(0.5f, -1f, 2f), 0.75f)
                val ap = offsets.anchorPoint
                val n = offsets.nudge
                val m = ModelTransform.compose(ModelCorrectionMatrix.base(scale, ap.x, ap.y, ap.z), yaw, pitch, roll, n.x, n.y, n.z)
                for (local in pts) {
                    val w = ArFrames.transformPoint(m, local.x, local.y, local.z)
                    val back = ModelTransform.toLocal(Vec3(w[0], w[1], w[2]), ap, n, yaw, pitch, roll, scale)
                    assertVec(local, back, 1e-4f)
                }
            }
    }

    @Test fun `rotation matches the axis-aligned setRotateM layout`() {
        val ry = ModelTransform.rotation(90f, 0f, 1f, 0f)
        // column-major: x axis → (c, 0, -s), z axis → (s, 0, c)
        assertEquals(-1f, ry[2], 1e-6f)
        assertEquals(1f, ry[8], 1e-6f)
        assertEquals(1f, ry[5], 0f)
        assertEquals(1f, ry[15], 0f)
    }
}
