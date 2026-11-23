package io.github.pennstatecs.geokit.models.gltf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GltfSidecarsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun copiesResourcesIntoTheBundle() {
        val src = tmp.newFolder("picked")
        File(src, "tex").mkdirs()
        File(src, "tex/a.png").writeText("png")
        File(src, "scene.bin").writeText("bin")
        val bundle = tmp.newFolder("models", "scene")

        val report = GltfSidecars.copy(src, bundle, setOf("tex/a.png", "scene.bin", "gone.png"))

        assertEquals("png", File(bundle, "tex/a.png").readText())
        assertEquals("bin", File(bundle, "scene.bin").readText())
        assertEquals(listOf("gone.png"), report.missing)
        assertEquals(1, report.problems)
    }

    // Regression: the containment check was a bare path-prefix test, so a sibling folder whose name
    // starts with the source folder's name ("picked2" vs "picked") passed as "inside".
    @Test fun sourceInASiblingFolderWithTheSamePrefixIsNotCopied() {
        val src = tmp.newFolder("picked")
        val sibling = tmp.newFolder("picked2")
        File(sibling, "secret.png").writeText("not yours")
        val bundle = tmp.newFolder("bundle")

        val report = GltfSidecars.copy(src, bundle, setOf("../picked2/secret.png"))

        assertEquals(listOf("../picked2/secret.png"), report.missing)
        assertTrue(bundle.listFiles()!!.isEmpty())
    }

    // Regression: same prefix bug on the target side; "../s2/x.png" from bundle "s" wrote into "s2".
    @Test fun targetInASiblingFolderWithTheSamePrefixIsUnsafe() {
        val src = tmp.newFolder("source", "s2")          // source/s2/x.png is inside the picked folder
        File(src, "x.png").writeText("x")
        val models = tmp.newFolder("models")
        val bundle = File(models, "s").also { it.mkdirs() }

        val report = GltfSidecars.copy(src, bundle, setOf("../s2/x.png"))

        assertEquals(listOf("../s2/x.png"), report.unsafe)
        assertFalse("nothing written next to the bundle", File(models, "s2/x.png").exists())
    }

    // An I/O error on one resource is reported (first error kept) and the others are still copied.
    @Test fun ioErrorIsReportedAndOtherResourcesAreStillCopied() {
        val src = tmp.newFolder("picked")
        File(src, "a.png").writeText("a")
        File(src, "b.png").writeText("b")
        val bundle = tmp.newFolder("bundle")
        File(bundle, "a.png").mkdirs()                   // a directory where the file must go

        val report = GltfSidecars.copy(src, bundle, setOf("a.png", "b.png"))

        assertEquals(listOf("a.png"), report.failed)
        assertTrue(report.firstError != null)
        assertEquals("b", File(bundle, "b.png").readText())
        assertEquals(1, report.problems)
    }
}