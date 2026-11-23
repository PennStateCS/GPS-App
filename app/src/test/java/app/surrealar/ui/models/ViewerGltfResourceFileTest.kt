package app.surrealar.ui.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The viewer only loads glTF resources from inside the model's own folder. */
class ViewerGltfResourceFileTest {

    @get:Rule val tmp = TemporaryFolder()

    private val identity: (String) -> String = { it }

    @Test fun `a resource in the folder or a subfolder is found`() {
        val base = tmp.newFolder("bundle")
        val tex = File(base, "tex/a.png").apply { parentFile!!.mkdirs(); writeText("x") }
        File(base, "mesh.bin").writeText("x")
        assertEquals(tex.canonicalFile, viewerGltfResourceFile(base, "tex/a.png", identity))
        assertEquals(File(base, "mesh.bin").canonicalFile, viewerGltfResourceFile(base, "mesh.bin?v=2#frag", identity))
    }

    @Test fun `a sibling folder sharing the name prefix is not inside`() {
        val base = tmp.newFolder("bundle")
        File(tmp.newFolder("bundle2"), "secret.bin").writeText("x")
        // Old check: "…/bundle2/secret.bin".startsWith("…/bundle") was true.
        assertNull(viewerGltfResourceFile(base, "../bundle2/secret.bin", identity))
    }

    @Test fun `escapes and missing files are rejected`() {
        val base = tmp.newFolder("bundle")
        File(tmp.root, "outside.bin").writeText("x")
        assertNull(viewerGltfResourceFile(base, "../outside.bin", identity))
        assertNull(viewerGltfResourceFile(base, "missing.png", identity))
    }

    @Test fun `the decoder is applied before resolving`() {
        val base = tmp.newFolder("bundle")
        File(base, "a b.png").writeText("x")
        assertEquals(File(base, "a b.png").canonicalFile,
            viewerGltfResourceFile(base, "a%20b.png") { it.replace("%20", " ") })
    }
}
