package app.surrealar.coords

import app.surrealar.settings.model.CoordinateSystemSettings
import app.surrealar.settings.model.VerticalReference
import io.github.pennstatecs.geokit.crs.CrsCatalogId
import io.github.pennstatecs.geokit.crs.CrsRegistry
import io.github.pennstatecs.geokit.datum.CoordinateEpoch
import io.github.pennstatecs.geokit.datum.FrameEdge
import io.github.pennstatecs.geokit.datum.FrameTransformationLookup
import io.github.pennstatecs.geokit.datum.FrameTransformationRegistry
import io.github.pennstatecs.geokit.datum.ReferenceFrame
import io.github.pennstatecs.geokit.status.ProductStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 beta capability model: product states come from geokit's status and registries; SPCS2022 and the
 * 2022 frames are listed as unavailable with the registries' reasons and are never selectable.
 */
class Nsrs2022CapabilitiesTest {

    @Test fun `ProductStatus maps to the three UI states`() {
        assertEquals(CapabilityUiState.LEGACY, ProductStatus.VERIFIED_LEGACY.toUiState())
        assertEquals(CapabilityUiState.BETA, ProductStatus.VERIFIED_BETA.toUiState())
        assertEquals(CapabilityUiState.UNAVAILABLE, ProductStatus.UNAVAILABLE.toUiState())
        assertEquals("unavailable — awaiting NGS verification", CapabilityUiState.UNAVAILABLE.label)
    }

    @Test fun `an unavailable product can never be selectable`() {
        assertThrows(IllegalArgumentException::class.java) {
            Nsrs2022Capability("X", CapabilityUiState.UNAVAILABLE, selectable = true, reason = "r")
        }
    }

    @Test fun `GEOID2022 is beta and selectable with its dataset version`() {
        val g = Nsrs2022Capabilities.geoid2022()
        assertEquals(CapabilityUiState.BETA, g.state)
        assertTrue(g.selectable)
        assertNull(g.reason)
        assertTrue(g.note!!, g.note!!.contains("beta_v0a"))
        assertTrue(g.note!!.contains(VerticalReference.GEOID2022.label))
    }

    @Test fun `the four 2022 frames are unavailable, with the registry's reason, never selectable`() {
        val frames = Nsrs2022Capabilities.frames()
        assertEquals(listOf("NATRF2022", "PATRF2022", "CATRF2022", "MATRF2022"), frames.map { it.name })
        for ((c, id) in frames.zip(Nsrs2022Capabilities.FRAME_IDS)) {
            assertEquals(c.name, CapabilityUiState.UNAVAILABLE, c.state)
            assertFalse(c.selectable)
            val l = FrameTransformationRegistry.DEFAULT.find(ReferenceFrame.WGS84_AS_ITRF2014.id, id, CoordinateEpoch(2020.0))
                as FrameTransformationLookup.Unavailable
            assertEquals(l.explanation, c.reason)
        }
    }

    @Test fun `a frame that became verified is still not selectable (no 2022 output in this app)`() {
        val stub = FrameTransformationRegistry(
            FrameTransformationRegistry.DEFAULT.registeredEdges + FrameEdge(
                ReferenceFrame.ITRF2020, ReferenceFrame.NATRF2022, "TEST STUB identity", null, "test only",
                ProductStatus.VERIFIED_BETA, "test", { p, _ -> p }, { p, _ -> p },
            ),
            FrameTransformationRegistry.DEFAULT_UNAVAILABLE - ReferenceFrame.NATRF2022,
        )
        val natrf = Nsrs2022Capabilities.frames(stub).first()
        assertEquals(CapabilityUiState.BETA, natrf.state)
        assertFalse(natrf.selectable)
    }

    @Test fun `SPCS2022 is unavailable with the catalog's reason, never selectable`() {
        val s = Nsrs2022Capabilities.spcs2022()
        assertEquals("SPCS2022 (953 NGS beta zones)", s.name)
        assertEquals(CapabilityUiState.UNAVAILABLE, s.state)
        assertFalse(s.selectable)
        assertEquals(CrsRegistry.DEFAULT.catalog(CrsCatalogId.SPCS2022).explanation, s.reason)
        assertEquals(CrsRegistry.SPCS2022_UNSUPPORTED, s.reason)
    }

    @Test fun `only GEOID2022 is selectable, status text lists every product`() {
        val all = Nsrs2022Capabilities.all()
        assertEquals(listOf("GEOID2022 heights (NAPGD2022)"), all.filter { it.selectable }.map { it.name })
        val text = Nsrs2022Capabilities.statusText(all)
        assertTrue(text, text.contains("• GEOID2022 heights (NAPGD2022): beta"))
        for (n in listOf("NATRF2022", "PATRF2022", "CATRF2022", "MATRF2022", "SPCS2022 (953 NGS beta zones)")) {
            assertTrue(n, text.contains("• $n: unavailable — awaiting NGS verification"))
        }
        assertTrue(text, text.contains("Why: "))
        assertTrue(text, text.contains("SPCS2022 is not available"))
    }

    @Test fun `height note - beta in use, suspended beta, nothing for legacy choices`() {
        val active = CoordinateSystemSettings(vertical = VerticalReference.GEOID2022, nsrs2022Preview = true)
        val a = Nsrs2022Capabilities.verticalNote(active)!!
        assertTrue(a, a.contains("GEOID2022 (beta beta_v0a)") && a.contains("not for authoritative survey results"))
        val suspended = active.copy(nsrs2022Preview = false)
        val s = Nsrs2022Capabilities.verticalNote(suspended)!!
        assertTrue(s, s.contains("preview is off") && s.contains(VerticalReference.RECEIVER_MSL.label))
        for (v in VerticalReference.entries.filter { !it.isBeta }) {
            assertNull(Nsrs2022Capabilities.verticalNote(CoordinateSystemSettings(vertical = v)))
            assertNull(Nsrs2022Capabilities.verticalNote(CoordinateSystemSettings(vertical = v, nsrs2022Preview = true)))
        }
    }
}
