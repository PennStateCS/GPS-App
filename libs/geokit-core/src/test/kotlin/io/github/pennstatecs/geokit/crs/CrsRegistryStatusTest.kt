package io.github.pennstatecs.geokit.crs

import io.github.pennstatecs.geokit.datum.FrameEdge
import io.github.pennstatecs.geokit.datum.FrameTransformationRegistry
import io.github.pennstatecs.geokit.datum.ReferenceFrame
import io.github.pennstatecs.geokit.status.ProductStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Effective status of registry entries = the weaker of the entry's declared status and the status of its
 * frame path from "WGS84 (ITRF2014)". The frame edges below are TEST STUBS (identity maths): only their
 * status matters here, they are never registered in the default registry.
 */
class CrsRegistryStatusTest {

    private val params = "lat_0=40;lon_0=-77;k_0=1;x_0=0;y_0=0"
    private val catalog = CrsCatalogCsv.parse(
        """
        ${CrsCatalogCsv.HEADER_V2}
        B1,,Beta zone,natrf2022,lcc1sp,$params,metre,-80,39,-74,42,d,v.csv,verified_beta,
        L1,,Legacy-declared zone,natrf2022,lcc1sp,$params,metre,-80,39,-74,42,d,v.csv,verified_legacy,
        N1,,NAD83 zone,nad83-2011,tm,$params,metre,-80,39,-74,42,d,v.csv,verified_legacy,
        """.trimIndent(),
        CrsCatalogId.SPCS2022,
    )

    private fun registryWith(edgeStatus: ProductStatus?): FrameTransformationRegistry =
        if (edgeStatus == null) FrameTransformationRegistry.DEFAULT
        else FrameTransformationRegistry(
            FrameTransformationRegistry.DEFAULT.registeredEdges + FrameEdge(
                ReferenceFrame.ITRF2020, ReferenceFrame.NATRF2022, "TEST STUB identity", null, "test only",
                edgeStatus, "test-version", { p, _ -> p }, { p, _ -> p },
            ),
            FrameTransformationRegistry.DEFAULT_UNAVAILABLE - ReferenceFrame.NATRF2022,
        )

    private fun crs(edgeStatus: ProductStatus?) =
        CrsRegistry(frameRegistry = registryWith(edgeStatus), spcs2022Source = { catalog }, spcs2022BoundaryResource = null)

    private fun CrsRegistry.status(code: String): ProductStatus =
        catalog(CrsCatalogId.SPCS2022).entries.first { it.zoneCode == code }.status

    @Test fun `default registry - zones on an unreachable frame stay unavailable, whatever they declare`() {
        val r = crs(null)
        assertEquals(ProductStatus.UNAVAILABLE, r.status("B1"))
        assertEquals(ProductStatus.UNAVAILABLE, r.status("L1"))
        assertEquals(ProductStatus.VERIFIED_LEGACY, r.status("N1"))
        assertTrue(r.byId("spcs2022:L1") is CrsLookup.Unsupported)
        assertFalse(r.geographic(ReferenceFrame.NATRF2022).operational)
    }

    @Test fun `beta frame path - the weaker status wins`() {
        val r = crs(ProductStatus.VERIFIED_BETA)
        assertEquals(ProductStatus.VERIFIED_BETA, r.status("B1"))
        assertEquals(ProductStatus.VERIFIED_BETA, r.status("L1"))     // legacy zone, beta frame → beta
        assertEquals(ProductStatus.VERIFIED_LEGACY, r.status("N1"))   // legacy path untouched
        // Beta entries stay opt-in.
        assertTrue(r.search("Legacy-declared", CrsCatalogId.SPCS2022).isEmpty())
        assertEquals("L1", r.search("Legacy-declared", CrsCatalogId.SPCS2022, includeBeta = true).single().zoneCode)
        // The geographic CRS of a frame reachable only through a beta edge is beta as well.
        val geo = r.geographic(ReferenceFrame.NATRF2022)
        assertTrue(geo.operational)
        assertEquals(ProductStatus.VERIFIED_BETA, geo.status)
        assertTrue(r.search("NATRF2022", CrsCatalogId.GEOGRAPHIC).isEmpty())
        // Legacy frames keep their status.
        assertEquals(ProductStatus.VERIFIED_LEGACY, r.geographic(ReferenceFrame.ITRF2020).status)
        assertEquals(ProductStatus.VERIFIED_LEGACY, r.geographic(ReferenceFrame.NAD83_2011).status)
    }

    @Test fun `legacy frame path - the declared beta status is kept`() {
        val r = crs(ProductStatus.VERIFIED_LEGACY)
        assertEquals(ProductStatus.VERIFIED_BETA, r.status("B1"))
        assertEquals(ProductStatus.VERIFIED_LEGACY, r.status("L1"))
        assertEquals(ProductStatus.VERIFIED_LEGACY, r.geographic(ReferenceFrame.NATRF2022).status)
    }

    @Test fun `shipped registry - no SPCS2022 zone and no 2022 frame is operational`() {
        val r = CrsRegistry.DEFAULT
        val c = r.catalog(CrsCatalogId.SPCS2022)
        assertEquals(953, c.entries.size)
        assertTrue(c.operational.isEmpty())
        assertTrue(c.entries.all { it.status == ProductStatus.UNAVAILABLE })
        assertEquals(ProductStatus.UNAVAILABLE, r.geographic(ReferenceFrame.NATRF2022).status)
        // Every geographic CRS that is operational today is legacy (no beta frame edges are registered).
        assertTrue(r.catalog(CrsCatalogId.GEOGRAPHIC).operational.all { it.status == ProductStatus.VERIFIED_LEGACY })
    }
}
