package app.surrealar.ui.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelFileIoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun readToDirectBuffer_readsWholeFileIntoDirectBufferAtZero() {
        val data = ByteArray(300_000) { (it % 251).toByte() }
        val f = tmp.newFile("m.glb").apply { writeBytes(data) }

        val buf = ModelFileIo.readToDirectBuffer(f)

        assertTrue(buf.isDirect)
        assertEquals(0, buf.position())
        assertEquals(data.size, buf.remaining())
        val out = ByteArray(data.size).also { buf.get(it) }
        assertTrue(data.contentEquals(out))
    }

    @Test
    fun readToDirectBuffer_emptyFile() {
        val f = tmp.newFile("empty.glb")
        assertEquals(0, ModelFileIo.readToDirectBuffer(f).remaining())
    }

    @Test
    fun sizeLimit_is150MiB() {
        assertFalse(ModelFileIo.isTooLarge(150L * 1024 * 1024))
        assertTrue(ModelFileIo.isTooLarge(150L * 1024 * 1024 + 1))
        assertFalse(ModelFileIo.isTooLarge(0))
    }
}
