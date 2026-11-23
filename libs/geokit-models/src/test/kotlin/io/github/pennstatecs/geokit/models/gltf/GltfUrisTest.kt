package io.github.pennstatecs.geokit.models.gltf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GltfUrisTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `externalResourceUris collects buffer and image uris`() {
        val json = """
            {
              "asset": {"version": "2.0"},
              "buffers": [
                {"uri": "scene.bin", "byteLength": 10},
                {"uri": "data:application/octet-stream;base64,AAAA", "byteLength": 3},
                {"byteLength": 4}
              ],
              "images": [
                {"uri": "textures/wood%20grain.png"},
                {"uri": "textures\/metal.jpg"},
                {"uri": "scene.bin"},
                {"bufferView": 2, "mimeType": "image/png"},
                {"uri": "  "},
                {"uri": "say \"hi\".png"}
              ],
              "extensions": {"X": {"uri": "not-a-resource.txt"}}
            }
        """.trimIndent()

        assertEquals(
            listOf("scene.bin", "textures/wood%20grain.png", "textures/metal.jpg", "say \"hi\".png"),
            GltfUris.externalResourceUris(json).getOrThrow(),
        )
    }

    @Test fun `externalResourceUris without resources is empty, bad json is a failure`() {
        assertTrue(GltfUris.externalResourceUris("""{"asset":{"version":"2.0"}}""").getOrThrow().isEmpty())
        assertTrue(GltfUris.externalResourceUris("not json").isFailure)
        assertTrue(GltfUris.externalResourceUris("").isFailure)
    }

    @Test fun `decode keeps plus literal and decodes percent escapes`() {
        assertEquals("a+b.png", GltfUris.decode("a+b.png"))
        assertEquals("wood grain.png", GltfUris.decode("wood%20grain.png"))
        assertEquals("a+b c.png", GltfUris.decode("a%2Bb%20c.png"))
        assertEquals("bad%zz.png", GltfUris.decode("bad%zz.png"))      // invalid escape: unchanged
    }

    @Test fun `relativePath rejects data, schemes and absolute paths`() {
        assertNull(GltfUris.relativePath("data:image/png;base64,AAAA"))
        assertNull(GltfUris.relativePath("http://example.com/a.png"))
        assertNull(GltfUris.relativePath("file:///sdcard/a.png"))
        assertNull(GltfUris.relativePath("/sdcard/a.png"))
        assertNull(GltfUris.relativePath("\\a.png"))
        assertNull(GltfUris.relativePath("%2Fsdcard%2Fa.png"))
        assertEquals("tex/a b.png", GltfUris.relativePath("tex/a%20b.png"))
        assertEquals("C:\\x.png", GltfUris.relativePath("C:\\x.png"))   // a drive path is not a scheme
    }

    @Test fun `resolve finds files inside the folder only`() {
        val root = tmp.newFolder("models")
        val bundle = File(root, "bundle").also { it.mkdirs() }
        File(bundle, "a+b.png").writeText("x")
        File(root, "outside.png").writeText("x")
        val sibling = tmp.newFolder("bundle2")
        File(sibling, "s.png").writeText("x")

        val found = GltfUris.resolve(bundle, "a+b.png")
        assertTrue(found is GltfUris.Resolution.Found)
        assertEquals(File(bundle, "a+b.png").canonicalFile, (found as GltfUris.Resolution.Found).file)
        assertTrue(GltfUris.resolve(bundle, "gone.png") is GltfUris.Resolution.Missing)
        assertEquals(GltfUris.Resolution.Outside, GltfUris.resolve(bundle, "../outside.png"))
        assertEquals(GltfUris.Resolution.Outside, GltfUris.resolve(File(root, "bundle"), "../bundle2/s.png"))
        assertEquals(GltfUris.Resolution.NotRelative, GltfUris.resolve(bundle, "https://x/y.png"))
        assertEquals(GltfUris.Resolution.Embedded, GltfUris.resolve(bundle, "data:,x"))
    }

    @Test fun `isInside needs a separator after the folder path`() {
        val models = tmp.newFolder("models").canonicalFile
        val models2 = tmp.newFolder("models2").canonicalFile
        assertTrue(GltfUris.isInside(models, File(models, "a.png").canonicalFile))
        assertFalse(GltfUris.isInside(models, File(models2, "a.png").canonicalFile))
        assertFalse(GltfUris.isInside(models, models))
    }
}
