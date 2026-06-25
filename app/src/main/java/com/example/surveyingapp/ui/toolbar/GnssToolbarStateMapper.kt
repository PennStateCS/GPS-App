package com.example.surveyingapp.ui.toolbar

import com.example.surveyingapp.domain.model.LocationSourceType
import com.example.surveyingapp.gnss.format.GnssStatusFormatter
import com.example.surveyingapp.gnss.model.Fix
import com.example.surveyingapp.gnss.model.Provider
import com.example.surveyingapp.gnss.model.RtkStatus
import com.example.surveyingapp.gnss.model.SkySnapshot
import java.time.Duration
import java.time.Instant
import java.util.Locale

/** Max age of a fix before the toolbar treats it as stale and ignores it. */
const val TOOLBAR_FIX_MAX_AGE_MS = 15_000L

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
 *  - stale fixes (older than [TOOLBAR_FIX_MAX_AGE_MS]) → Ignore
 *  - invalid coordinates → Ignore
 *  - no current-provider fix → Render(waiting)
 */
object GnssToolbarStateMapper {

    fun fixMatchesSource(source: LocationSourceType, fix: Fix): Boolean =
        if (source == LocationSourceType.INTERNAL) fix.provider == Provider.INTERNAL
        else fix.provider != Provider.INTERNAL

    /** Display state shown while there is no current-provider fix (just switched / acquiring). */
    fun waiting(source: LocationSourceType): GnssToolbarState {
        val isInternal = source == LocationSourceType.INTERNAL
        return GnssToolbarState(
            sourceText = GnssStatusFormatter.formatSource(isInternal),
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
        nowMs: Long
    ): ToolbarMapResult {
        if (fix == null) return ToolbarMapResult.Render(waiting(selectedSource))
        if (!fixMatchesSource(selectedSource, fix)) return ToolbarMapResult.Ignore("wrong-provider")
        val ageMs = try {
            Duration.between(fix.timeUtc, Instant.ofEpochMilli(nowMs)).toMillis()
        } catch (_: Exception) { 0L }
        if (ageMs > TOOLBAR_FIX_MAX_AGE_MS) return ToolbarMapResult.Ignore("stale")
        if (fix.latDeg !in -90.0..90.0 || fix.lonDeg !in -180.0..180.0) {
            return ToolbarMapResult.Ignore("invalid-coords")
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
        val hAcc = fix.hAccM
        val accVisible = hAcc != null && hAcc in 0.0..9999.0
        val accuracyText: String?
        val accuracyLevel: GnssStatusLevel
        if (accVisible && hAcc != null) {
            accuracyText = GnssStatusFormatter.formatAccuracyMeters(hAcc)
            accuracyLevel = when {
                hAcc <= 0.05 -> GnssStatusLevel.SUCCESS
                hAcc <= 0.30 -> GnssStatusLevel.SUCCESS
                hAcc <= 1.0  -> GnssStatusLevel.WARNING
                else         -> GnssStatusLevel.ERROR
            }
        } else {
            accuracyText = null
            accuracyLevel = GnssStatusLevel.NONE
        }

        // --- COORDINATES ---
        val latLonText = "${String.format(Locale.US, "%.6f", fix.latDeg)}, " +
            String.format(Locale.US, "%.6f", fix.lonDeg)

        // --- ALTITUDE: prefer MSL, else ellipsoidal, validate range ---
        val altMsl = fix.altMslM
        val altEllip = fix.altEllipsoidalM
        val altitudeText = when {
            altMsl != null && altMsl in -500.0..10000.0 -> String.format(Locale.US, "%.2f m", altMsl)
            altEllip != null && altEllip in -500.0..10000.0 -> String.format(Locale.US, "%.2f m", altEllip)
            else -> "--"
        }

        return ToolbarMapResult.Render(
            GnssToolbarState(
                sourceText = GnssStatusFormatter.formatSource(isInternal),
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
                isStale = false
            )
        )
    }
}
