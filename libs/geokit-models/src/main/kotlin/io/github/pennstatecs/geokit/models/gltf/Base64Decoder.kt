package io.github.pennstatecs.geokit.models.gltf

/**
 * Standard-alphabet base64 decoding with the same leniency as Android's
 * `android.util.Base64.decode(s, Base64.DEFAULT)`: any byte outside the alphabet (whitespace, line
 * breaks, other punctuation, non-ASCII) is skipped; `=` padding is optional at the end, but data after
 * it, `=` in the first two positions of a quantum, or a lone trailing character are errors.
 *
 * Exists because the app's minSdk (24) predates `java.util.Base64` (API 26), and this module must not
 * use Android classes.
 */
public object Base64Decoder {

    private const val SKIP = -1
    private const val EQUALS = -2

    private val DECODE = IntArray(256) { SKIP }.also { t ->
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        for (i in alphabet.indices) t[alphabet[i].code] = i
        t['='.code] = EQUALS
    }

    /**
     * Decodes [text] (as its UTF-8 bytes, like Android). Throws [IllegalArgumentException] on bad
     * base64, as Android does.
     */
    public fun decode(text: String): ByteArray {
        val input = text.toByteArray(Charsets.UTF_8)
        val out = ByteArray(input.size * 3 / 4 + 3)
        var op = 0
        var state = 0
        var value = 0
        for (byte in input) {
            val d = DECODE[byte.toInt() and 0xFF]
            when (state) {
                0 -> if (d >= 0) { value = d; state = 1 } else if (d != SKIP) bad()
                1 -> if (d >= 0) { value = (value shl 6) or d; state = 2 } else if (d != SKIP) bad()
                2 -> when {
                    d >= 0 -> { value = (value shl 6) or d; state = 3 }
                    d == EQUALS -> { out[op++] = (value shr 4).toByte(); state = 4 }
                    d != SKIP -> bad()
                }
                3 -> when {
                    d >= 0 -> {
                        value = (value shl 6) or d
                        out[op++] = (value shr 16).toByte()
                        out[op++] = (value shr 8).toByte()
                        out[op++] = value.toByte()
                        state = 0
                    }
                    d == EQUALS -> {
                        out[op++] = (value shr 10).toByte()
                        out[op++] = (value shr 2).toByte()
                        state = 5
                    }
                    d != SKIP -> bad()
                }
                4 -> if (d == EQUALS) state = 5 else if (d != SKIP) bad()
                5 -> if (d != SKIP) bad()
            }
        }
        when (state) {
            1, 4 -> bad()
            2 -> out[op++] = (value shr 4).toByte()
            3 -> {
                out[op++] = (value shr 10).toByte()
                out[op++] = (value shr 2).toByte()
            }
        }
        return out.copyOf(op)
    }

    private fun bad(): Nothing = throw IllegalArgumentException("bad base-64")
}
