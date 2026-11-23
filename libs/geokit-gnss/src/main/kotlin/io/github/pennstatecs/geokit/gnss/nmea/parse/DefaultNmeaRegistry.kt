package io.github.pennstatecs.geokit.gnss.nmea.parse

import io.github.pennstatecs.geokit.gnss.nmea.sentence.NmeaSentence

/**
 * Builds an [NmeaRegistry] with every standard sentence parser in this library, plus any extra parsers
 * the host supplies (receiver-specific / proprietary sentences from a receiver module).
 *
 * Extension: a receiver module writes a [SentenceParser] per proprietary sentence (its sentence type
 * implements [NmeaSentence], optionally [io.github.pennstatecs.geokit.gnss.nmea.sentence.FlaggedNmeaSentence])
 * and hands the map to [create] (or `ReplayAdapter(extraParsers = …)`). Those sentences never feed a
 * fix: [io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaFuser] counts them per tag and keeps the
 * latest of each for diagnostics (`NmeaFuser.extraSentenceStats`).
 */
public object DefaultNmeaRegistry {

    /** The standard parsers by tag: GGA, RMC, GSA, GSV, ZDA, GST and MediaTek `$..ACCURACY`. */
    public fun standardParsers(): Map<String, SentenceParser<out NmeaSentence>> = mapOf(
        "GGA" to GgaParser(),
        "RMC" to RmcParser(),
        "GSA" to GsaParser(),
        "GSV" to GsvParser(),
        "ZDA" to ZdaParser(),
        "GST" to GstParser(),
        // MediaTek proprietary accuracy (e.g. Samsung SM-X940 internal GNSS) — fallback when no GST.
        "ACCURACY" to AccuracyParser(),
    )

    /**
     * A registry with the [standardParsers] plus [extraParsers].
     * @param verifyChecksum reject lines whose `*hh` checksum doesn't match.
     * @param requireChecksum also reject lines with no checksum (live streams); false for log files.
     * @param extraParsers additional parsers keyed by sentence tag (the part after the two-letter
     *   talker, uppercase).
     * @param allowStandardOverride standard tags that [extraParsers] may deliberately replace (e.g.
     *   `setOf("GST")` for a receiver whose GST needs a custom parser). Replacing GGA/RMC/GST changes how
     *   fixes are built, so it is never implicit.
     * @throws IllegalArgumentException if an extra parser uses a standard tag (compared ignoring case)
     *   that is not in [allowStandardOverride], or if [allowStandardOverride] names a tag that is not a
     *   standard tag or has no extra parser (a typo would otherwise silently do nothing).
     */
    public fun create(
        verifyChecksum: Boolean = true,
        requireChecksum: Boolean = false,
        extraParsers: Map<String, SentenceParser<out NmeaSentence>> = emptyMap(),
        allowStandardOverride: Set<String> = emptySet(),
    ): NmeaRegistry {
        val standard = standardParsers()
        checkOverrides(standard.keys, extraParsers.keys, allowStandardOverride)
        // The registry looks tags up in uppercase, so key the extras the same way (a lowercase key would
        // otherwise be unreachable and an allowed override would silently leave the standard parser).
        val extraUpper = extraParsers.mapKeys { it.key.uppercase() }
        require(extraUpper.size == extraParsers.size) { "extraParsers has tags that differ only in case: ${extraParsers.keys.sorted()}" }
        return NmeaRegistry(standard + extraUpper, verifyChecksum, requireChecksum)
    }

    private fun checkOverrides(standard: Set<String>, extra: Set<String>, allowed: Set<String>) {
        val standardUpper = standard.map { it.uppercase() }.toSet()
        val extraUpper = extra.map { it.uppercase() }.toSet()
        val allowedUpper = allowed.map { it.uppercase() }.toSet()

        val unknownAllowed = allowedUpper - standardUpper
        require(unknownAllowed.isEmpty()) {
            "allowStandardOverride names non-standard tag(s) ${unknownAllowed.sorted()}; standard tags are ${standardUpper.sorted()}"
        }
        val unusedAllowed = allowedUpper - extraUpper
        require(unusedAllowed.isEmpty()) {
            "allowStandardOverride names tag(s) ${unusedAllowed.sorted()} with no extra parser"
        }
        val collisions = (extraUpper intersect standardUpper) - allowedUpper
        require(collisions.isEmpty()) {
            "Extra NMEA parser(s) ${collisions.sorted()} would replace standard parser(s); " +
                "pass allowStandardOverride = setOf(${collisions.sorted().joinToString { "\"$it\"" }}) if that is intended"
        }
    }
}
