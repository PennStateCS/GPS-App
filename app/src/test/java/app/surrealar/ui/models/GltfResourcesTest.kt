package app.surrealar.ui.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The listing itself is tested in geokit-models (GltfUrisTest); this checks the app wrapper.
// Robolectric for a real org.json (the plain unit-test android.jar stubs it out).
@RunWith(RobolectricTestRunner::class)
class GltfResourcesTest {

    @Test
    fun externalResourceUris_delegatesToTheLibrary() {
        val json = """{"buffers":[{"uri":"scene.bin"},{"uri":"data:,x"}],"images":[{"uri":"tex/a%20b.png"}]}"""
        assertEquals(listOf("scene.bin", "tex/a%20b.png"), GltfResources.externalResourceUris(json))
    }

    @Test
    fun externalResourceUris_badJson_isLoggedNotSwallowed() {
        val dir = java.nio.file.Files.createTempDirectory("diaglog").toFile()
        app.surrealar.util.DiagnosticsLogger.initForTest(dir)
        try {
            assertTrue(GltfResources.externalResourceUris("not json").isEmpty())
            assertTrue(GltfResources.externalResourceUris("").isEmpty())
            app.surrealar.util.DiagnosticsLogger.flush()
            val log = java.io.File(dir, "app-log-current.txt").readText()
            assertTrue(log.contains("Model glTF resource listing failed: JSON unparseable"))
        } finally {
            dir.deleteRecursively()
        }
    }
}