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
 * The sentences are diagnostics only: they never change a fix.
 */
public object EmlidNmea {
    /** Base position sentence tag ([EBP]). */
    public const val TAG_EBP: String = "EBP"
    /** Timestamp / orientation sentence tag ([ETC]). */
    public const val TAG_ETC: String = "ETC"
    /** [ETC] flag: the sentence carried an orientation/IMU-style payload. */
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
 * @property imuOrientationSeen true if any ETC carried orientation/IMU-style data (undocumented —
 *   never used for AR).
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
