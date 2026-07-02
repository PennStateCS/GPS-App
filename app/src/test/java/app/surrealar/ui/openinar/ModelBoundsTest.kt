package app.surrealar.ui.openinar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure model-bounds + placement-origin math that fixes large-GLB AR placement. The headline
 * case is Model1_local.glb, whose visible geometry sits ~156 m from the GLB origin — anchoring the
 * origin puts the structure far from the coordinate; anchoring bottom-center aligns it.
 */
class ModelBoundsTest {

    // Measured bounds of Model1_local.glb (metres, glTF Y-up).
    private val model1 = ModelBounds.fromMinMax(
        minX = 43.56f, minY = 132.13f, minZ = -72.29f,
        maxX = 52.74f, maxY = 137.16f, maxZ = -64.15f,
    )

    @Test fun `Model1 bounds size, center, and bottom-center`() {
        assertEquals(9.18f, model1.size.x, 0.01f)
        assertEquals(5.03f, model1.size.y, 0.01f)
        assertEquals(8.14f, model1.size.z, 0.01f)

        assertEquals(48.15f, model1.center.x, 0.01f)
        assertEquals(134.645f, model1.center.y, 0.01f)
        assertEquals(-68.22f, model1.center.z, 0.01f)

        // Y-up ⇒ bottom is min Y.
        assertEquals(48.15f, model1.bottomCenter.x, 0.01f)
        assertEquals(132.13f, model1.bottomCenter.y, 0.01f)
        assertEquals(-68.22f, model1.bottomCenter.z, 0.01f)
    }

    @Test fun `Model1 origin-to-bottom-center is about 156 m`() {
        assertEquals(156.30f, model1.originToBottomCenterM, 0.1f)
        assertTrue("geometry is far from origin", model1.originToBottomCenterM > ModelBounds.FAR_FROM_ORIGIN_M)
    }

    @Test fun `Bottom-center anchor point equals the geometry bottom-center`() {
        val ap = model1.anchorPoint(ModelPlacementOrigin.BOTTOM_CENTER)
        assertEquals(48.15f, ap.x, 0.01f)
        assertEquals(132.13f, ap.y, 0.01f)
        assertEquals(-68.22f, ap.z, 0.01f)
        // Conceptual model-local correction = -anchorPoint (matches the report's numbers).
        assertEquals(-48.15f, (-ap).x, 0.01f)
        assertEquals(-132.13f, (-ap).y, 0.01f)
        assertEquals(68.22f, (-ap).z, 0.01f)
    }

    @Test fun `Origin mode anchors the GLB origin (no translation)`() {
        val ap = model1.anchorPoint(ModelPlacementOrigin.ORIGIN)
        assertEquals(Vec3(0f, 0f, 0f), ap)
    }

    @Test fun `Center mode anchors the bounds center`() {
        assertEquals(model1.center, model1.anchorPoint(ModelPlacementOrigin.CENTER))
    }

    @Test fun `Custom mode anchors the supplied offset exactly (applied once)`() {
        val custom = Vec3(1f, 2f, 3f)
        assertEquals(custom, model1.anchorPoint(ModelPlacementOrigin.CUSTOM, custom))
    }

    @Test fun `pin-style model near origin is unaffected by Origin and stays close under bottom-center`() {
        // A well-authored pin: geometry centred at origin, base at Y=0.
        val pin = ModelBounds.fromCenterHalfExtent(0f, 0.1f, 0f, 0.05f, 0.1f, 0.05f)
        assertEquals(Vec3(0f, 0f, 0f), pin.anchorPoint(ModelPlacementOrigin.ORIGIN))
        // Bottom-center of a base-at-origin pin is ~origin, so it barely differs from Origin.
        assertTrue(pin.originToBottomCenterM < ModelBounds.FAR_FROM_ORIGIN_M)
        assertEquals(0f, pin.bottomCenter.y, 1e-4f)
    }

    @Test fun `from() parses tokens and falls back to CENTER`() {
        assertEquals(ModelPlacementOrigin.BOTTOM_CENTER, ModelPlacementOrigin.from("BOTTOM_CENTER"))
        assertEquals(ModelPlacementOrigin.ORIGIN, ModelPlacementOrigin.from("ORIGIN"))
        assertEquals(ModelPlacementOrigin.CENTER, ModelPlacementOrigin.from(null))
        assertEquals(ModelPlacementOrigin.CENTER, ModelPlacementOrigin.from("garbage"))
    }

    // ── resolveOffsets: the custom offset must be applied in EXACTLY ONE place (no double-apply). ──

    @Test fun `Origin applies the custom offset once as a nudge, not as the anchor point`() {
        val off = Vec3(1f, 2f, 3f)
        val r = model1.resolveOffsets(ModelPlacementOrigin.ORIGIN, off, verticalOffsetM = 0.5f)
        assertEquals(Vec3(0f, 0f, 0f), r.anchorPoint)   // Origin anchors the GLB origin (offset NOT here)
        assertEquals(Vec3(1f, 2.5f, 3f), r.nudge)       // offset (+ vertical) in the nudge, applied once
    }

    @Test fun `Custom folds the offset into the anchor point, not the nudge`() {
        val off = Vec3(1f, 2f, 3f)
        val r = model1.resolveOffsets(ModelPlacementOrigin.CUSTOM, off, verticalOffsetM = 0.5f)
        assertEquals(off, r.anchorPoint)                // offset is the anchor point
        assertEquals(Vec3(0f, 0.5f, 0f), r.nudge)       // only the vertical remains → offset NOT re-applied
    }

    @Test fun `Bottom center anchors the geometry and applies the offset once as a nudge`() {
        val off = Vec3(1f, 0f, 0f)
        val r = model1.resolveOffsets(ModelPlacementOrigin.BOTTOM_CENTER, off, verticalOffsetM = 0f)
        assertEquals(model1.bottomCenter, r.anchorPoint)
        assertEquals(Vec3(1f, 0f, 0f), r.nudge)
    }

    @Test fun `Center with no offset produces the historical center anchor and zero nudge`() {
        val r = model1.resolveOffsets(ModelPlacementOrigin.CENTER, Vec3(0f, 0f, 0f), verticalOffsetM = 0f)
        assertEquals(model1.center, r.anchorPoint)
        assertEquals(Vec3(0f, 0f, 0f), r.nudge)
    }
}
