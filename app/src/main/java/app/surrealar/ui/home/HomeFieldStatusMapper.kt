package app.surrealar.ui.home

import app.surrealar.gnss.format.GnssStatusFormatter
import app.surrealar.gnss.model.RtkStatus
import java.util.Locale

/**
 * Pure mapper: live GNSS fix/source/stream data → a polished, plain-language "Field Status" summary
 * for the Home dashboard. No Android types, so it is trivially unit-testable.
 *
 * It is a *summary*, not a second toolbar: it reuses [GnssStatusFormatter] for the source/fix/
 * accuracy wording so the Home card never drifts from the app-wide GNSS toolbar, and collapses the
 * live state into one headline + two always-present detail lines + a compact, ordered chip row.
 * Callers map [Severity] → colors/icons and [Action] → a navigation destination.
 */
object HomeFieldStatusMapper {

    /** Horizontal accuracy (m) above which a non-RTK fix is flagged "Low accuracy". */
    const val LOW_ACCURACY_M = 1.0

    /** Correction age (s) above which RTK corrections are considered stale (matches status bar). */
    const val STALE_CORRECTION_S = 15.0

    /**
     * Overall field-status state shown on the Home card, derived from the live source/fix/correction
     * inputs (e.g. ready, float, low-accuracy, waiting, stale corrections).
     */
    enum class State {
        READY, HIGH_ACCURACY, FLOAT, LOW_ACCURACY,
        WAITING, NO_RECEIVER_DATA, INTERNAL_GPS, CORRECTIONS_STALE,
    }

    /** Semantic severity for accents (color + icon); never the *only* signal — text always says it. */
    enum class Severity { GOOD, CAUTION, WARNING, NEUTRAL }

    /** Where the card's header action navigates. */
    enum class Action { OPEN_MAP, RECEIVER_SETTINGS }

    /** A compact metadata chip, e.g. "Sats 29/29", "RTK 1s", "Errors 3". */
    data class Chip(val label: String, val severity: Severity = Severity.NEUTRAL)

    /**
     * Immutable Field Status summary for the Home card: a headline, two detail lines, and an ordered
     * chip row, plus a severity and optional action. Callers map severity to colors/icons and the
     * action to a navigation destination.
     */
    data class FieldStatus(
        val state: State,
        val headline: String,
        /** Always present. e.g. "RS2+ · Float · H ±0.04 m · V ±0.06 m". */
        val primaryDetail: String,
        /** Always present. e.g. "Reach6 · 192.168.2.174:9000" or "Android device location". */
        val receiverDetail: String,
        /** Ordered compact chips; never empty (a placeholder/"No fix" chip is used when bare). */
        val chips: List<Chip>,
        val severity: Severity,
        val actionLabel: String,
        val actionDestination: Action,
    )

    /**
     * All inputs the card needs, gathered by the caller (it owns "now" / the live flows) so this
     * function stays pure. [hasRecentFix] = the live snapshot currently carries valid coordinates.
     */
    data class Inputs(
        val isInternal: Boolean,
        val externalLabel: String,
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
        val nmeaParseErrors: Long,
        val hasRecentFix: Boolean,
    )

    fun map(i: Inputs): FieldStatus {
        val source = GnssStatusFormatter.formatSource(i.isInternal, i.externalLabel)
        val receiverDetail = buildReceiverDetail(i)
        val openMap = "Open Map"

        // 1. No usable position yet.
        if (!i.hasRecentFix) {
            return if (i.isInternal) {
                FieldStatus(
                    State.WAITING, "Waiting for live position",
                    "$source · acquiring…", receiverDetail,
                    listOf(Chip("No fix")),
                    Severity.NEUTRAL, openMap, Action.OPEN_MAP,
                )
            } else {
                FieldStatus(
                    State.NO_RECEIVER_DATA, "No receiver data",
                    "$source · waiting for live position", receiverDetail,
                    buildNoDataChips(i),
                    Severity.WARNING, "Receiver Settings", Action.RECEIVER_SETTINGS,
                )
            }
        }

        val primary = buildPrimaryDetail(source, i)

        // 2. Internal device GPS is active — informational, not RTK-graded.
        if (i.isInternal) {
            return FieldStatus(
                State.INTERNAL_GPS, "Using internal GPS",
                primary, receiverDetail, buildInternalChips(i),
                Severity.NEUTRAL, openMap, Action.OPEN_MAP,
            )
        }

        // 3. External receiver with a recent fix — RTK-aware.
        val chips = buildExternalChips(i)
        if (i.correctionAgeS != null && i.correctionAgeS > STALE_CORRECTION_S) {
            return FieldStatus(State.CORRECTIONS_STALE, "Corrections stale", primary, receiverDetail, chips, Severity.WARNING, openMap, Action.OPEN_MAP)
        }
        when (i.rtkStatus) {
            RtkStatus.FIX ->
                return FieldStatus(State.HIGH_ACCURACY, "High accuracy", primary, receiverDetail, chips, Severity.GOOD, openMap, Action.OPEN_MAP)
            RtkStatus.FLOAT ->
                return FieldStatus(State.FLOAT, "Using float solution", primary, receiverDetail, chips, Severity.CAUTION, openMap, Action.OPEN_MAP)
            else -> Unit // DGPS / SINGLE / NONE / DR fall through to accuracy-based wording.
        }
        if (i.hAccM != null && i.hAccM > LOW_ACCURACY_M) {
            return FieldStatus(State.LOW_ACCURACY, "Low accuracy", primary, receiverDetail, chips, Severity.WARNING, openMap, Action.OPEN_MAP)
        }
        return FieldStatus(State.READY, "Ready for field work", primary, receiverDetail, chips, Severity.GOOD, openMap, Action.OPEN_MAP)
    }

    // ── Detail lines ──────────────────────────────────────────────────────────────────────────

    private fun buildPrimaryDetail(source: String, i: Inputs): String {
        val parts = mutableListOf(source, GnssStatusFormatter.formatFixStatus(i.rtkStatus, i.isInternal))
        i.hAccM?.let { parts += "H " + GnssStatusFormatter.formatAccuracyMeters(it) }
        // Vertical accuracy is meaningful for external RTK; internal device GPS shows horizontal only.
        if (!i.isInternal) i.vAccM?.let { parts += "V " + GnssStatusFormatter.formatAccuracyMeters(it) }
        return parts.joinToString(" · ")
    }

    private fun buildReceiverDetail(i: Inputs): String {
        if (i.isInternal) return "Android device location"
        val name = i.receiverName?.takeIf { it.isNotBlank() }
        val addr = i.connectionAddress?.takeIf { it.isNotBlank() }
        return when {
            name != null && addr != null -> "$name · $addr"
            addr != null -> addr
            name != null -> name
            else -> "External receiver"
        }
    }

    // ── Chips ─────────────────────────────────────────────────────────────────────────────────

    private fun buildExternalChips(i: Inputs): List<Chip> =
        listOfNotNull(satsChip(i), dopChip(i), rtkChip(i), baseChip(i), nmeaChip(i), errorsChip(i))
            .ifEmpty { listOf(Chip("--")) }

    private fun buildInternalChips(i: Inputs): List<Chip> =
        listOfNotNull(satsChip(i), dopChip(i)) + Chip("Device GPS")

    private fun buildNoDataChips(i: Inputs): List<Chip> =
        // The absent stream is conveyed by the "No receiver data" headline — not a misleading
        // "NMEA 0/s" chip. Just flag the missing fix and point at the receiver settings.
        listOf(Chip("No fix", Severity.WARNING), Chip("Check receiver", Severity.WARNING))

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
        return if (age > STALE_CORRECTION_S) Chip("RTK stale", Severity.WARNING) else Chip("RTK ${age.toInt()}s")
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

    private fun errorsChip(i: Inputs): Chip? =
        if (i.nmeaParseErrors > 0) Chip("Errors ${i.nmeaParseErrors}", Severity.WARNING) else null

    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
}
