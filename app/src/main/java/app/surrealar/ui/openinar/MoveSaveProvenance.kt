package app.surrealar.ui.openinar

import io.github.pennstatecs.geokit.ar.model.ModelMove
import java.util.Locale
import kotlin.math.hypot

/**
 * How a saved Move model adjustment was made, for the diagnostics log (no schema change): ARCore's
 * localization at the time, in coarse buckets, whether the anchors were placed by timeout, and the
 * size of the move. Never a coordinate: no latitude, longitude or altitude.
 */
data class MoveSaveProvenance(
    val qualityGood: Boolean,
    val hAccM: Double?,
    val yawAccDeg: Double?,
    val placedByTimeout: Boolean,
) {
    /** One log line for [move] saved on [coordinateId]; [storedHorizM] is the stored offset's size after the save. */
    fun logLine(coordinateId: String, move: ModelMove, storedHorizM: Double?): String =
        "AR model move saved coordinateId=$coordinateId provenance=arcore_localization " +
            "arQuality=${if (qualityGood) "GOOD" else "NOT_GOOD"} hAcc=${hAccBucket(hAccM)} yawAcc=${yawBucket(yawAccDeg)} " +
            "placedByTimeout=$placedByTimeout " +
            "moveHorizM=${fmt2(hypot(move.eastM.toDouble(), move.northM.toDouble()))} moveUpM=${fmt2(move.upM.toDouble())} " +
            "turnDeg=${String.format(Locale.US, "%.1f", move.yawDeg)} storedHorizM=${storedHorizM?.let(::fmt2) ?: "0.00"}"

    companion object {
        /** ARCore horizontal accuracy (68% radius) as a bucket. */
        fun hAccBucket(m: Double?): String = when {
            m == null || !m.isFinite() -> "unknown"
            m <= 0.5 -> "<=0.5m"
            m <= 1.0 -> "<=1m"
            m <= 2.0 -> "<=2m"
            m <= 5.0 -> "<=5m"
            else -> ">5m"
        }

        /** ARCore heading (yaw) accuracy as a bucket. */
        fun yawBucket(deg: Double?): String = when {
            deg == null || !deg.isFinite() -> "unknown"
            deg <= 5.0 -> "<=5deg"
            deg <= 10.0 -> "<=10deg"
            deg <= 15.0 -> "<=15deg"
            else -> ">15deg"
        }

        private fun fmt2(v: Double) = String.format(Locale.US, "%.2f", v)
    }
}
