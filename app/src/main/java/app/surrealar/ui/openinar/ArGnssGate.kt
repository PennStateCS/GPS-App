package app.surrealar.ui.openinar

import io.github.pennstatecs.geokit.gnss.accuracy.AccuracyEstimator
import io.github.pennstatecs.geokit.gnss.accuracy.UereTable
import io.github.pennstatecs.geokit.gnss.model.Fix
import io.github.pennstatecs.geokit.gnss.model.RtkStatus

/**
 * The GNSS half of the AR anchor gate: is the receiver's position good enough to place geospatial
 * anchors? (ARCore's own Earth localization is gated separately by GeoAnchorGate.)
 *
 * Accuracy comes from the same place as everywhere else in the app — [AccuracyEstimator] with the
 * shared [UereTable]: the receiver's own 68 % radius when it sends one, else DOP × UERE for the fix's
 * accuracy class. A fix with no satellite solution (NONE, DEAD_RECKONING, INVALID) never passes.
 */
internal object ArGnssGate {

    /** Positions worse than this (68 % radius, metres) don't place anchors. */
    const val MAX_ACCURACY_M: Double = 20.0

    private val uere = UereTable()

    /** False for fixes with no satellite solution: they never place anchors. */
    fun hasSolution(status: RtkStatus): Boolean =
        status != RtkStatus.NONE && status != RtkStatus.DEAD_RECKONING && status != RtkStatus.INVALID

    /** Horizontal accuracy (68 % radius, m) the gate judges, or null when it can't be known. */
    fun accuracyM(fix: Fix): Double? = AccuracyEstimator.estimate1SigmaMeters(fix, uere).horizontalMeters

    /** True when [fix] may place anchors: it has a solution and isn't known to be worse than [maxM]. */
    fun allows(fix: Fix, maxM: Double = MAX_ACCURACY_M): Boolean =
        hasSolution(fix.rtkStatus) && (accuracyM(fix)?.let { it <= maxM } ?: true)
}
