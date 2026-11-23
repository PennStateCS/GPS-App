package io.github.pennstatecs.geokit.models.glb

import java.io.EOFException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A GLB (binary glTF 2.0) container held in memory: the 12-byte header and its chunks.
 *
 * Layout: `magic "glTF" | version | total length` then chunks of `length | type | content`, all
 * little-endian. [parse] walks every chunk and stops quietly at the first one that is negative or runs
 * past the end of the data (anything after it is ignored). [rewriteJson] rebuilds the container with a
 * new JSON chunk and every other chunk copied as it is.
 *
 * [readHead] is the streaming counterpart for callers that only need the header and the first chunk
 * header without reading a whole model.
 */
public class GlbContainer private constructor(
    /** The data the chunks point into. Not copied: edits made through it show in [rewriteJson]. */
    public val bytes: ByteArray,
    public val chunks: List<Chunk>,
) {

    /** One chunk: its [type] and where its content sits in [bytes]. */
    public class Chunk(public val type: Int, public val contentStart: Int, public val length: Int)

    /** The first JSON chunk, or null. */
    public val json: Chunk? get() = chunks.firstOrNull { it.type == CHUNK_JSON }

    /** The first BIN chunk, or null. */
    public val bin: Chunk? get() = chunks.firstOrNull { it.type == CHUNK_BIN }

    /** The content of [chunk] decoded as UTF-8 (padding included). */
    public fun text(chunk: Chunk): String = String(bytes, chunk.contentStart, chunk.length, Charsets.UTF_8)

    /**
     * A new GLB with every JSON chunk replaced by [newJson] (space-padded to 4 bytes). BIN chunks are
     * zero-padded to 4 bytes; any other chunk is copied unpadded. The header's total length is the
     * number of bytes written.
     */
    public fun rewriteJson(newJson: ByteArray): ByteArray {
        val newJsonPadded = pad4(newJson, ' '.code.toByte())

        val out = ByteBuffer.allocate(
            12 + chunks.sumOf { 8 + alignedLength(if (it.type == CHUNK_JSON) newJsonPadded.size else it.length) }
        ).order(ByteOrder.LITTLE_ENDIAN)

        out.putInt(MAGIC)
        out.putInt(VERSION_2)
        val totalLenPos = out.position()
        out.putInt(0) // patched below

        for (c in chunks) {
            if (c.type == CHUNK_JSON) {
                out.putInt(newJsonPadded.size)
                out.putInt(c.type)
                out.put(newJsonPadded)
            } else {
                val content = bytes.copyOfRange(c.contentStart, c.contentStart + c.length)
                val padded = if (c.type == CHUNK_BIN) pad4(content, 0) else content
                out.putInt(padded.size)
                out.putInt(c.type)
                out.put(padded)
            }
        }
        val total = out.position()
        out.putInt(totalLenPos, total)
        return out.array().copyOf(total)
    }

    /**
     * The first up-to-20 bytes of a GLB: the header and the first chunk's header. [available] is how
     * many of them the file actually had; a field is only meaningful when [available] covers it.
     */
    public class Head internal constructor(private val b: ByteArray, public val available: Int) {
        public val magic: Int get() = readIntLE(b, 0)
        public val version: Int get() = readIntLE(b, 4)
        /** Declared total length (signed raw value; see [unsigned]). */
        public val declaredLength: Int get() = readIntLE(b, 8)
        /** First chunk's content length (signed raw value; see [unsigned]). */
        public val firstChunkLength: Int get() = readIntLE(b, 12)
        public val firstChunkType: Int get() = readIntLE(b, 16)

        /** Throws [EOFException] unless at least [bytes] header bytes were read. */
        public fun require(bytes: Int) {
            if (available < bytes) throw EOFException("Unexpected EOF reading int")
        }
    }

    public companion object {
        /** "glTF" little-endian. */
        public const val MAGIC: Int = 0x46546C67
        public const val VERSION_2: Int = 2
        /** "JSON" little-endian. */
        public const val CHUNK_JSON: Int = 0x4E4F534A
        /** "BIN\0" little-endian. */
        public const val CHUNK_BIN: Int = 0x004E4942
        /** Bytes in the header plus the first chunk header. */
        public const val HEAD_BYTES: Int = 20

        /**
         * Parses [bytes] (not copied). Null when shorter than a header, the magic is wrong or the
         * version is not 2. Chunks are read until one is negative or runs past the end.
         */
        public fun parse(bytes: ByteArray): GlbContainer? {
            if (bytes.size < 12) return null
            if (readIntLE(bytes, 0) != MAGIC) return null
            if (readIntLE(bytes, 4) != VERSION_2) return null
            // total length (skip)
            val chunks = ArrayList<Chunk>()
            var p = 12
            while (p + 8 <= bytes.size) {
                val clen = readIntLE(bytes, p)
                val ctype = readIntLE(bytes, p + 4)
                val contentStart = p + 8
                if (clen < 0 || contentStart + clen > bytes.size) break
                chunks.add(Chunk(ctype, contentStart, clen))
                p = contentStart + clen
            }
            return GlbContainer(bytes, chunks)
        }

        /** Reads up to [HEAD_BYTES] from the current position of [raf] (fewer at end of file). */
        public fun readHead(raf: RandomAccessFile): Head {
            val b = ByteArray(HEAD_BYTES)
            var n = 0
            while (n < HEAD_BYTES) {
                val r = raf.read(b, n, HEAD_BYTES - n)
                if (r < 0) break
                n += r
            }
            return Head(b, n)
        }

        /** [value] as an unsigned 32-bit length. */
        public fun unsigned(value: Int): Long = value.toLong() and 0xFFFFFFFFL

        /** [length] rounded up to a multiple of 4. */
        public fun alignedLength(length: Int): Int = (length + 3) and 3.inv()

        /** [data] padded with [pad] to a multiple of 4 bytes (the same array when already aligned). */
        public fun pad4(data: ByteArray, pad: Byte): ByteArray {
            val rem = data.size % 4
            if (rem == 0) return data
            val out = data.copyOf(data.size + (4 - rem))
            for (i in data.size until out.size) out[i] = pad
            return out
        }

        /** Little-endian Int at [at]. */
        public fun readIntLE(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or
                ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16) or
                ((b[at + 3].toInt() and 0xFF) shl 24)
    }
}
