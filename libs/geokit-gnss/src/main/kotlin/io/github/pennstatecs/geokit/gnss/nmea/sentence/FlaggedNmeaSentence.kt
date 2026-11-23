package io.github.pennstatecs.geokit.gnss.nmea.sentence

/**
 * Optional extension of [NmeaSentence] for sentences that do not feed fixes but carry a diagnostic
 * property worth remembering across the stream, such as "this message had a payload".
 *
 * [io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaFuser] keeps, per sentence tag, the union of
 * every flag it has seen (`NmeaFuser.ExtraSentenceStats.flagsByTag`), so a flag stays set even when
 * later sentences of the same tag do not carry it.
 */
public interface FlaggedNmeaSentence : NmeaSentence {
    /** Flags this sentence carries; empty when none. Names are chosen by the sentence's module. */
    public val diagnosticFlags: Set<String>
}
