package app.surrealar.ui.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric for a real org.json (the plain unit-test android.jar stubs it out).
@RunWith(RobolectricTestRunner::class)
class GltfResourcesTest {

    @Test
    fun externalResourceUris_collectsBufferAndImageUris() {
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
                {"uri": "  "}
              ],
              "extensions": {"X": {"uri": "not-a-resource.txt"}}
            }
        """.trimIndent()

        assertEquals(
            listOf("scene.bin", "textures/wood%20grain.png", "textures/metal.jpg"),
            GltfResources.externalResourceUris(json)
        )
    }

    @Test
    fun externalResourceUris_noResourcesOrBadJson_isEmpty() {
        assertTrue(GltfResources.externalResourceUris("""{"asset":{"version":"2.0"}}""").isEmpty())
        assertTrue(GltfResources.externalResourceUris("not json").isEmpty())
        assertTrue(GltfResources.externalResourceUris("").isEmpty())
    }

    @Test
    fun externalResourceUris_badJson_isLoggedNotSwallowed() {
        val dir = java.nio.file.Files.createTempDirectory("diaglog").toFile()
        app.surrealar.util.DiagnosticsLogger.initForTest(dir)
        try {
            assertTrue(GltfResources.externalResourceUris("not json").isEmpty())
            app.surrealar.util.DiagnosticsLogger.flush()
            val log = java.io.File(dir, "app-log-current.txt").readText()
            assertTrue(log.contains("glTF JSON unparseable"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
