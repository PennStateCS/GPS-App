package io.github.pennstatecs.geokit.ar.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of [ModelPlacement] / [ModelMove]; `resolve(coordinate, model)` is tested in the app. */
class ModelPlacementTest {

    @Test fun `default placement is the identity`() {
        assertTrue(ModelPlacement().isIdentity)
        assertEquals(ModelPlacementOrigin.CENTER, ModelPlacement().placementOrigin)
    }

    @Test fun `any non-default field breaks identity`() {
        assertFalse(ModelPlacement(scale = 2f).isIdentity)
        assertFalse(ModelPlacement(yawDeg = 1f).isIdentity)
        assertFalse(ModelPlacement(verticalOffsetM = 0.1f).isIdentity)
        assertFalse(ModelPlacement(originOffsetZM = 0.1f).isIdentity)
        assertFalse(ModelPlacement(moveNorthM = 0.1f).isIdentity)
    }

    @Test fun `placement origin alone does not break identity`() {
        // Historical behaviour: the preset is not part of isIdentity.
        assertTrue(ModelPlacement(placementOrigin = ModelPlacementOrigin.BOTTOM_CENTER).isIdentity)
    }

    @Test fun `adding a move shifts east north up and yaw only`() {
        val base = ModelPlacement(scale = 2f, yawDeg = 10f, moveEastM = 1f, moveNorthM = 2f, moveUpM = 3f)
        val p = base + ModelMove(eastM = 0.5f, northM = -1f, upM = 0.25f, yawDeg = -30f)
        assertEquals(1.5f, p.moveEastM, 0f)
        assertEquals(1f, p.moveNorthM, 0f)
        assertEquals(3.25f, p.moveUpM, 0f)
        assertEquals(-20f, p.yawDeg, 0f)
        assertEquals(2f, p.scale, 0f)
    }

    @Test fun `adding a zero move returns the same instance`() {
        val base = ModelPlacement(scale = 2f)
        assertSame(base, base + ModelMove())
    }

    @Test fun `moves add component-wise`() {
        val m = ModelMove(1f, 2f, 3f, 4f) + ModelMove(0.5f, -2f, 1f, -4f)
        assertEquals(ModelMove(1.5f, 0f, 4f, 0f), m)
        assertTrue(ModelMove().isZero)
        assertFalse(m.isZero)
    }

    @Test fun `stored origin tokens parse, unknown falls back to CENTER`() {
        assertEquals(ModelPlacementOrigin.BOTTOM_CENTER, ModelPlacementOrigin.from("BOTTOM_CENTER"))
        assertEquals(ModelPlacementOrigin.CUSTOM, ModelPlacementOrigin.from("CUSTOM"))
        assertEquals(ModelPlacementOrigin.ORIGIN, ModelPlacementOrigin.from("ORIGIN"))
        assertEquals(ModelPlacementOrigin.CENTER, ModelPlacementOrigin.from(null))
        assertEquals(ModelPlacementOrigin.CENTER, ModelPlacementOrigin.from("nonsense"))
    }

    @Test fun `stored origin token names never change`() {
        assertEquals(listOf("ORIGIN", "CENTER", "BOTTOM_CENTER", "CUSTOM"), ModelPlacementOrigin.entries.map { it.name })
    }
}
