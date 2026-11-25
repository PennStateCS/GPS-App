package io.github.pennstatecs.geokit.receiver.emlid.nmea

import io.github.pennstatecs.geokit.gnss.bus.adapters.NmeaFuser
import io.github.pennstatecs.geokit.gnss.nmea.parse.SentenceParser
import io.github.pennstatecs.geokit.gnss.nmea.sentence.NmeaSentence

/**
 * Emlid Reach proprietary NMEA sentences, for geokit-gnss's extension mechanism:
 *
 * ```kotlin
 * val registry = DefaultNmeaRegistry.create(requireChecksum = true, extraParsers = EmlidNmea.parsers)
 * val replay = ReplayAdapter(scope, extraParsers = EmlidNmea.parsers)
 * // later, from the fuser / NmeaSource:
 * val emlid = EmlidNmeaDiagnostics.from(fuser.extraSentenceStats)
 * ```
 *
 * The sentences never change a fix. ETC's documented fields are in [ETC.decoded]; per-fix tilt state
 * and the position-reference rules are in the `tilt` package (`EmlidTiltTracker`, `EmlidPositionReference`).
 */
public object EmlidNmea {
    /** Base position sentence tag ([EBP]). */
    public const val TAG_EBP: String = "EBP"
    /** Tilt compensation data sentence tag ([ETC]). */
    public const val TAG_ETC: String = "ETC"
    /** [ETC] flag: the sentence carried data beyond the time (tilt compensation on in some state). */
    public const val FLAG_ORIENTATION: String = "orientation"

    /** EBP and ETC parsers keyed by tag (stateless; safe to share between registries). */
    public val parsers: Map<String, SentenceParser<out NmeaSentence>> =
        mapOf(TAG_EBP to EbpParser(), TAG_ETC to EtcParser())
}

/**
 * Typed view of the Emlid sentences in a fuser's [NmeaFuser.ExtraSentenceStats].
 *
 * @property ebpCount EBP sentences received.
 * @property etcCount ETC sentences received.
 * @property imuOrientationSeen true if any ETC carried data beyond its time (tilt compensation was on in
 *   some state) — never used for AR.
 * @property latestEtc the most recent ETC, or null.
 */
public data class EmlidNmeaDiagnostics(
    val ebpCount: Int,
    val etcCount: Int,
    val imuOrientationSeen: Boolean,
    val latestEtc: ETC?,
) {
    /** At least one EBP arrived. */
    public val ebpSeen: Boolean get() = ebpCount > 0
    /** At least one ETC arrived. */
    public val etcSeen: Boolean get() = etcCount > 0

    public companion object {
        /** Reads the EBP/ETC entries of [stats]; all zero / null when the Emlid parsers weren't registered. */
        public fun from(stats: NmeaFuser.ExtraSentenceStats): EmlidNmeaDiagnostics = EmlidNmeaDiagnostics(
            ebpCount = stats.count(EmlidNmea.TAG_EBP),
            etcCount = stats.count(EmlidNmea.TAG_ETC),
            imuOrientationSeen = stats.flagSeen(EmlidNmea.TAG_ETC, EmlidNmea.FLAG_ORIENTATION),
            latestEtc = stats.latest<ETC>(EmlidNmea.TAG_ETC),
        )
    }
}
