package io.github.pennstatecs.geokit.ar.frame

import io.github.pennstatecs.geokit.ar.model.ModelCorrectionMatrix
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ArFramesTest {

    private val eps = 1e-6f

    @Test fun `anchor frame is East-Up-South`() {
        // east = +X, up = +Y, north = -Z
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), ArFrames.enuToAnchor(1f, 0f, 0f), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, -1f), ArFrames.enuToAnchor(0f, 1f, 0f), 0f)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), ArFrames.enuToAnchor(0f, 0f, 1f), 0f)
    }

    @Test fun `anchor to ENU inverts ENU to anchor`() {
        val a = ArFrames.enuToAnchor(1.5f, -2.25f, 0.75f)
        assertArrayEquals(floatArrayOf(1.5f, -2.25f, 0.75f), ArFrames.anchorToEnu(a[0], a[1], a[2]), 0f)
    }

    @Test fun `world delta through an identity anchor is east north up`() {
        val id = ArFrames.identity()
        // world -Z is north for an anchor whose axes match the world
        assertArrayEquals(floatArrayOf(0f, 2f, 0f), ArFrames.worldDeltaToEnu(id, 0f, 0f, -2f), 0f)
        assertArrayEquals(floatArrayOf(3f, 0f, 1f), ArFrames.worldDeltaToEnu(id, 3f, 1f, 0f), 0f)
    }

    @Test fun `world delta through an anchor turned 90 degrees about up`() {
        // Anchor X (east) along world -Z, anchor Z (south) along world +X.
        val m = ArFrames.identity().also {
            it[0] = 0f; it[2] = -1f   // X axis column
            it[8] = 1f; it[10] = 0f   // Z axis column
        }
        // World +X = anchor south ⇒ 1 m south.
        assertArrayEquals(floatArrayOf(0f, -1f, 0f), ArFrames.worldDeltaToEnu(m, 1f, 0f, 0f), eps)
        // World -Z = anchor east.
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), ArFrames.worldDeltaToEnu(m, 0f, 0f, -1f), eps)
    }

    @Test fun `glTF +Z north lands on anchor -Z through the base correction`() {
        val cm = ModelCorrectionMatrix.base(1f, 0f, 0f, 0f)
        val north = ArFrames.transformPoint(cm, 0f, 0f, 1f)
        val enu = ArFrames.anchorToEnu(north[0], north[1], north[2])
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), enu, 0f)
        // model -X lands on east
        val e = ArFrames.transformPoint(cm, -1f, 0f, 0f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), ArFrames.anchorToEnu(e[0], e[1], e[2]), 0f)
    }

    @Test fun `transformPoint applies column-major translation`() {
        val m = ArFrames.identity().also { it[12] = 1f; it[13] = 2f; it[14] = 3f }
        assertArrayEquals(floatArrayOf(2f, 4f, 6f), ArFrames.transformPoint(m, 1f, 2f, 3f), 0f)
        assertArrayEquals(floatArrayOf(2f, 4f, 6f, 1f), ArFrames.multiplyMV(m, floatArrayOf(1f, 2f, 3f, 1f)), 0f)
    }

    @Test fun `multiplyMM composes right to left`() {
        val t = ArFrames.identity().also { it[12] = 5f }
        val s = ArFrames.identity().also { it[0] = 2f; it[5] = 2f; it[10] = 2f }
        // T · S applies S first: x=1 → 2 → 7
        assertArrayEquals(floatArrayOf(7f, 0f, 0f), ArFrames.transformPoint(ArFrames.multiplyMM(t, s), 1f, 0f, 0f), 0f)
        // S · T applies T first: x=1 → 6 → 12
        assertArrayEquals(floatArrayOf(12f, 0f, 0f), ArFrames.transformPoint(ArFrames.multiplyMM(s, t), 1f, 0f, 0f), 0f)
    }

    @Test fun `NDC and screen pixels round-trip with y flipped`() {
        assertEquals(0f, ArFrames.ndcToScreenX(-1f, 1000), 0f)
        assertEquals(1000f, ArFrames.ndcToScreenX(1f, 1000), 0f)
        assertEquals(0f, ArFrames.ndcToScreenY(1f, 500), 0f)       // NDC top = screen row 0
        assertEquals(500f, ArFrames.ndcToScreenY(-1f, 500), 0f)
        for (px in listOf(0f, 123.5f, 500f, 999f)) {
            assertEquals(px, ArFrames.ndcToScreenX(ArFrames.screenToNdcX(px, 1000), 1000), 1e-3f)
            assertEquals(px / 2f, ArFrames.ndcToScreenY(ArFrames.screenToNdcY(px / 2f, 500), 500), 1e-3f)
        }
    }
}
