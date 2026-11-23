package io.github.pennstatecs.geokit.models.glb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.EOFException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GlbContainerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun glb(vararg chunks: Pair<Int, ByteArray>, version: Int = 2): ByteArray {
        val total = 12 + chunks.sumOf { 8 + it.second.size }
        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(GlbContainer.MAGIC); b.putInt(version); b.putInt(total)
        for ((type, data) in chunks) { b.putInt(data.size); b.putInt(type); b.put(data) }
        return b.array()
    }

    @Test fun `parses header and chunks`() {
        val bytes = glb(GlbContainer.CHUNK_JSON to "{}  ".toByteArray(), GlbContainer.CHUNK_BIN to ByteArray(8) { it.toByte() })
        val c = GlbContainer.parse(bytes)!!
        assertEquals(2, c.chunks.size)
        assertEquals("{}  ", c.text(c.json!!))
        assertEquals(32, c.bin!!.contentStart)
        assertEquals(8, c.bin!!.length)
    }

    @Test fun `rejects short, wrong magic and wrong version`() {
        assertNull(GlbContainer.parse(ByteArray(11)))
        assertNull(GlbContainer.parse(ByteArray(12)))
        assertNull(GlbContainer.parse(glb(GlbContainer.CHUNK_JSON to "{}  ".toByteArray(), version = 1)))
    }

    private fun setInt(b: ByteArray, at: Int, v: Int) = b.also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(at, v) }

    private fun assertProblem(p: GlbContainer.Problem, bytes: ByteArray) {
        assertEquals(p, GlbContainer.check(bytes))
        assertNull(GlbContainer.parse(bytes))
    }

    private fun valid() = glb(GlbContainer.CHUNK_JSON to "{}  ".toByteArray(), GlbContainer.CHUNK_BIN to ByteArray(8))

    @Test fun `a valid container has no problem`() {
        assertNull(GlbContainer.check(valid()))
    }

    @Test fun `truncated file is a length mismatch, not a quietly shorter container`() {
        val bytes = valid()
        assertProblem(GlbContainer.Problem.LENGTH_MISMATCH, bytes.copyOf(bytes.size - 1))
    }

    @Test fun `declared length must equal the byte count`() {
        assertProblem(GlbContainer.Problem.LENGTH_MISMATCH, setInt(valid(), 8, valid().size + 4))
        assertProblem(GlbContainer.Problem.LENGTH_MISMATCH, setInt(valid(), 8, valid().size - 4))
        assertProblem(GlbContainer.Problem.LENGTH_MISMATCH, setInt(valid(), 8, -1))   // 4 GB unsigned
        // Trailing bytes past the declared length are not a container either.
        val padded = valid() + ByteArray(4)
        assertProblem(GlbContainer.Problem.LENGTH_MISMATCH, padded)
    }

    @Test fun `header without any chunk is too short`() {
        val b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(GlbContainer.MAGIC).putInt(2).putInt(12).array()
        assertProblem(GlbContainer.Problem.TOO_SHORT, b)
    }

    @Test fun `chunk running past the end is rejected`() {
        // BIN chunk length claims 12 bytes but only 8 follow (total length kept consistent).
        assertProblem(GlbContainer.Problem.BAD_CHUNK_LENGTH, setInt(valid(), 24, 12))
    }

    @Test fun `negative (huge unsigned) and Int-overflowing chunk lengths are rejected`() {
        assertProblem(GlbContainer.Problem.BAD_CHUNK_LENGTH, setInt(valid(), 24, -8))
        assertProblem(GlbContainer.Problem.BAD_CHUNK_LENGTH, setInt(valid(), 24, Int.MAX_VALUE))
        assertProblem(GlbContainer.Problem.BAD_CHUNK_LENGTH, setInt(valid(), 12, Int.MIN_VALUE))
    }

    @Test fun `incomplete chunk header is rejected`() {
        val bytes = glb(GlbContainer.CHUNK_JSON to "{}  ".toByteArray()) + ByteArray(4)
        assertProblem(GlbContainer.Problem.BAD_CHUNK_LENGTH, setInt(bytes, 8, bytes.size))
    }

    @Test fun `misaligned JSON chunk is rejected`() {
        val bytes = glb(GlbContainer.CHUNK_JSON to "{} ".toByteArray(), GlbContainer.CHUNK_BIN to ByteArray(8))
        assertProblem(GlbContainer.Problem.MISALIGNED_CHUNK, bytes)
    }

    @Test fun `chunk order is enforced`() {
        assertProblem(GlbContainer.Problem.BAD_CHUNK_ORDER, glb(GlbContainer.CHUNK_BIN to ByteArray(8)))
        assertProblem(GlbContainer.Problem.BAD_CHUNK_ORDER, glb(GlbContainer.CHUNK_JSON to ByteArray(0)))
        assertProblem(GlbContainer.Problem.BAD_CHUNK_ORDER,
            glb(GlbContainer.CHUNK_JSON to "{}  ".toByteArray(), GlbContainer.CHUNK_JSON to "{}  ".toByteArray()))
        assertProblem(GlbContainer.Problem.BAD_CHUNK_ORDER,
            glb(GlbContainer.CHUNK_JSON to "{}  ".toByteArray(), 0x7777 to ByteArray(4), GlbContainer.CHUNK_BIN to ByteArray(4)))
    }

    @Test fun `bad magic, version and short input map to their problems`() {
        assertEquals(GlbContainer.Problem.TOO_SHORT, GlbContainer.check(ByteArray(11)))
        assertEquals(GlbContainer.Problem.BAD_MAGIC, GlbContainer.check(ByteArray(12)))
        assertEquals(GlbContainer.Problem.BAD_VERSION,
            GlbContainer.check(glb(GlbContainer.CHUNK_JSON to "{}  ".toByteArray(), version = 1)))
    }

    @Test fun `unaligned BIN followed by an unknown chunk is still accepted (golden compatibility)`() {
        val bytes = glb(GlbContainer.CHUNK_JSON to "{}  ".toByteArray(), GlbContainer.CHUNK_BIN to ByteArray(5), 0x7777 to ByteArray(3))
        val c = GlbContainer.parse(bytes)!!
        assertEquals(3, c.chunks.size)
        assertEquals(5, c.bin!!.length)
    }

    @Test fun `rewriteJson pads JSON with spaces and BIN with zeros, keeps other chunks, patches the length`() {
        val bytes = glb(
            GlbContainer.CHUNK_JSON to "{}  ".toByteArray(),
            GlbContainer.CHUNK_BIN to byteArrayOf(1, 2, 3, 4, 5),
            0x7777 to byteArrayOf(9),
        )
        val out = GlbContainer.parse(bytes)!!.rewriteJson("{\"a\":1}".toByteArray())
        val c = GlbContainer.parse(out)!!
        assertEquals(out.size, GlbContainer.readIntLE(out, 8))
        assertEquals("{\"a\":1} ", c.text(c.json!!))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 0, 0, 0), out.copyOfRange(c.bin!!.contentStart, c.bin!!.contentStart + c.bin!!.length))
        val other = c.chunks[2]
        assertEquals(0x7777, other.type)
        assertEquals(1, other.length)
        assertEquals(12 + 16 + 16 + 9, out.size)
    }

    @Test fun `readHead reports how much was available`() {
        val f = tmp.newFile("x.glb").also { it.writeBytes(glb(GlbContainer.CHUNK_JSON to "{}  ".toByteArray())) }
        RandomAccessFile(f, "r").use { raf ->
            val h = GlbContainer.readHead(raf)
            assertEquals(20, h.available)
            assertEquals(GlbContainer.MAGIC, h.magic)
            assertEquals(2, h.version)
            assertEquals(4, h.firstChunkLength)
            assertEquals(GlbContainer.CHUNK_JSON, h.firstChunkType)
            assertEquals(20L, raf.filePointer)
        }
        val short = tmp.newFile("s.glb").also { it.writeBytes(byteArrayOf(0x67, 0x6C, 0x54, 0x46, 2, 0)) }
        RandomAccessFile(short, "r").use { raf ->
            val h = GlbContainer.readHead(raf)
            assertEquals(6, h.available)
            h.require(4)
            assertNotNull(runCatching { h.require(8) }.exceptionOrNull() as? EOFException)
        }
    }

    @Test fun `alignment helpers`() {
        assertEquals(0, GlbContainer.alignedLength(0))
        assertEquals(4, GlbContainer.alignedLength(1))
        assertEquals(8, GlbContainer.alignedLength(8))
        assertEquals(0xFFFFFFFFL, GlbContainer.unsigned(-1))
        val same = ByteArray(4)
        assertEquals(same, GlbContainer.pad4(same, 1))
        assertArrayEquals(byteArrayOf(7, 32, 32, 32), GlbContainer.pad4(byteArrayOf(7), 32))
    }
}
