package io.github.pennstatecs.geokit.models.glb

import java.io.EOFException
import java.io.RandomAccessFile

/**
 * A GLB (binary glTF 2.0) container held in memory: the 12-byte header and its chunks.
 *
 * Layout: `magic "glTF" | version | total length` then chunks of `length | type | content`, all
 * little-endian. [parse] / [check] validate the whole container before anything uses it:
 *
 * - the declared total length must equal the number of bytes;
 * - every chunk header must be complete and every chunk length (unsigned) must fit in the remaining
 *   bytes, so the chunks tile the data exactly with nothing left over;
 * - the first chunk must be a non-empty JSON chunk whose length is a multiple of 4 (so the next chunk
 *   starts 4-byte aligned), there is only one JSON chunk, and a BIN chunk, if any, is the second chunk.
 *
 * Compatibility: a BIN chunk whose length is not a multiple of 4, and unknown chunks after it, are
 * accepted as before (some exporters write them; [rewriteJson] pads the BIN on output). All offset
 * arithmetic is done in `Long`, so lengths near or above 2^31 are rejected rather than wrapping.
 *
 * [rewriteJson] rebuilds the container with a new JSON chunk and every other chunk copied as it is.
 * [readHead] is the streaming counterpart for callers that only need the header and the first chunk
 * header without reading a whole model.
 */
public class GlbContainer private constructor(
    /** The data the chunks point into. Not copied. */
    public val bytes: ByteArray,
    public val chunks: List<Chunk>,
) {

    /** One chunk: its [type] and where its content sits in [bytes]. */
    public class Chunk(public val type: Int, public val contentStart: Int, public val length: Int)

    /** Why [check] refused a byte array. */
    public enum class Problem {
        /** Fewer bytes than a header plus one chunk header. */
        TOO_SHORT,
        BAD_MAGIC,
        BAD_VERSION,
        /** The header's total length is not the number of bytes. */
        LENGTH_MISMATCH,
        /** A chunk header is incomplete, or a chunk length runs past the end. */
        BAD_CHUNK_LENGTH,
        /** The JSON chunk's length is not a multiple of 4. */
        MISALIGNED_CHUNK,
        /** The first chunk is not a non-empty JSON chunk, there are two JSON chunks, or BIN is not second. */
        BAD_CHUNK_ORDER,
    }

    /** The JSON chunk (always the first chunk of a parsed container). */
    public val json: Chunk? get() = chunks.firstOrNull { it.type == CHUNK_JSON }

    /** The BIN chunk (the second chunk when present), or null. */
    public val bin: Chunk? get() = chunks.firstOrNull { it.type == CHUNK_BIN }

    /** The content of [chunk] decoded as UTF-8 (padding included). */
    public fun text(chunk: Chunk): String = String(bytes, chunk.contentStart, chunk.length, Charsets.UTF_8)

    /**
     * A new GLB with the JSON chunk replaced by [newJson] (space-padded to 4 bytes). BIN chunks are
     * zero-padded to 4 bytes; any other chunk is copied unpadded. The header's total length is the
     * number of bytes written. Throws [IllegalArgumentException] if the result would exceed 2 GB.
     */
    public fun rewriteJson(newJson: ByteArray): ByteArray {
        val jsonPadded = alignedLong(newJson.size.toLong())
        var total = 12L
        for (c in chunks) {
            total += 8 + when (c.type) {
                CHUNK_JSON -> jsonPadded
                CHUNK_BIN -> alignedLong(c.length.toLong())
                else -> c.length.toLong()
            }
        }
        require(total <= MAX_ARRAY_BYTES) { "Rewritten GLB would be $total bytes" }

        val out = ByteArray(total.toInt())
        writeIntLE(out, 0, MAGIC)
        writeIntLE(out, 4, VERSION_2)
        writeIntLE(out, 8, total.toInt())
        var p = 12
        for (c in chunks) {
            when (c.type) {
                CHUNK_JSON -> {
                    writeIntLE(out, p, jsonPadded.toInt()); writeIntLE(out, p + 4, c.type)
                    System.arraycopy(newJson, 0, out, p + 8, newJson.size)
                    for (i in p + 8 + newJson.size until p + 8 + jsonPadded.toInt()) out[i] = SPACE
                    p += 8 + jsonPadded.toInt()
                }
                CHUNK_BIN -> {
                    val padded = alignedLong(c.length.toLong()).toInt()
                    writeIntLE(out, p, padded); writeIntLE(out, p + 4, c.type)
                    System.arraycopy(bytes, c.contentStart, out, p + 8, c.length)   // padding stays 0
                    p += 8 + padded
                }
                else -> {
                    writeIntLE(out, p, c.length); writeIntLE(out, p + 4, c.type)
                    System.arraycopy(bytes, c.contentStart, out, p + 8, c.length)
                    p += 8 + c.length
                }
            }
        }
        return out
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

        /** Largest array the JVM reliably allocates. */
        private const val MAX_ARRAY_BYTES = Int.MAX_VALUE - 8L
        private const val SPACE = ' '.code.toByte()

        /**
         * Parses [bytes] (not copied). Null when [check] finds any problem: the container is either
         * fully valid or not returned at all.
         */
        public fun parse(bytes: ByteArray): GlbContainer? {
            val chunks = ArrayList<Chunk>()
            if (walk(bytes, chunks) != null) return null
            return GlbContainer(bytes, chunks)
        }

        /** The first structural problem in [bytes], or null for a valid container. */
        public fun check(bytes: ByteArray): Problem? = walk(bytes, null)

        private fun walk(bytes: ByteArray, into: MutableList<Chunk>?): Problem? {
            val size = bytes.size.toLong()
            if (size < 12) return Problem.TOO_SHORT
            if (readIntLE(bytes, 0) != MAGIC) return Problem.BAD_MAGIC
            if (readIntLE(bytes, 4) != VERSION_2) return Problem.BAD_VERSION
            if (unsigned(readIntLE(bytes, 8)) != size) return Problem.LENGTH_MISMATCH
            if (size < HEAD_BYTES) return Problem.TOO_SHORT
            var p = 12L
            var index = 0
            while (p < size) {
                if (size - p < 8) return Problem.BAD_CHUNK_LENGTH
                val clen = unsigned(readIntLE(bytes, p.toInt()))
                val ctype = readIntLE(bytes, p.toInt() + 4)
                val contentStart = p + 8
                if (clen > size - contentStart) return Problem.BAD_CHUNK_LENGTH
                when {
                    index == 0 && (ctype != CHUNK_JSON || clen == 0L) -> return Problem.BAD_CHUNK_ORDER
                    index == 0 && clen % 4 != 0L -> return Problem.MISALIGNED_CHUNK
                    index > 0 && ctype == CHUNK_JSON -> return Problem.BAD_CHUNK_ORDER
                    index > 1 && ctype == CHUNK_BIN -> return Problem.BAD_CHUNK_ORDER
                }
                into?.add(Chunk(ctype, contentStart.toInt(), clen.toInt()))
                p = contentStart + clen
                index++
            }
            return null
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

        /** [length] rounded up to a multiple of 4, without overflow. */
        private fun alignedLong(length: Long): Long = (length + 3) and 3L.inv()

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

        private fun writeIntLE(b: ByteArray, at: Int, v: Int) {
            b[at] = v.toByte()
            b[at + 1] = (v ushr 8).toByte()
            b[at + 2] = (v ushr 16).toByte()
            b[at + 3] = (v ushr 24).toByte()
        }
    }
}
