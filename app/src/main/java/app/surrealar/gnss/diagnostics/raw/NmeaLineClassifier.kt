package app.surrealar.gnss.diagnostics.raw

/**
 * Read-only classification of one recorded NMEA line for the raw-recording statistics. It never changes
 * the line and is independent of the app's NMEA parsers (geokit-gnss) — it only looks at the text, so a
 * sentence the parsers reject is still counted (and recorded) exactly as received.
 *
 * - Type: talker-agnostic, from the address field (`$GNGGA`, `$GPGGA` → GGA; `$GNETC` → ETC). A proprietary
 *   address (`$P…`) is [SentenceType.OTHER].
 * - Checksum: XOR of the characters between `$` and `*`, compared with the two hex digits after `*`.
 * - Malformed: not `$`-prefixed, no address, non-printable / non-ASCII characters, or no `*hh` ending.
 *   A malformed line is not checksum-checked (it is counted as malformed only).
 */
object NmeaLineClassifier {

    /** Sentence types counted separately; everything else is [OTHER]. */
    enum class SentenceType { GGA, RMC, GST, ETC, EBP, OTHER }

    /** Checksum outcome of a structurally valid line. */
    enum class ChecksumStatus { VALID, INVALID, MALFORMED }

    data class Result(val type: SentenceType, val checksum: ChecksumStatus)

    fun classify(line: String): Result = Result(typeOf(line), checksumOf(line))

    /** Talker-agnostic sentence type from the address field (between `$` and the first `,` or `*`). */
    fun typeOf(line: String): SentenceType {
        if (line.isEmpty() || line[0] != '$') return SentenceType.OTHER
        var end = 1
        while (end < line.length && line[end] != ',' && line[end] != '*') end++
        val address = line.substring(1, end)
        // $ttSSS: two-character talker + formatter. Proprietary ($P...) is OTHER.
        if (address.length != 5 || address[0] == 'P') return SentenceType.OTHER
        return when (address.substring(2)) {
            "GGA" -> SentenceType.GGA
            "RMC" -> SentenceType.RMC
            "GST" -> SentenceType.GST
            "ETC" -> SentenceType.ETC
            "EBP" -> SentenceType.EBP
            else -> SentenceType.OTHER
        }
    }

    /** Verifies the checksum on the text only; the caller's line is not modified. */
    fun checksumOf(line: String): ChecksumStatus {
        if (line.length < 4 || line[0] != '$') return ChecksumStatus.MALFORMED
        for (c in line) if (c < ' ' || c > '~') return ChecksumStatus.MALFORMED
        val star = line.lastIndexOf('*')
        // Exactly two hex digits after the last '*', and an address of at least one character before it.
        if (star < 2 || star != line.length - 3) return ChecksumStatus.MALFORMED
        val hi = hex(line[star + 1])
        val lo = hex(line[star + 2])
        if (hi < 0 || lo < 0) return ChecksumStatus.MALFORMED
        val expected = hi * 16 + lo
        var sum = 0
        for (i in 1 until star) sum = sum xor line[i].code
        return if (sum == expected) ChecksumStatus.VALID else ChecksumStatus.INVALID
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'F' -> c - 'A' + 10
        in 'a'..'f' -> c - 'a' + 10
        else -> -1
    }
}
