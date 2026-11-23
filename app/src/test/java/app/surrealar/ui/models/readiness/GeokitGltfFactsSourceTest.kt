package app.surrealar.ui.models.readiness

import io.github.pennstatecs.geokit.ar.model.Vec3
import io.github.pennstatecs.geokit.models.gltf.GltfBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import io.github.pennstatecs.geokit.models.gltf.GltfFacts as ModelFileFacts

// Robolectric for a real org.json (geokit-models compiles against it; the plain android.jar stubs it).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeokitGltfFactsSourceTest {

    @Test fun `test cube reads through the library with float bounds`() {
        val url = javaClass.classLoader!!.getResource("models/surreal-test-cube-1m.glb")!!
        val f = GeokitGltfFactsSource.read(File(url.toURI()))
        assertNull(f.parseError)
        assertEquals(-0.5f, f.bounds!!.min.x, 1e-6f)
        assertEquals(1f, f.bounds!!.max.y, 1e-6f)
        assertEquals(12L, f.triangleCount)
        assertEquals(2048, f.maxTextureSidePx)
    }

    @Test fun `every field is mapped`() {
        val lib = ModelFileFacts(
            fileSizeBytes = 5, bounds = GltfBounds(-1.25, 0.0, -3.0, 1.0, 2.5, 3.0), triangleCount = 7,
            vertexCount = 9, meshCount = 2, textureCount = 3, maxTextureSidePx = 512, estimatedTextureBytes = 99,
            extensionsRequired = listOf("A"), extensionsUsed = listOf("A", "B"), missingResources = listOf("m.png"),
            parseError = "x",
        )
        val app = lib.toReadinessFacts()
        assertEquals(
            GltfFacts(
                fileSizeBytes = 5,
                bounds = Bounds(Vec3(-1.25f, 0f, -3f), Vec3(1f, 2.5f, 3f)),
                triangleCount = 7, vertexCount = 9, meshCount = 2, textureCount = 3, maxTextureSidePx = 512,
                estimatedTextureBytes = 99, extensionsRequired = listOf("A"), extensionsUsed = listOf("A", "B"),
                missingResources = listOf("m.png"), parseError = "x",
            ),
            app,
        )
    }
}
