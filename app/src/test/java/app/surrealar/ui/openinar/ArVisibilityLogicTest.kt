package app.surrealar.ui.openinar

import app.surrealar.data.local.entity.CoordinateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the shared AR model-visibility rules (used by both the bottom sheet and the AR render gate):
 * cold-open derives from the map selection until customised, toggling produces the right set, and the
 * selection is independent of the range filter.
 */
class ArVisibilityLogicTest {

    private fun cwm(id: String, hasModel: Boolean = true, renderEnabled: Boolean = true) = CoordWithModel(
        coordinate = CoordinateEntity(
            id = id, name = "P-$id", latitude = 41.0, longitude = -76.0, altitude = 100.0,
            timestamp = 1_000L, icon = "ic_pin", color = 0, modelId = if (hasModel) "m-$id" else null,
            renderEnabled = renderEnabled,
        ),
        modelId = if (hasModel) "m-$id" else null,
        modelFilePath = if (hasModel) "/models/$id.glb" else null,
    )

    // ── effectiveVisibleIds ──────────────────────────────────────────────────────────────────────

    @Test fun `cold open (not customized) derives from map selection - renderEnabled model coords`() {
        val coords = listOf(cwm("a"), cwm("b", renderEnabled = false), cwm("c", hasModel = false))
        val eff = ArVisibilityLogic.effectiveVisibleIds(coords, customized = false, storedIds = setOf("zzz"))
        assertEquals(setOf("a"), eff)   // b hidden on map, c has no model, stored set ignored
    }

    @Test fun `once customized the stored set is authoritative (ignores map selection)`() {
        val coords = listOf(cwm("a"), cwm("b"))
        val eff = ArVisibilityLogic.effectiveVisibleIds(coords, customized = true, storedIds = setOf("b"))
        assertEquals(setOf("b"), eff)   // 'a' is renderEnabled on the map but not in the AR set
    }

    @Test fun `use map selection (customized cleared) derives from map again`() {
        val coords = listOf(cwm("a"), cwm("b"))
        // Was customized to just {b}; clearing customized reverts to the map selection (both enabled).
        val eff = ArVisibilityLogic.effectiveVisibleIds(coords, customized = false, storedIds = setOf("b"))
        assertEquals(setOf("a", "b"), eff)
    }

    // ── toggle ───────────────────────────────────────────────────────────────────────────────────

    @Test fun `toggle adds and removes without touching the rest`() {
        assertEquals(setOf("a", "b"), ArVisibilityLogic.toggle(setOf("a"), "b", selected = true))
        assertEquals(setOf("a"), ArVisibilityLogic.toggle(setOf("a", "b"), "b", selected = false))
    }

    @Test fun `changing range does not delete selected ids`() {
        // The selection set is independent of range — effectiveVisibleIds takes no range at all.
        val coords = listOf(cwm("a"), cwm("b"))
        val selection = setOf("a", "b")
        val eff = ArVisibilityLogic.effectiveVisibleIds(coords, customized = true, storedIds = selection)
        assertEquals(selection, eff)
        // And a tighter range only affects render eligibility, never the stored selection.
        assertFalse(ArVisibilityLogic.inRange(ArVisibilityMode.SELECTED, distanceM = 80.0, rangeM = 50.0))
        assertEquals(selection, eff)   // unchanged
    }

    // ── inRange / renderable (mode semantics) ─────────────────────────────────────────────────────

    @Test fun `ALL mode ignores range`() {
        assertTrue(ArVisibilityLogic.inRange(ArVisibilityMode.ALL, distanceM = 9_999.0, rangeM = 50.0))
        assertTrue(ArVisibilityLogic.renderable(ArVisibilityMode.ALL, inRange = false, selected = false))
    }

    @Test fun `NEARBY renders in-range regardless of selection`() {
        assertTrue(ArVisibilityLogic.renderable(ArVisibilityMode.NEARBY, inRange = true, selected = false))
        assertFalse(ArVisibilityLogic.renderable(ArVisibilityMode.NEARBY, inRange = false, selected = true))
    }

    @Test fun `SELECTED renders only when selected and in range`() {
        assertTrue(ArVisibilityLogic.renderable(ArVisibilityMode.SELECTED, inRange = true, selected = true))
        assertFalse(ArVisibilityLogic.renderable(ArVisibilityMode.SELECTED, inRange = true, selected = false))
        assertFalse(ArVisibilityLogic.renderable(ArVisibilityMode.SELECTED, inRange = false, selected = true))
    }

    @Test fun `null range means unlimited for non-ALL modes`() {
        assertTrue(ArVisibilityLogic.inRange(ArVisibilityMode.NEARBY, distanceM = 5_000.0, rangeM = null))
    }
}
