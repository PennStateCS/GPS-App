package app.surrealar.ui.openinar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The generated test cube is a well-formed GLB 2.0 file. */
class TestCubeGlbTest {

    private val glb = TestCubeGlb.build()
    private val buf = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)

    @Test fun `header magic, version and total length`() {
        assertEquals(0x46546C67, buf.getInt(0))
        assertEquals(2, buf.getInt(4))
        assertEquals(glb.size, buf.getInt(8))
        assertEquals(0, glb.size % 4)
    }

    @Test fun `JSON then BIN chunk, both 4-byte aligned, filling the file`() {
        val jsonLen = buf.getInt(12)
        assertEquals(0x4E4F534A, buf.getInt(16))
        assertEquals(0, jsonLen % 4)
        val binHeader = 20 + jsonLen
        val binLen = buf.getInt(binHeader)
        assertEquals(0x004E4942, buf.getInt(binHeader + 4))
        assertEquals(0, binLen % 4)
        assertEquals(glb.size, binHeader + 8 + binLen)
        val json = String(glb, 20, jsonLen, Charsets.UTF_8).trim()
        assertTrue(json.startsWith("{\"asset\":{\"version\":\"2.0\""))
        assertTrue("two primitives", json.contains("\"material\":0") && json.contains("\"material\":1"))
        // The geometry is a multiple of 4 bytes, so the BIN chunk needs no padding and matches the buffer.
        assertTrue("buffer length matches the BIN chunk", json.contains("\"buffers\":[{\"byteLength\":$binLen}]"))
    }

    @Test fun `positions stand on the origin and the nose sticks out on +Z`() {
        val json = String(glb, 20, buf.getInt(12), Charsets.UTF_8)
        assertTrue(json.contains("\"min\":[-0.5,0.0,-0.5],\"max\":[0.5,1.0,0.5]"))
        assertTrue(json.contains("\"max\":[0.15,0.65,0.8]"))
        // First vertex of the cube's +X face, read from the binary chunk.
        val bin = 20 + buf.getInt(12) + 8
        assertEquals(0.5f, buf.getFloat(bin), 0f)
        assertEquals(0f, buf.getFloat(bin + 4), 0f)
        assertEquals(0.5f, buf.getFloat(bin + 8), 0f)
    }
}
