package app.surrealar.gnss.status

import app.surrealar.receiver.tilt.EpochReference
import app.surrealar.settings.model.CompensatedOutputSetting
import io.github.pennstatecs.geokit.gnss.model.RtkStatus

/**
 * Correction health of a live external fix, as the status UI words it. [OK] means "nothing to warn about" (also
 * when corrections do not apply, e.g. device GNSS): the UI never shows a permanent "Corrections OK".
 *
 * Ordered by severity ([rank]): a more severe state is entered at once, a less severe one only after the
 * hysteresis in [CorrectionHealthTracker].
 */
enum class CorrectionHealth(val rank: Int) { OK(0), AGING(1), STALE(2), UNAVAILABLE(3) }

/**
 * The one shared, pure interpretation of a live external GNSS fix: its solution quality and the warnings that apply.
 * Used by the Home Field Status card ([app.surrealar.ui.home.HomeFieldStatusMapper]), the app-wide toolbar
 * ([app.surrealar.ui.toolbar.GnssToolbarStateMapper]) and the GNSS Status report, so the three cannot contradict
 * each other: they read the same [Quality] and the same ordered [Warning]s; only their presentation differs.
 *
 * Deliberately conservative (moved here unchanged from the Home precedence table):
 * - RTK Fixed is never treated as proven accuracy: only a receiver-REPORTED horizontal accuracy (68 % radius)
 *   ≤ [HIGH_ACCURACY_H_M] reads [Quality.HIGH_ACCURACY]; a missing or estimated value never does.
 * - Any [Warning] outranks a positive quality headline (callers show the first warning instead of a GOOD headline).
 *
 * Raw data (fix, link, settings) → this interpretation (quality, warnings, severity) → presentation (each screen's
 * wording, colours, layout). No Android types.
 */
object GnssStatusInterpreter {

    /** Reported horizontal accuracy (m, 68 % radius) above which a non-RTK fix is flagged "Low accuracy". */
    const val LOW_ACCURACY_M = 1.0

    /**
     * Reported horizontal accuracy (m, 68 % radius) at or below which an RTK Fixed solution reads "High accuracy".
     * Only a receiver-REPORTED value counts (GST / $..ACCURACY); a missing or estimated value never does.
     */
    const val HIGH_ACCURACY_H_M = 0.05

    /** Correction age (s) above which RTK corrections are stale (the Home card's long-standing rule). */
    const val STALE_CORRECTION_S = 15.0

    /** Semantic severity; never the only signal — every state has text. */
    enum class Severity { GOOD, CAUTION, WARNING, NEUTRAL }

    /** RS4 / RS4 Pro: which point the live positions are (Phase 4 position reference). */
    enum class Reference { POLE_TIP, ANTENNA, UNCONFIRMED, MAY_BE_COMPENSATED }

    /** Solution quality of a live external fix (Home rows 11–16). */
    enum class Quality(val headline: String, val severity: Severity) {
        HIGH_ACCURACY("High accuracy", Severity.GOOD),
        FIX_UNVERIFIED("RTK Fixed — accuracy unverified", Severity.CAUTION),
        ACCURACY_BELOW_TARGET("Accuracy below target", Severity.CAUTION),
        FLOAT("RTK Float", Severity.CAUTION),
        LOW_ACCURACY("Low accuracy", Severity.WARNING),
        POSITION_AVAILABLE("Position available", Severity.NEUTRAL),
    }

    /**
     * A warning that applies to a live external fix, in precedence order (declaration order = priority; the first
     * one present is the headline). [text] is the full wording (Home headline, report); [shortText] is the compact
     * toolbar's narrow-width form. Home only ever produces [CORRECTIONS_STALE], the two reference warnings and
     * [VERTICAL_INVALID] (its precedence rows 8–10, unchanged).
     */
    enum class Warning(val text: String, val shortText: String, val severity: Severity) {
        CORRECTIONS_STALE("Corrections stale", "Corr. stale", Severity.WARNING),
        CORRECTIONS_UNAVAILABLE("Corrections unavailable", "No corr.", Severity.WARNING),
        REFERENCE_MAY_BE_COMPENSATED("Position reference unconfirmed", "Ref. unconfirmed", Severity.WARNING),
        REFERENCE_UNCONFIRMED("Position reference unconfirmed", "Ref. unconfirmed", Severity.CAUTION),
        CORRECTIONS_AGING("Corrections aging", "Corr. aging", Severity.CAUTION),
        VERTICAL_INVALID("Vertical accuracy invalid", "V invalid", Severity.CAUTION),
    }

    /** What the interpretation needs about a live external fix. */
    data class Inputs(
        val rtkStatus: RtkStatus,
        /** Horizontal accuracy (m, 68 % radius); null when unknown. */
        val hAccM: Double?,
        /** [hAccM] is an estimate (DOP × UERE), not receiver-reported: never counts as verified. */
        val hAccEstimated: Boolean,
        val corrections: CorrectionHealth,
        /** RS4 / RS4 Pro only; null = not applicable. */
        val reference: Reference? = null,
        /** The receiver's vertical accuracy is an invalid marker (e.g. Emlid 999 m). */
        val vAccInvalid: Boolean = false,
    )

    data class Interpretation(val quality: Quality, val warnings: List<Warning>) {
        /** The headline text: the first warning, else the quality. */
        val headline: String get() = warnings.firstOrNull()?.text ?: quality.headline
        val severity: Severity get() = warnings.firstOrNull()?.severity ?: quality.severity
        /** Only a GOOD quality with no warning at all may be shown as good (green). */
        val isGood: Boolean get() = warnings.isEmpty() && quality.severity == Severity.GOOD
    }

    fun interpret(i: Inputs): Interpretation {
        val warnings = buildList {
            when (i.corrections) {
                CorrectionHealth.STALE -> add(Warning.CORRECTIONS_STALE)
                CorrectionHealth.UNAVAILABLE -> add(Warning.CORRECTIONS_UNAVAILABLE)
                else -> Unit
            }
            when (i.reference) {
                Reference.MAY_BE_COMPENSATED -> add(Warning.REFERENCE_MAY_BE_COMPENSATED)
                Reference.UNCONFIRMED -> add(Warning.REFERENCE_UNCONFIRMED)
                else -> Unit
            }
            if (i.corrections == CorrectionHealth.AGING) add(Warning.CORRECTIONS_AGING)
            if (i.vAccInvalid) add(Warning.VERTICAL_INVALID)
        }
        return Interpretation(quality(i.rtkStatus, i.hAccM, i.hAccEstimated), warnings.sortedBy { it.ordinal })
    }

    /** Solution quality (Home rows 11–16). */
    fun quality(rtkStatus: RtkStatus, hAccM: Double?, hAccEstimated: Boolean): Quality {
        val reportedH = hAccM?.takeIf { !hAccEstimated && it.isFinite() && it >= 0.0 }
        return when (rtkStatus) {
            RtkStatus.FIX -> when {
                reportedH == null -> Quality.FIX_UNVERIFIED
                reportedH <= HIGH_ACCURACY_H_M -> Quality.HIGH_ACCURACY
                else -> Quality.ACCURACY_BELOW_TARGET
            }
            RtkStatus.FLOAT -> Quality.FLOAT
            else -> if (reportedH != null && reportedH > LOW_ACCURACY_M) Quality.LOW_ACCURACY else Quality.POSITION_AVAILABLE
        }
    }

    /** Raw (un-stabilised) staleness, the Home card's rule: a reported age above [STALE_CORRECTION_S]. */
    fun rawStale(correctionAgeS: Double?): CorrectionHealth =
        if (correctionAgeS != null && correctionAgeS > STALE_CORRECTION_S) CorrectionHealth.STALE else CorrectionHealth.OK

    /**
     * RS4 / RS4 Pro position reference from the tilt monitor's state; null when not applicable (other receivers,
     * device GNSS). Unknown compensated output with no epoch classification reads "unconfirmed".
     */
    fun referenceOf(applicable: Boolean, reference: EpochReference?, compensatedOutput: CompensatedOutputSetting): Reference? {
        if (!applicable) return null
        return when (reference) {
            EpochReference.POLE_TIP -> Reference.POLE_TIP
            EpochReference.ANTENNA -> Reference.ANTENNA
            EpochReference.MAY_BE_COMPENSATED -> Reference.MAY_BE_COMPENSATED
            EpochReference.ANTENNA_UNCERTAIN -> Reference.UNCONFIRMED
            EpochReference.NOT_APPLICABLE, null ->
                if (compensatedOutput == CompensatedOutputSetting.UNKNOWN) Reference.UNCONFIRMED else null
        }
    }
}
