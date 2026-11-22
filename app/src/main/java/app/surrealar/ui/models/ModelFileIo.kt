package app.surrealar.ui.models

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * File helpers shared by the model import, thumbnail and viewer screens.
 */
object ModelFileIo {

    /** Largest model file we import. Bigger GLBs tend to run tablets out of memory in Filament. */
    const val MAX_IMPORT_BYTES: Long = 150L * 1024 * 1024

    fun isTooLarge(sizeBytes: Long): Boolean = sizeBytes > MAX_IMPORT_BYTES

    /** "150 MB"-style label for [MAX_IMPORT_BYTES], for user messages. */
    fun maxImportLabel(): String = "${MAX_IMPORT_BYTES / (1024 * 1024)} MB"

    /**
     * Reads [file] straight into a new direct [ByteBuffer] (positioned at 0) without an extra
     * heap byte-array copy. Throws [IOException] if the file is too big for one buffer or
     * shrinks while reading.
     */
    @Throws(IOException::class)
    fun readToDirectBuffer(file: File): ByteBuffer {
        RandomAccessFile(file, "r").use { raf ->
            val channel = raf.channel
            val size = channel.size()
            if (size > Int.MAX_VALUE) throw IOException("File too large to load: $size bytes")
            val buf = ByteBuffer.allocateDirect(size.toInt())
            while (buf.hasRemaining()) {
                if (channel.read(buf) < 0) throw IOException("Unexpected end of file")
            }
            buf.rewind()
            return buf
        }
    }
}
