package app.surrealar.util.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogPrivacyTest {

    @Test fun `ref is a short stable hash, never the text`() {
        val r = LogPrivacy.ref("Smith Residence Backyard")
        assertTrue(r.matches(Regex("#[0-9a-f]{6}")))
        assertEquals(r, LogPrivacy.ref("Smith Residence Backyard"))
        assertNotEquals(r, LogPrivacy.ref("Jones Residence"))
        assertEquals("unknown", LogPrivacy.ref(null))
        assertEquals("unknown", LogPrivacy.ref("  "))
    }

    @Test fun `file keeps only the extension and hashes the basename`() {
        val f = LogPrivacy.file("/storage/emulated/0/Download/JohnDoe-house.GLB")
        assertTrue(f, f.matches(Regex("#[0-9a-f]{6}\\.glb")))
        assertFalse(f.contains("JohnDoe"))
        assertEquals(f, LogPrivacy.file("JohnDoe-house.GLB"))          // same file, same hash, path or not
        assertEquals(LogPrivacy.file("a.glb"), LogPrivacy.file("C:\\x\\a.glb"))
        assertTrue(LogPrivacy.file("README").matches(Regex("#[0-9a-f]{6}")))
        assertTrue(LogPrivacy.file(".hidden").matches(Regex("#[0-9a-f]{6}")))
        assertEquals("unknown", LogPrivacy.file(null))
    }

    @Test fun `errorType is the class name only`() {
        assertEquals("FileNotFoundException", LogPrivacy.errorType(java.io.FileNotFoundException("/storage/emulated/0/x.glb")))
        assertEquals("unknown", LogPrivacy.errorType(null))
    }
}
