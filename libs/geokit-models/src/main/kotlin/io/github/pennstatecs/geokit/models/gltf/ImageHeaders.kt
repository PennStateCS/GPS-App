package io.github.pennstatecs.geokit.models.gltf

/** Image dimensions from the first bytes of a PNG, JPEG, WebP or KTX2 file. */
internal object ImageHeaders {
    fun dimensions(b: ByteArray): Pair<Int, Int>? = png(b) ?: jpeg(b) ?: webp(b) ?: ktx2(b)

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun be32(b: ByteArray, i: Int) = (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
    private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun le24(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16)
    private fun le32(b: ByteArray, i: Int) = le24(b, i) or (u8(b, i + 3) shl 24)
    private fun ascii(b: ByteArray, i: Int, s: String) =
        b.size >= i + s.length && s.indices.all { b[i + it].toInt() == s[it].code }

    private fun valid(w: Int, h: Int) = if (w > 0 && h > 0) w to h else null

    private fun png(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 24 || u8(b, 0) != 0x89 || !ascii(b, 1, "PNG") || !ascii(b, 12, "IHDR")) return null
        return valid(be32(b, 16), be32(b, 20))
    }

    private fun jpeg(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 4 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) return null
        var i = 2
        while (i + 3 < b.size) {
            if (u8(b, i) != 0xFF) { i++; continue }
            val marker = u8(b, i + 1)
            when {
                marker == 0xFF -> { i++; continue }
                marker == 0x01 || marker in 0xD0..0xD9 -> { i += 2; continue }
            }
            val len = be16(b, i + 2)
            val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isSof) {
                if (i + 8 >= b.size) return null
                return valid(be16(b, i + 7), be16(b, i + 5))
            }
            if (marker == 0xDA || len < 2) return null
            i += 2 + len
        }
        return null
    }

    private fun webp(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 30 || !ascii(b, 0, "RIFF") || !ascii(b, 8, "WEBP")) return null
        return when {
            ascii(b, 12, "VP8 ") -> valid(le16(b, 26) and 0x3FFF, le16(b, 28) and 0x3FFF)
            ascii(b, 12, "VP8L") -> {
                val bits = le32(b, 21)
                valid((bits and 0x3FFF) + 1, ((bits ushr 14) and 0x3FFF) + 1)
            }
            ascii(b, 12, "VP8X") -> valid(le24(b, 24) + 1, le24(b, 27) + 1)
            else -> null
        }
    }

    private fun ktx2(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 28 || u8(b, 0) != 0xAB || !ascii(b, 1, "KTX 20")) return null
        return valid(le32(b, 20), maxOf(1, le32(b, 24)))
    }
}
