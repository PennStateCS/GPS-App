package app.surrealar.ui.home

import app.surrealar.gnss.format.GnssStatusFormatter
import app.surrealar.receiver.tilt.EpochReference
import app.surrealar.settings.model.CompensatedOutputSetting
import app.surrealar.ui.home.HomeLiveSource.Link
import app.surrealar.ui.home.HomeLiveSource.Live
import io.github.pennstatecs.geokit.gnss.model.RtkStatus
import java.util.Locale

/**
 * Pure mapper: live GNSS fix/source/stream data → a plain-language "Field Status" summary for the Home dashboard.
 * No Android types, so it is unit-tested through [map] with real [Inputs].
 *
 * It is a *summary*, not a second toolbar: it reuses [GnssStatusFormatter] for the source/fix/accuracy wording so
 * the card never drifts from the app-wide GNSS toolbar. The classification is deliberately conservative: RTK Fixed
 * is never treated as proven centimetre accuracy, an unknown or single-point solution is never "ready", and a
 * reference or vertical warning always outranks a positive quality headline.
 *
 * ## Precedence (first matching row wins; later warnings still appear in [FieldStatus.additionalWarnings])
 *
 * | # | Condition                                                        | State                  | Headline                          | Severity |
 * |---|------------------------------------------------------------------|------------------------|-----------------------------------|----------|
 * | 1 | Tablet GNSS selected, no live tablet fix                         | WAITING                | Waiting for position              | NEUTRAL  |
 * | 2 | External selected, no live external fix, link connecting         | CONNECTING             | Connecting to receiver            | NEUTRAL  |
 * | 3 | External selected, no live external fix, link reconnecting       | RECONNECTING           | Reconnecting to receiver          | CAUTION  |
 * | 4 | External selected, no live external fix, link up                 | WAITING                | Waiting for position              | CAUTION  |
 * | 5 | External selected, no live external fix, link unavailable        | NO_RECEIVER_DATA       | No receiver data                  | WARNING  |
 * | 6 | Live tablet GNSS fix                                             | INTERNAL_GPS           | Using tablet GNSS                 | NEUTRAL  |
 * | 7 | Live NMEA replay fix                                             | REPLAY                 | NMEA replay                       | NEUTRAL  |
 * | 8 | External fix, correction age > [STALE_CORRECTION_S]              | CORRECTIONS_STALE      | Corrections stale                 | WARNING  |
 * | 9 | External fix, RS4 reference may be compensated / unconfirmed     | REFERENCE_UNCONFIRMED  | Position reference unconfirmed    | WARNING / CAUTION |
 * |10 | External fix, receiver vertical accuracy is an invalid marker    | VERTICAL_INVALID       | Vertical accuracy invalid         | CAUTION  |
 * |11 | RTK Fixed, reported hAcc ≤ [HIGH_ACCURACY_H_M]                    | HIGH_ACCURACY          | High accuracy                     | GOOD     |
 * |12 | RTK Fixed, hAcc missing or estimated                             | FIX_UNVERIFIED         | RTK Fixed — accuracy unverified   | CAUTION  |
 * |13 | RTK Fixed, reported hAcc > [HIGH_ACCURACY_H_M]                    | ACCURACY_BELOW_TARGET  | Accuracy below target             | CAUTION  |
 * |14 | RTK Float                                                        | FLOAT                  | RTK Float                         | CAUTION  |
 * |15 | Other solution (Single/DGPS/DR/none), reported hAcc > [LOW_ACCURACY_M] | LOW_ACCURACY     | Low accuracy                      | WARNING  |
 * |16 | Other solution (Single/DGPS/DR/none)                             | POSITION_AVAILABLE     | Position available                | NEUTRAL  |
 *
 * Only row 11 is GOOD (green), and only when rows 8–10 do not apply. "No live fix" (rows 1–5) means no valid fix
 * from the SELECTED source: a tablet GNSS fix while an external receiver is selected is not shown as the receiver's.
 * External identity (reported name / model) and RTK metrics are attached only to a live external fix.
 * Callers map [Severity] → colours/icons; the headline text always carries the meaning on its own.
 */
object HomeFieldStatusMapper {

    /** Reported horizontal accuracy (m, 68 % radius) above which a non-RTK fix is flagged "Low accuracy". */
    const val LOW_ACCURACY_M = 1.0

    /**
     * Reported horizontal accuracy (m, 68 % radius) at or below which an RTK Fixed solution reads "High accuracy".
     * Only a receiver-REPORTED value counts (GST / $..ACCURACY); a missing or estimated value never does.
     */
    const val HIGH_ACCURACY_H_M = 0.05

    /** Correction age (s) above which RTK corrections are considered stale (matches status bar). */
    const val STALE_CORRECTION_S = 15.0

    /** Field-status state shown on the Home card (see the precedence table in the class KDoc). */
    enum class State {
        WAITING, CONNECTING, RECONNECTING, NO_RECEIVER_DATA, INTERNAL_GPS, REPLAY,
        CORRECTIONS_STALE, REFERENCE_UNCONFIRMED, VERTICAL_INVALID,
        HIGH_ACCURACY, FIX_UNVERIFIED, ACCURACY_BELOW_TARGET, FLOAT, LOW_ACCURACY, POSITION_AVAILABLE,
    }

    /** Semantic severity for accents (color + icon); never the *only* signal — text always says it. */
    enum class Severity { GOOD, CAUTION, WARNING, NEUTRAL }

    /** Where the card's header action navigates. */
    enum class Action { OPEN_MAP, RECEIVER_SETTINGS }

    /** RS4 / RS4 Pro: which point the live positions are (Phase 4 position reference). */
    enum class PositionReference { POLE_TIP, ANTENNA, UNCONFIRMED, MAY_BE_COMPENSATED }

    /** A compact metadata chip, e.g. "Sats 29/29", "RTK 1s", "Errors 3". */
    data class Chip(val label: String, val severity: Severity = Severity.NEUTRAL)

    /**
     * Immutable Field Status summary for the Home card: a headline, the other active warnings, two detail lines, and
     * an ordered chip row, plus a severity and an action.
     */
    data class FieldStatus(
        val state: State,
        val headline: String,
        /** Always present. e.g. "RS2+ · Float · H ±0.04 m · V ±0.06 m". */
        val primaryDetail: String,
        /** Always present. e.g. "Reach6 · Emlid Reach RS2+", "Selected: Reach6" or "Tablet GNSS receiver". */
        val receiverDetail: String,
        /** Ordered compact chips; never empty (a placeholder/"No fix" chip is used when bare). */
        val chips: List<Chip>,
        val severity: Severity,
        val actionLabel: String,
        val actionDestination: Action,
        /** Other warnings that apply but lost to the headline in the precedence order (empty when none). */
        val additionalWarnings: List<String> = emptyList(),
    )

    /**
     * All inputs the card needs, gathered by the caller (it owns "now" / the live flows) so this function stays pure.
     * [isInternal] = the SELECTED source is tablet GNSS. [hasRecentFix] = the live fix has valid coordinates.
     * [live] / [link] = which source actually produced that fix and the external link state ([HomeLiveSource]);
     * their defaults describe a fix from the selected source with no transition (convenient for tests).
     */
    data class Inputs(
        val isInternal: Boolean,
        val externalLabel: String,
        /** Receiver name: the receiver-reported name when confirmed for the live receiver, else the saved name. */
        val receiverName: String?,
        val connectionAddress: String?,
        val rtkStatus: RtkStatus,
        val hAccM: Double?,
        val vAccM: Double?,
        val satsUsed: Int?,
        val satsVisible: Int?,
        /** Live sky counts from SkyBus (same source the app-wide toolbar prefers); 0 when unknown. */
        val skyTotalUsed: Int = 0,
        val skyTotalVisible: Int = 0,
        val hdop: Double?,
        val pdop: Double?,
        val correctionAgeS: Double?,
        val correctionStationId: String?,
        val nmeaLinesPerSecond: Double?,
        /** NMEA parse errors in the recent window ([RecentCountWindow]), not the cumulative Developer total. */
        val nmeaParseErrors: Long,
        val hasRecentFix: Boolean,
        /** Model the receiver itself reported and the app recognised (e.g. "Emlid Reach RS2+"); null = not reported. */
        val receiverModel: String? = null,
        /** The vertical accuracy is an invalid marker (e.g. Emlid 999 m when receiver height corrections stop). */
        val vAccInvalid: Boolean = false,
        /** RS4 / RS4 Pro only: which point the positions are; null = not applicable. */
        val positionReference: PositionReference? = null,
        /** [hAccM] is an estimate (e.g. from DOP / UERE), not reported by the receiver. Never counts as verified. */
        val hAccEstimated: Boolean = false,
        val live: Live = when {
            !hasRecentFix -> Live.NONE
            isInternal -> Live.INTERNAL
            else -> Live.EXTERNAL
        },
        val link: Link = if (isInternal) Link.NOT_APPLICABLE else Link.CONNECTED,
    )

    fun map(i: Inputs): FieldStatus {
        val source = GnssStatusFormatter.formatSource(i.isInternal, i.externalLabel)
        val openMap = "Open Map"
        val receiverSettings = "Receiver Settings"

        // Rows 1–5: no valid live fix from the SELECTED source.
        val liveFromSelected = when {
            i.isInternal -> i.live == Live.INTERNAL || i.live == Live.REPLAY
            else -> i.live == Live.EXTERNAL || i.live == Live.REPLAY
        }
        if (!i.hasRecentFix || !liveFromSelected) {
            if (i.isInternal) {
                return FieldStatus(
                    State.WAITING, "Waiting for position",
                    "$source · acquiring…", INTERNAL_RECEIVER_DETAIL,
                    listOf(Chip("No fix")),
                    Severity.NEUTRAL, openMap, Action.OPEN_MAP,
                )
            }
            val selected = selectedReceiverDetail(i)
            return when (i.link) {
                Link.CONNECTING -> FieldStatus(
                    State.CONNECTING, "Connecting to receiver", "$source · connecting…", selected,
                    listOf(Chip("No fix")), Severity.NEUTRAL, receiverSettings, Action.RECEIVER_SETTINGS,
                )
                Link.RECONNECTING -> FieldStatus(
                    State.RECONNECTING, "Reconnecting to receiver", "$source · link lost, retrying…", selected,
                    listOf(Chip("No fix", Severity.CAUTION)), Severity.CAUTION, receiverSettings, Action.RECEIVER_SETTINGS,
                )
                Link.CONNECTED -> FieldStatus(
                    State.WAITING, "Waiting for position", "$source · connected, no position yet", selected,
                    listOf(Chip("No fix", Severity.CAUTION)), Severity.CAUTION, receiverSettings, Action.RECEIVER_SETTINGS,
                )
                Link.UNAVAILABLE, Link.NOT_APPLICABLE -> FieldStatus(
                    State.NO_RECEIVER_DATA, "No receiver data", "$source · waiting for live position", selected,
                    // The absent stream is conveyed by the headline — not a misleading "NMEA 0/s" chip.
                    listOf(Chip("No fix", Severity.WARNING), Chip("Check receiver", Severity.WARNING)),
                    Severity.WARNING, receiverSettings, Action.RECEIVER_SETTINGS,
                )
            }
        }

        // Row 6: tablet GNSS — informational, not RTK-graded.
        if (i.live == Live.INTERNAL) {
            return FieldStatus(
                State.INTERNAL_GPS, "Using tablet GNSS",
                buildPrimaryDetail(source, i, internal = true), INTERNAL_RECEIVER_DETAIL, buildInternalChips(i),
                Severity.NEUTRAL, openMap, Action.OPEN_MAP,
            )
        }
        // Row 7: replay — informational; no receiver identity is attached.
        if (i.live == Live.REPLAY) {
            return FieldStatus(
                State.REPLAY, "NMEA replay",
                buildPrimaryDetail("Replay", i, internal = false), "Recorded NMEA (not a live receiver)",
                buildExternalChipsBase(i), Severity.NEUTRAL, openMap, Action.OPEN_MAP,
            )
        }

        // Rows 8–16: a live fix from the selected external receiver.
        val primary = buildPrimaryDetail(source, i, internal = false)
        val receiverDetail = liveReceiverDetail(i)
        val chips = buildExternalChips(i)

        val warnings = mutableListOf<Triple<State, String, Severity>>()
        if (correctionsStale(i)) warnings += Triple(State.CORRECTIONS_STALE, "Corrections stale", Severity.WARNING)
        when (i.positionReference) {
            PositionReference.MAY_BE_COMPENSATED ->
                warnings += Triple(State.REFERENCE_UNCONFIRMED, "Position reference unconfirmed", Severity.WARNING)
            PositionReference.UNCONFIRMED ->
                warnings += Triple(State.REFERENCE_UNCONFIRMED, "Position reference unconfirmed", Severity.CAUTION)
            else -> Unit
        }
        if (i.vAccInvalid) warnings += Triple(State.VERTICAL_INVALID, "Vertical accuracy invalid", Severity.CAUTION)

        warnings.firstOrNull()?.let { (state, headline, severity) ->
            return FieldStatus(state, headline, primary, receiverDetail, chips, severity, openMap, Action.OPEN_MAP,
                additionalWarnings = warnings.drop(1).map { it.second })
        }

        val reportedH = i.hAccM?.takeIf { !i.hAccEstimated && it.isFinite() && it >= 0.0 }
        val (state, headline, severity) = when (i.rtkStatus) {
            RtkStatus.FIX -> when {
                reportedH == null -> Triple(State.FIX_UNVERIFIED, "RTK Fixed — accuracy unverified", Severity.CAUTION)
                reportedH <= HIGH_ACCURACY_H_M -> Triple(State.HIGH_ACCURACY, "High accuracy", Severity.GOOD)
                else -> Triple(State.ACCURACY_BELOW_TARGET, "Accuracy below target", Severity.CAUTION)
            }
            RtkStatus.FLOAT -> Triple(State.FLOAT, "RTK Float", Severity.CAUTION)
            else -> if (reportedH != null && reportedH > LOW_ACCURACY_M)
                Triple(State.LOW_ACCURACY, "Low accuracy", Severity.WARNING)
            else Triple(State.POSITION_AVAILABLE, "Position available", Severity.NEUTRAL)
        }
        return FieldStatus(state, headline, primary, receiverDetail, chips, severity, openMap, Action.OPEN_MAP)
    }

    /**
     * RS4 / RS4 Pro position reference for Home from the tilt monitor's state; null when not applicable (other
     * receivers, tablet GNSS). Unknown compensated output with no epoch classification reads "unconfirmed".
     */
    fun positionReferenceOf(
        applicable: Boolean,
        reference: EpochReference?,
        compensatedOutput: CompensatedOutputSetting,
    ): PositionReference? {
        if (!applicable) return null
        return when (reference) {
            EpochReference.POLE_TIP -> PositionReference.POLE_TIP
            EpochReference.ANTENNA -> PositionReference.ANTENNA
            EpochReference.MAY_BE_COMPENSATED -> PositionReference.MAY_BE_COMPENSATED
            EpochReference.ANTENNA_UNCERTAIN -> PositionReference.UNCONFIRMED
            EpochReference.NOT_APPLICABLE, null ->
                if (compensatedOutput == CompensatedOutputSetting.UNKNOWN) PositionReference.UNCONFIRMED else null
        }
    }

    private const val INTERNAL_RECEIVER_DETAIL = "Tablet GNSS receiver"

    private fun correctionsStale(i: Inputs): Boolean = i.correctionAgeS != null && i.correctionAgeS > STALE_CORRECTION_S

    // ── Detail lines ──────────────────────────────────────────────────────────────────────────

    private fun buildPrimaryDetail(source: String, i: Inputs, internal: Boolean): String {
        val parts = mutableListOf(source, GnssStatusFormatter.formatFixStatus(i.rtkStatus, internal))
        i.hAccM?.let { parts += "H " + GnssStatusFormatter.formatAccuracyMeters(it) + if (i.hAccEstimated) " (est.)" else "" }
        // Vertical accuracy is meaningful for external RTK; tablet GNSS shows horizontal only.
        if (!internal) {
            if (i.vAccInvalid) parts += "V invalid"   // never show a 999 m marker as an accuracy
            else i.vAccM?.let { parts += "V " + GnssStatusFormatter.formatAccuracyMeters(it) }
        }
        return parts.joinToString(" · ")
    }

    /** A saved name that is just the address (older builds stored the host as the name) is not repeated. */
    private fun displayName(i: Inputs): String? {
        val addr = i.connectionAddress?.takeIf { it.isNotBlank() }
        return i.receiverName?.trim()?.takeIf { n ->
            n.isNotBlank() && addr?.let { a -> n != a && n != a.substringBeforeLast(':') } != false
        }
    }

    /**
     * Live receiver identity, concise: name + receiver-reported model; the address only when no name is known (it is
     * then the only identity), so the IP is never repeated next to a name.
     */
    private fun liveReceiverDetail(i: Inputs): String {
        val name = displayName(i)
        val model = i.receiverModel?.takeIf { it.isNotBlank() && it != name }
        val parts = listOfNotNull(name ?: i.connectionAddress?.takeIf { it.isNotBlank() }, model)
        return if (parts.isEmpty()) "External receiver" else parts.joinToString(" · ")
    }

    /** No live fix from the receiver yet: the saved receiver is only "selected" (not proof it is streaming). */
    private fun selectedReceiverDetail(i: Inputs): String {
        val who = displayName(i) ?: i.connectionAddress?.takeIf { it.isNotBlank() }
        return if (who == null) "No receiver saved" else "Selected: $who"
    }

    // ── Chips ─────────────────────────────────────────────────────────────────────────────────

    private fun buildExternalChips(i: Inputs): List<Chip> =
        listOfNotNull(referenceChip(i.positionReference)) + buildExternalChipsBase(i)

    private fun buildExternalChipsBase(i: Inputs): List<Chip> =
        listOfNotNull(satsChip(i), dopChip(i), rtkChip(i), baseChip(i), nmeaChip(i), errorsChip(i))
            .ifEmpty { listOf(Chip("--")) }

    private fun buildInternalChips(i: Inputs): List<Chip> =
        listOfNotNull(satsChip(i), dopChip(i)) + Chip("Device GPS")

    private fun referenceChip(r: PositionReference?): Chip? = when (r) {
        PositionReference.POLE_TIP -> Chip("Pole tip", Severity.GOOD)
        PositionReference.ANTENNA -> Chip("Antenna")
        PositionReference.MAY_BE_COMPENSATED -> Chip("May be compensated", Severity.WARNING)
        PositionReference.UNCONFIRMED -> Chip("Reference unconfirmed", Severity.CAUTION)
        null -> null
    }

    /**
     * Satellite chip that matches the app-wide GNSS toolbar exactly: prefer SkyBus counts, fall
     * back to the fix, and when the visible count is unknown use "used" for both so it reads
     * "used/used" (e.g. "Sats 27/27") like the toolbar — never "Sats 27" alone.
     */
    private fun satsChip(i: Inputs): Chip? {
        val used = if (i.skyTotalUsed > 0) i.skyTotalUsed else (i.satsUsed ?: 0)
        val vis = if (i.skyTotalVisible > 0) i.skyTotalVisible else (i.satsVisible ?: used)
        if (used <= 0 && vis <= 0) return null
        return Chip("Sats " + GnssStatusFormatter.formatSatellites(used, maxOf(vis, used)))
    }

    /** Prefer HDOP; fall back to PDOP. */
    private fun dopChip(i: Inputs): Chip? {
        i.hdop?.let { return Chip("HDOP ${fmt1(it)}") }
        i.pdop?.let { return Chip("PDOP ${fmt1(it)}") }
        return null
    }

    private fun rtkChip(i: Inputs): Chip? {
        val age = i.correctionAgeS ?: return null
        if (age < 0) return null
        return if (age > STALE_CORRECTION_S) Chip("RTK stale", Severity.WARNING) else Chip("RTK ${GnssStatusFormatter.formatAgeSeconds(age)}")
    }

    private fun baseChip(i: Inputs): Chip? =
        i.correctionStationId?.takeIf { it.isNotBlank() }?.let { Chip("Base $it") }

    /**
     * NMEA stream rate (external only). Shown only for a real, positive live rate. A null
     * (unavailable) or sub-1/s rate is hidden rather than shown as a misleading "NMEA 0/s" — a
     * truly stopped/absent stream is conveyed by the headline (e.g. "No receiver data").
     */
    private fun nmeaChip(i: Inputs): Chip? {
        val rate = i.nmeaLinesPerSecond ?: return null
        if (rate < 1.0) return null
        return Chip("NMEA ${rate.toInt()}/s")
    }

    /** Recent parse errors only ("in the last minute"); the cumulative total stays on the Developer page. */
    private fun errorsChip(i: Inputs): Chip? =
        if (i.nmeaParseErrors > 0) Chip("Errors ${i.nmeaParseErrors} in 1 min", Severity.WARNING) else null

    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
}
