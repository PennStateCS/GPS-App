package app.surrealar.ui.openinar

import io.github.pennstatecs.geokit.ar.model.ModelPlacement
import io.github.pennstatecs.geokit.ar.model.ModelPlacementOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

/** A cached AR model follows relinks and placement edits made while AR stays open. */
class CachedModelActionTest {

    private val base = ModelPlacement()

    @Test fun `same file and placement needs nothing`() {
        assertEquals(CachedModelAction.NONE, cachedModelAction("/m/a.glb", base, "/m/a.glb", ModelPlacement()))
    }

    @Test fun `an offset and yaw edit updates the transform without a reload`() {
        val edited = base.copy(yawDeg = 30f, verticalOffsetM = 0.5f, originOffsetXM = 1.2f)
        assertEquals(CachedModelAction.UPDATE_PLACEMENT, cachedModelAction("/m/a.glb", base, "/m/a.glb", edited))
    }

    @Test fun `scale and origin edits also update the transform`() {
        assertEquals(CachedModelAction.UPDATE_PLACEMENT,
            cachedModelAction("/m/a.glb", base, "/m/a.glb", base.copy(scale = 2f)))
        assertEquals(CachedModelAction.UPDATE_PLACEMENT,
            cachedModelAction("/m/a.glb", base, "/m/a.glb", base.copy(placementOrigin = ModelPlacementOrigin.BOTTOM_CENTER)))
    }

    @Test fun `a relink to another file reloads, even with the same placement`() {
        assertEquals(CachedModelAction.RELOAD, cachedModelAction("/m/a.glb", base, "/m/b.glb", base))
        assertEquals(CachedModelAction.RELOAD, cachedModelAction("/m/a.glb", base, "/m/b.glb", base.copy(yawDeg = 10f)))
    }
}
