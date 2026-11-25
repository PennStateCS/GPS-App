package app.surrealar.coords

import app.surrealar.settings.model.VerticalReference
import io.github.pennstatecs.geokit.crs.CrsCatalogCsv
import io.github.pennstatecs.geokit.crs.CrsCatalogId
import io.github.pennstatecs.geokit.crs.CrsRegistry
import io.github.pennstatecs.geokit.datum.FrameEdge
import io.github.pennstatecs.geokit.datum.FrameTransformationRegistry
import io.github.pennstatecs.geokit.datum.ReferenceFrame
import io.github.pennstatecs.geokit.status.ProductStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 WP U: Settings → Coordinates groups products into verified / verified NGS beta / unsupported,
 * from the Phase 3 capability model; unsupported items have a concise one-line reason (full registry text
 * kept for the info dialog) and are never selectable.
 */
class CoordinateSystemGroupsTest {

    private val shipped by lazy { Nsrs2022Capabilities.all() }

    @Test fun `preview ON - three groups, GEOID2022 beta, frames and SPCS2022 unsupported`() {
        val groups = CoordinateSystemGroups.build(preview = true, capabilities = shipped).toMap()
        assertEquals(listOf(CoordinateSystemGroup.VERIFIED, CoordinateSystemGroup.BETA, CoordinateSystemGroup.UNSUPPORTED),
            CoordinateSystemGroups.build(true, shipped).map { it.first })
        assertEquals(listOf("Systems", "Datums", "Heights"), groups.getValue(CoordinateSystemGroup.VERIFIED).map { it.name })
        val beta = groups.getValue(CoordinateSystemGroup.BETA).single()
        assertEquals("GEOID2022 heights (NAPGD2022)", beta.name)
        assertTrue(beta.selectable && beta.beta)
        assertTrue(beta.fullReason!!, beta.fullReason!!.contains("beta_v0a"))
        val unsupported = groups.getValue(CoordinateSystemGroup.UNSUPPORTED)
        assertEquals(listOf("NATRF2022", "PATRF2022", "CATRF2022", "MATRF2022", "SPCS2022 (953 NGS beta zones)"), unsupported.map { it.name })
        for (u in unsupported) {
            assertFalse(u.name, u.selectable)
            assertEquals("unavailable", u.value)
            assertNotNull(u.name, u.shortReason)
            assertTrue("${u.name}: '${u.shortReason}'", u.shortReason!!.length <= Nsrs2022Capabilities.MAX_SHORT_REASON)
            assertFalse("one line", u.shortReason!!.contains('\n'))
            // The full registry wording stays available (info dialog) and is longer than the summary.
            assertEquals(shipped.first { it.name == u.name }.reason, u.fullReason)
            assertTrue(u.fullReason!!.length > u.shortReason!!.length)
        }
        assertEquals("No NGS-verified transformation yet", unsupported.first().shortReason)
        assertEquals("Needs a verified 2022 frame; not in EPSG/PROJ", unsupported.last().shortReason)
        assertEquals(CrsRegistry.SPCS2022_UNSUPPORTED, unsupported.last().fullReason)
    }

    @Test fun `verified group lists only legacy options - never a beta height`() {
        val heights = CoordinateSystemGroups.verified().first { it.name == "Heights" }.value
        assertFalse(heights, heights.contains("GEOID2022"))
        for (v in VerticalReference.entries.filter { !it.isBeta }) assertTrue(v.shortLabel, heights.contains(v.shortLabel))
        assertTrue(CoordinateSystemGroups.verified().all { it.selectable && !it.beta })
    }

    @Test fun `preview OFF - no beta group`() {
        val groups = CoordinateSystemGroups.build(preview = false, capabilities = shipped).map { it.first }
        assertEquals(listOf(CoordinateSystemGroup.VERIFIED, CoordinateSystemGroup.UNSUPPORTED), groups)
    }

    @Test fun `an unsupported item can never be selectable`() {
        assertThrows(IllegalArgumentException::class.java) {
            CoordinateSystemGroupItem("X", CoordinateSystemGroup.UNSUPPORTED, "unavailable", selectable = true)
        }
    }

    @Test fun `nothing UNAVAILABLE lands in a selectable group`() {
        for (preview in listOf(true, false)) {
            for ((g, items) in CoordinateSystemGroups.build(preview, shipped)) {
                for (i in items) {
                    val cap = shipped.firstOrNull { it.name == i.name }
                    if (cap?.state == CapabilityUiState.UNAVAILABLE) assertEquals(i.name, CoordinateSystemGroup.UNSUPPORTED, g)
                }
            }
        }
    }

    @Test fun `concise reasons - known kinds, and the first clause cut to one line otherwise`() {
        val other = Nsrs2022Capability("Thing", CapabilityUiState.UNAVAILABLE, selectable = false,
            reason = "Thing is not available: a very long explanation that goes on. And on.")
        assertEquals("Thing is not available", Nsrs2022Capabilities.shortReason(other))
        val long = "x".repeat(200)
        val cut = Nsrs2022Capabilities.firstClause(long)
        assertEquals(Nsrs2022Capabilities.MAX_SHORT_REASON, cut.length)
        assertTrue(cut.endsWith("…"))
        assertNull(Nsrs2022Capabilities.shortReason(Nsrs2022Capabilities.geoid2022()))
    }

    @Test fun `stub registry with NATRF2022 reachable - SPCS2022 is verified but not offered, listed as such`() {
        val crs = stubCrs()
        val caps = Nsrs2022Capabilities.all(frames = stubFrames(), crs = crs)
        val spcs = caps.first { it.kind == CapabilityKind.SPCS }
        assertEquals(CapabilityUiState.BETA, spcs.state)
        assertFalse(spcs.selectable)
        val item = CoordinateSystemGroups.build(true, caps).toMap().getValue(CoordinateSystemGroup.UNSUPPORTED)
            .first { it.name.startsWith("SPCS2022") }
        assertEquals("not offered", item.value)
        assertEquals("Verified zones, not yet offered here", item.shortReason)
        assertTrue(item.beta)
        val natrf = caps.first { it.name == "NATRF2022" }
        assertEquals(CapabilityUiState.BETA, natrf.state)
        assertEquals("Verified, not yet an output frame here", Nsrs2022Capabilities.shortReason(natrf))
    }

    companion object {
        private const val PARAMS = "lat_0=40;lon_0=-77;k_0=1;x_0=0;y_0=0"

        /** TEST STUB: an identity ITRF2020 → NATRF2022 edge (beta); only its status matters. */
        fun stubFrames(): FrameTransformationRegistry = FrameTransformationRegistry(
            FrameTransformationRegistry.DEFAULT.registeredEdges + FrameEdge(
                ReferenceFrame.ITRF2020, ReferenceFrame.NATRF2022, "TEST STUB identity", null, "test only",
                ProductStatus.VERIFIED_BETA, "test", { p, _ -> p }, { p, _ -> p },
            ),
            FrameTransformationRegistry.DEFAULT_UNAVAILABLE - ReferenceFrame.NATRF2022,
        )

        /** A two-zone SPCS2022 catalog: one NATRF2022 beta zone and one (invalid for SPCS2022) NAD83 zone. */
        fun stubCrs(frames: FrameTransformationRegistry = stubFrames()): CrsRegistry {
            val catalog = CrsCatalogCsv.parse(
                """
                ${CrsCatalogCsv.HEADER_V2}
                3701,,Pennsylvania North,natrf2022,lcc1sp,$PARAMS,metre,-80,40,-74,42,d,v.csv,verified_beta,PA_N
                N1,,NAD83 zone,nad83-2011,tm,$PARAMS,metre,-80,39,-74,42,d,v.csv,verified_legacy,
                """.trimIndent(),
                CrsCatalogId.SPCS2022,
            )
            return CrsRegistry(frameRegistry = frames, spcs2022Source = { catalog }, spcs2022BoundaryResource = null)
        }
    }
}
