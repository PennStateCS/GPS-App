package app.surrealar.ui.toolbar

import app.surrealar.domain.model.LocationSourceType
import app.surrealar.gnss.accuracy.AccuracyEstimator
import app.surrealar.gnss.accuracy.UereTable
import app.surrealar.gnss.format.GnssStatusFormatter
import app.surrealar.gnss.model.Fix
import app.surrealar.gnss.model.Provider
import app.surrealar.gnss.model.RtkStatus
import app.surrealar.gnss.model.SkySnapshot
import java.util.Locale

/** Result of mapping live GNSS inputs: either render a state, or ignore this fix (with a reason). */
sealed interface ToolbarMapResult {
    data class Render(val state: GnssToolbarState) : ToolbarMapResult
    data class Ignore(val reason: String) : ToolbarMapResult
}

/**
 * Pure mapper: turns the current GNSS inputs into a [GnssToolbarState]. Holds ALL the display
 * decision logic that used to live in `MainActivity.updateStatusTokens` so it can be unit-tested
 * without an Activity. No Android view, Context, or color lookups here.
 *
 * Source-switch protections live here too:
 *  - wrong-provider fixes (internal fix while RS2+ selected during the connecting window) → Ignore
 *  - invalid coordinates → Render(waiting) (never leave older values on screen)
 *  - no current-provider fix → Render(waiting)
 *
 * Liveness is NOT judged here by comparing the fix's GNSS timestamp with the device clock (a skewed
 * tablet clock froze the toolbar). The input is `FixSwitchboard.currentFix`, which the GNSS feed
 * sets to null when fixes stop arriving (receive-time watchdog) — that null renders "waiting".
 */
object GnssToolbarStateMapper {

    private val uereTable = UereTable()

    /**
     * Accuracy colour. External receivers are graded for survey work (RTK: green ≤ 0.30 m, amber
     * ≤ 1 m). Internal phone/tablet GNSS can never reach that, so grading it the same way made it
     * permanently red; it gets phone-scale thresholds instead (green ≤ 5 m, amber ≤ 15 m).
     */
    internal fun accuracyLevel(hAccM: Double, isInternal: Boolean): GnssStatusLevel =
        if (isInternal) when {
            hAccM <= INTERNAL_GOOD_M -> GnssStatusLevel.SUCCESS
            hAccM <= INTERNAL_FAIR_M -> GnssStatusLevel.WARNING
            else                     -> GnssStatusLevel.ERROR
        } else when {
            hAccM <= 0.30 -> GnssStatusLevel.SUCCESS
            hAccM <= 1.0  -> GnssStatusLevel.WARNING
            else          -> GnssStatusLevel.ERROR
        }

    internal const val INTERNAL_GOOD_M = 5.0
    internal const val INTERNAL_FAIR_M = 15.0

    fun fixMatchesSource(source: LocationSourceType, fix: Fix): Boolean =
        if (source == LocationSourceType.INTERNAL) fix.provider == Provider.INTERNAL
        else fix.provider != Provider.INTERNAL

    /** Display state shown while there is no current-provider fix (just switched / acquiring). */
    fun waiting(source: LocationSourceType, externalLabel: String = "RS2+"): GnssToolbarState {
        val isInternal = source == LocationSourceType.INTERNAL
        return GnssToolbarState(
            sourceText = GnssStatusFormatter.formatSource(isInternal, externalLabel),
            // External shows an explicit "Waiting" so RS2+ is never paired with an ambiguous blank.
            fixText = if (isInternal) "--" else "Waiting",
            fixLevel = GnssStatusLevel.NONE,
            satelliteText = null,
            accuracyText = null,
            accuracyLevel = GnssStatusLevel.NONE,
            latLonText = "--",
            altitudeText = "--",
            correctionText = null,
            batteryVisible = !isInternal,
            batteryText = null,
            statusLevel = GnssStatusLevel.NONE,
            isExternal = !isInternal,
            isWaiting = true,
            isStale = false
        )
    }

    fun map(
        selectedSource: LocationSourceType,
        fix: Fix?,
        sky: SkySnapshot,
        externalLabel: String = "RS2+",
        /** The fix expressed in the user's coordinate system (null = legacy lat/lon + MSL display). */
        coords: app.surrealar.coords.ConvertedPosition? = null,
    ): ToolbarMapResult {
        if (fix == null) return ToolbarMapResult.Render(waiting(selectedSource, externalLabel))
        if (!fixMatchesSource(selectedSource, fix)) return ToolbarMapResult.Ignore("wrong-provider")
        if (fix.latDeg !in -90.0..90.0 || fix.lonDeg !in -180.0..180.0) {
            return ToolbarMapResult.Render(waiting(selectedSource, externalLabel))
        }

        val isInternal = selectedSource == LocationSourceType.INTERNAL

        // --- FIX / SOL --- (label wording is shared via GnssStatusFormatter; level stays here)
        val fixText = GnssStatusFormatter.formatFixStatus(fix.rtkStatus, isInternal)
        val fixLevel = if (isInternal) {
            when (fix.rtkStatus) {
                RtkStatus.NONE, RtkStatus.INVALID -> GnssStatusLevel.ERROR
                else -> GnssStatusLevel.SUCCESS
            }
        } else {
            when (fix.rtkStatus) {
                RtkStatus.NONE           -> GnssStatusLevel.ERROR
                RtkStatus.SINGLE         -> GnssStatusLevel.WARNING
                RtkStatus.DGPS           -> GnssStatusLevel.INFO
                RtkStatus.FLOAT          -> GnssStatusLevel.WARNING
                RtkStatus.FIX            -> GnssStatusLevel.SUCCESS
                RtkStatus.DEAD_RECKONING -> GnssStatusLevel.WARNING
                RtkStatus.INVALID        -> GnssStatusLevel.ERROR
            }
        }

        // --- SATS ---
        val used = if (sky.totalUsed > 0) sky.totalUsed else fix.satsUsed.coerceIn(0, 100)
        val vis = if (sky.totalVisible > 0) sky.totalVisible else (fix.satsVisible ?: used).coerceIn(used, 100)
        val satelliteText = if (used > 0 || vis > 0) GnssStatusFormatter.formatSatellites(used, vis) else null

        // --- ACCURACY ---
        // Receiver-reported (GST / platform) when available; otherwise the same DOP × UERE estimate
        // capture uses, marked with "~" so it is never mistaken for a reported value. Many phones'
        // NMEA has no GST, so without this the internal source never showed an accuracy.
        val reportedH = fix.hAccM
        val hAcc = reportedH ?: AccuracyEstimator.estimate1SigmaMeters(fix, uereTable).horizontalMeters
        val estimated = reportedH == null
        val accuracyText: String?
        val accuracyLevel: GnssStatusLevel
        if (hAcc != null && hAcc in 0.0..9999.0) {
            accuracyText = (if (estimated) "~" else "") + GnssStatusFormatter.formatAccuracyMeters(hAcc)
            accuracyLevel = accuracyLevel(hAcc, isInternal = selectedSource == LocationSourceType.INTERNAL)
        } else {
            accuracyText = null
            accuracyLevel = GnssStatusLevel.NONE
        }

        // --- COORDINATES ---
        // --- COORDINATES / HEIGHT: in the user's coordinate system when provided ---
        val latLonText: String
        val altitudeText: String
        var coordLabel = "LL"
        var altitudeLabel = "ALT"
        if (coords != null) {
            latLonText = if (coords.easting != null && coords.northing != null) {
                coordLabel = "NE"
                app.surrealar.coords.CoordinateFormat.horizontal(coords)
            } else {
                app.surrealar.coords.CoordinateFormat.horizontal(coords, latLonDecimals = 6)
            }
            altitudeLabel = coords.vertical.shortLabel.uppercase(Locale.US)
            altitudeText = coords.height?.let {
                String.format(Locale.US, "%,.2f %s", it, coords.unit.suffix)
            } ?: "--"
        } else {
            latLonText = "${String.format(Locale.US, "%.6f", fix.latDeg)}, " +
                String.format(Locale.US, "%.6f", fix.lonDeg)
            // --- ALTITUDE: prefer MSL, else ellipsoidal, validate range ---
            val altMsl = fix.altMslM
            val altEllip = fix.altEllipsoidalM
            altitudeText = when {
                altMsl != null && altMsl in -500.0..10000.0 -> String.format(Locale.US, "%.2f m", altMsl)
                altEllip != null && altEllip in -500.0..10000.0 -> String.format(Locale.US, "%.2f m", altEllip)
                else -> "--"
            }
        }

        return ToolbarMapResult.Render(
            GnssToolbarState(
                sourceText = GnssStatusFormatter.formatSource(isInternal, externalLabel),
                fixText = fixText,
                fixLevel = fixLevel,
                satelliteText = satelliteText,
                accuracyText = accuracyText,
                accuracyLevel = accuracyLevel,
                latLonText = latLonText,
                altitudeText = altitudeText,
                correctionText = null,
                batteryVisible = !isInternal,
                batteryText = null,
                statusLevel = fixLevel,
                isExternal = !isInternal,
                isWaiting = false,
                isStale = false,
                coordLabel = coordLabel,
                altitudeLabel = altitudeLabel
            )
        )
    }
}
