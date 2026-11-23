package app.surrealar.ui.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which sidecar files a .gltf import copies. Regression for the old regex parser, which URL-decoded '+'
 * to a space ("a+b.png" became "a b.png", so the texture was not copied) and stopped at an escaped
 * quote. Robolectric for a real org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GltfSidecarsResourcePathsTest {

    private fun paths(json: String) = GltfSidecars.resourcePaths(json).getOrThrow()

    @Test fun plusInAFileNameStaysPlus() {
        assertEquals(setOf("a+b.png"), paths("""{"images":[{"uri":"a+b.png"}]}"""))
    }

    @Test fun escapedQuotesInAUriAreKept() {
        assertEquals(setOf("say \"hi\".png"), paths("""{"images":[{"uri":"say \"hi\".png"}]}"""))
    }

    @Test fun percentEscapesAreDecodedAndQueryOrFragmentDropped() {
        assertEquals(
            setOf("tex/wood grain.png", "scene.bin", "t.png"),
            paths("""{"buffers":[{"uri":"scene.bin"}],"images":[{"uri":"tex/wood%20grain.png"},{"uri":"t.png?v=2#x"}]}"""),
        )
    }

    @Test fun embeddedRemoteAndAbsoluteUrisAreSkipped() {
        val json = """{"buffers":[{"uri":"data:application/octet-stream;base64,AAAA"},{"uri":"https://x.org/a.bin"}],
            "images":[{"uri":"file:///sdcard/a.png"},{"uri":"/sdcard/b.png"},{"uri":"ok.png"}]}"""
        assertEquals(setOf("ok.png"), paths(json))
    }

    @Test fun unparseableJsonIsAFailure() {
        assertTrue(GltfSidecars.resourcePaths("{ nope").isFailure)
    }
}